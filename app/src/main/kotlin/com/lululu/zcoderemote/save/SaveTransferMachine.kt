package com.lululu.zcoderemote.save

/**
 * 保存副本的传输状态机（纯逻辑，单元测试在 SaveTransferMachineTest）。
 *
 * 一次保存的走法：
 * 1. 页面点了一个带 `download` 的 blob 链接，发来 [SaveEvent.Request]（文件名、大小、MIME）。
 * 2. 原生弹系统「保存文件」([SaveAction.AskTarget])；用户选好位置回 [SaveEvent.TargetPicked]，
 *    原生回话「可以发了」([SaveAction.Ready])；用户取消回 [SaveEvent.TargetCancelled]。
 * 3. 页面按块发 ArrayBuffer；每块 [SaveEvent.Chunk]，原生收一块回一次 ack（ack 由桥自己做，不经过这里）。
 * 4. 发完 [SaveEvent.End]；字节数对上就 [SaveAction.Done]。
 *
 * 任何一步对不上（顺序不对、字节数不对、页面自己放弃、App 这头打断）都把状态打回 [SaveTransfer.Idle]，
 * 决不会卡在一个中间态里：页面的取数超时是 30 秒，App 这头的空闲计时（桥里）比它宽。
 */
sealed interface SaveEvent {
    /** 页面发来的保存请求。 */
    data class Request(val request: SaveRequest) : SaveEvent

    /** 用户在系统保存框里选好了位置。 */
    data object TargetPicked : SaveEvent

    /** 用户取消了系统保存框。 */
    data object TargetCancelled : SaveEvent

    /** 收到一块数据，length 是字节数。 */
    data class Chunk(val length: Int) : SaveEvent

    /** 页面说数据发完了。 */
    data object End : SaveEvent

    /** 页面自己放弃了（Blob 读不出来之类），不用回话。 */
    data object Abort : SaveEvent

    /** App 这头打断：空闲超时、页面换文档、WebView 挂掉、内核清了。 */
    data object Reset : SaveEvent
}

/** 状态机让桥去做的事。 */
sealed interface SaveAction {
    /** 弹系统保存框。 */
    data class AskTarget(val request: SaveRequest) : SaveAction

    /** 回话页面：位置选好了，开始发数据。 */
    data object Ready : SaveAction

    /** 回话页面：用户取消了，别等了。 */
    data object Cancelled : SaveAction

    /** 回话页面：出了错，别再发了。 */
    data object Error : SaveAction

    /** 回话页面：写完了。回话前桥要把流冲干净。 */
    data object Done : SaveAction

    /**
     * 静默打回 Idle：不回话（页面已经不在了，或者本来就没有传输），桥把半截文件收拾掉。
     */
    data object QuietReset : SaveAction
}

/** 一次保存的状态。 */
sealed interface SaveTransfer {
    /** 没有正在进行（也没有挂起）的保存。 */
    data object Idle : SaveTransfer

    /** 等用户在系统保存框里选位置。 */
    data class AwaitingTarget(val request: SaveRequest) : SaveTransfer

    /** 正在收数据。receivedBytes 是已经收到的字节数。 */
    data class Receiving(val request: SaveRequest, val receivedBytes: Long) : SaveTransfer
}

/** 一步的结论：新状态，加上桥要做的事（没有就是只记账）。 */
data class SaveStep(val transfer: SaveTransfer, val action: SaveAction?)

object SaveTransferMachine {

    fun on(state: SaveTransfer, event: SaveEvent): SaveStep = when (event) {
        is SaveEvent.Request -> onRequest(state, event.request)
        SaveEvent.TargetPicked -> onTargetPicked(state)
        SaveEvent.TargetCancelled -> onTargetCancelled(state)
        is SaveEvent.Chunk -> onChunk(state, event.length)
        SaveEvent.End -> onEnd(state)
        SaveEvent.Abort -> onAbort(state)
        SaveEvent.Reset -> onReset(state)
    }

    /** 只有 Idle 接新请求；已经在走的一律不接（页面的保存一次只走一条，第二条点击是它自己的乱序）。 */
    private fun onRequest(state: SaveTransfer, request: SaveRequest): SaveStep = when (state) {
        is SaveTransfer.Idle -> SaveStep(SaveTransfer.AwaitingTarget(request), SaveAction.AskTarget(request))
        else -> SaveStep(state, null)
    }

    private fun onTargetPicked(state: SaveTransfer): SaveStep = when (state) {
        is SaveTransfer.AwaitingTarget ->
            SaveStep(SaveTransfer.Receiving(state.request, 0), SaveAction.Ready)
        else -> SaveStep(state, null)
    }

    private fun onTargetCancelled(state: SaveTransfer): SaveStep = when (state) {
        is SaveTransfer.AwaitingTarget -> SaveStep(SaveTransfer.Idle, SaveAction.Cancelled)
        is SaveTransfer.Receiving ->
            // 位置已经选好了还会来「取消」：不该发生。按协议错误处理，让页面停下来。
            SaveStep(SaveTransfer.Idle, SaveAction.Error)
        SaveTransfer.Idle -> SaveStep(state, null)
    }

    private fun onChunk(state: SaveTransfer, length: Int): SaveStep = when (state) {
        is SaveTransfer.Receiving ->
            if (length >= 0 && state.receivedBytes + length <= state.request.size) {
                SaveStep(state.copy(receivedBytes = state.receivedBytes + length), null)
            } else {
                // 收到的超过页面报的大小：虚报了，或者两边协议没对齐。停，半截文件由桥收拾。
                SaveStep(SaveTransfer.Idle, SaveAction.Error)
            }
        else -> SaveStep(state, null)
    }

    private fun onEnd(state: SaveTransfer): SaveStep = when (state) {
        is SaveTransfer.Receiving ->
            if (state.receivedBytes == state.request.size) {
                SaveStep(SaveTransfer.Idle, SaveAction.Done)
            } else {
                // 数据没发完就说发完了：当成出错，半截文件不能用。
                SaveStep(SaveTransfer.Idle, SaveAction.Error)
            }
        else -> SaveStep(state, null)
    }

    private fun onAbort(state: SaveTransfer): SaveStep = when (state) {
        // 页面自己放弃了：不用回话，桥把半截文件收拾掉（保存框要是还开着，等它自己收场，那时状态已在 Idle）。
        is SaveTransfer.AwaitingTarget, is SaveTransfer.Receiving -> SaveStep(SaveTransfer.Idle, SaveAction.QuietReset)
        SaveTransfer.Idle -> SaveStep(state, null)
    }

    private fun onReset(state: SaveTransfer): SaveStep = when (state) {
        is SaveTransfer.AwaitingTarget, is SaveTransfer.Receiving -> SaveStep(SaveTransfer.Idle, SaveAction.QuietReset)
        SaveTransfer.Idle -> SaveStep(state, null)
    }
}

/** 页面发来的保存请求（`begin` 消息解析出来的）。 */
data class SaveRequest(
    /** 清洗过的文件名（[SaveProtocol.suggestedFileName] 的产物）。 */
    val name: String,
    /** 页面报的文件大小，字节。 */
    val size: Long,
    /** 页面给的 MIME 类型，没有就是 null。 */
    val mime: String?,
)
