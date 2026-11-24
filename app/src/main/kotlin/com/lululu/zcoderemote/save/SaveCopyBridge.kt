package com.lululu.zcoderemote.save

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lululu.zcoderemote.R
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 「保存副本」的通道（方案第 16 条）：页面脚本 ↔ 原生。安装进 WebView 的东西：
 *
 * - `WebViewCompat.addWebMessageListener`：往匹配来源的页面里注入 JS 对象 [SaveCopyScript.JS_OBJECT_NAME]，
 *   接住页面发来的控制消息（JSON 字符串）和数据块（ArrayBuffer）；
 * - `WebViewCompat.addDocumentStartJavaScript`：把 [SaveCopyScript] 在文档开始时注进去。
 *
 * `allowedOriginRules` 只有 [SaveCopyScript.ALLOWED_ORIGIN] 一条——只对官方手机页生效，
 * 不是通用桥（docs/ARCHITECTURE.md 6.2）。特性不支持就什么都不装（页面不配合就静默降级）；
 * 装上了之后哪一步走不通（保存框弹不出来、协议对不上、写盘失败），提示之后把流程收场，不崩溃、不卡住。
 *
 * 文件由 `ActivityResultContracts.CreateDocument` 的结果落地（界面层实现 [Picker]）：
 * 拿到位置后开流，页面来一块写一块（单线程后台写，主线程只记账），写完冲掉关闭再报成功。
 */
class SaveCopyBridge(
    /** Application 的 Context：提示和 ContentResolver 用。与 Activity 无关，活多久都行。 */
    private val appContext: Context,
    /** 需要 Activity 才能弹的系统保存框，由内核转发给界面层实现。 */
    private val picker: Picker,
) : WebViewCompat.WebMessageListener {

    /** 界面层要做的事：弹系统「保存文件」。 */
    interface Picker {
        /** 发起系统保存框（[androidx.activity.result.contract.ActivityResultContracts.CreateDocument]）。发起成功返回 true。 */
        fun launchSavePicker(request: SaveRequest): Boolean
    }

    /** 页面发来的控制消息。 */
    private sealed interface Control {
        data class Begin(val request: SaveRequest) : Control
        data object End : Control
        data object Abort : Control

        /** 脚本拦到了保存点击，但 WebView 收不了 ArrayBuffer 消息：提示后撒手。 */
        data object Unsupported : Control
    }

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { task -> Thread(task, "zcode-save-io").apply { isDaemon = true } }

    private var released = false

    /** 当前保存的状态（状态机见 [SaveTransferMachine]）。只在主线程碰。 */
    private var transfer: SaveTransfer = SaveTransfer.Idle

    /** 回话页面用的代理。页面先开口才有它；换一次消息换一次。只在主线程碰。 */
    private var reply: androidx.webkit.JavaScriptReplyProxy? = null

    /** 用户选好的保存位置。TargetPicked 之后才有；写盘失败、中途放弃时要删半截文件。 */
    private var targetUri: Uri? = null

    /** 输出流。只在 io 线程碰：开了就写，收尾关。 */
    private var out: OutputStream? = null

    /** 本次传输的代号；选好位置时加一。io 线程读。 */
    @Volatile
    private var streamGen = 0

    /** io 线程发现写挂了就记下当时的代号；只读写在这一个线程上。 */
    @Volatile
    private var failedGen = -1

    /** 数据停下这么久没收（页面取数超时是 30 秒，notes 第 8 节），这条传输就当死了。 */
    private val inactivityTimeout = Runnable {
        dispatch(SaveEvent.Reset)
        toast(R.string.save_failed)
    }

    // ================================================================================ 安装与拆线

    /**
     * 往 [view] 上装通道。消息通道和文档起始脚本是底线，缺一个就什么都装不了——
     * 注不进脚本就拦不到点击，也没有「用户想保存」这件事可以提示。装不上返回 false。
     * ArrayBuffer 消息收不了时仍然装（点击拦得到）：脚本拦到后只报一声 `unsupported`，
     * 原生据此提示「暂不支持保存」。
     * 必须在该 WebView 首次 loadUrl 之前调用；每个新实例装一次（渲染进程挂掉重建后也会走到这里）。
     */
    fun install(view: WebView): Boolean {
        val listenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val scriptSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!listenerSupported || !scriptSupported) return false
        val arrayBufferSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        return try {
            WebViewCompat.addWebMessageListener(
                view,
                SaveCopyScript.JS_OBJECT_NAME,
                setOf(SaveCopyScript.ALLOWED_ORIGIN),
                this,
            )
            WebViewCompat.addDocumentStartJavaScript(
                view,
                SaveCopyScript.build(arrayBufferSupported = arrayBufferSupported),
                setOf(SaveCopyScript.ALLOWED_ORIGIN),
            )
            true
        } catch (e: IllegalArgumentException) {
            // 来源规则不合法、或名字已注册：装不上就退化为「不支持保存」，不提示。
            false
        }
    }

    /** 页面换了文档（新主框架开始加载）：旧文档的 Blob 已经没了，这条传输就此收场，不提示。 */
    fun onDocumentChanged() = dispatch(SaveEvent.Reset)

    /** WebView 这个实例挂了（渲染进程崩溃等）：收场，不提示。 */
    fun reset() = dispatch(SaveEvent.Reset)

    /** 内核清了：收场、停掉后台线程。之后这个桥不再收任何消息。 */
    fun release() {
        dispatch(SaveEvent.Reset)
        released = true
        main.removeCallbacks(inactivityTimeout)
        io.shutdown()
    }

    // ================================================================================ 界面层回调

    /** 系统保存框收场：[uri] 是用户选的位置，null 是取消了。 */
    fun onTargetPicked(uri: Uri?) {
        // 等待中的请求已经没了（传输中途被打断、页面换了文档）：刚建出来的空文件删掉，别的什么都不做。
        if (transfer !is SaveTransfer.AwaitingTarget) {
            if (uri != null) io.execute { deleteQuietly(uri) }
            return
        }
        if (uri == null) dispatch(SaveEvent.TargetCancelled) else {
            targetUri = uri
            streamGen += 1
            dispatch(SaveEvent.TargetPicked)
        }
    }

    // ================================================================================ 页面 → 原生

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: androidx.webkit.JavaScriptReplyProxy,
    ) {
        // 只收主框架、只收允许来源的。allowedOriginRules 已经限过一次，这里是第二道。
        if (!isMainFrame) return
        if (sourceOrigin.toString() != SaveCopyScript.ALLOWED_ORIGIN) return
        // 回调约定在主线程（WebViewCompat 的这条 API）；万一不是，也归拢到主线程再记账。
        main.post {
            if (released) return@post
            reply = replyProxy
            when (message.type) {
                WebMessageCompat.TYPE_ARRAY_BUFFER -> onChunkBytes(message.arrayBuffer)
                WebMessageCompat.TYPE_STRING -> parseControl(message.data)?.let(::onControl)
            }
        }
    }

    private fun onChunkBytes(bytes: ByteArray) {
        val before = transfer
        val step = SaveTransferMachine.on(before, SaveEvent.Chunk(bytes.size))
        val accepted = step.transfer is SaveTransfer.Receiving && step.action == null
        applyStep(step)
        if (accepted) {
            // 每收到一块，空闲计时从头算：大文件传得慢也不至于被误杀。
            armInactivityTimer()
            enqueueWrite(bytes)
            replyJson("""{"zcodeSave":"ack"}""")
        }
    }

    private fun onControl(control: Control) {
        when (control) {
            is Control.Begin -> {
                if (control.request.size < 0) {
                    // 畸形的 begin（没有大小）：回个错让页面收场，不提示——不是用户的错。
                    replyJson("""{"zcodeSave":"error"}""")
                    return
                }
                if (!SaveProtocol.isWithinLimit(control.request.size)) {
                    // 页面自己就有 50MB 上限，这条只是页面改版后的防线；超了不接手。
                    toast(R.string.save_too_large)
                    replyJson("""{"zcodeSave":"error"}""")
                    return
                }
                val before = transfer
                val step = SaveTransferMachine.on(before, SaveEvent.Request(control.request))
                applyStep(step)
                if (step.action == null && step.transfer == before) {
                    // 已经有一条在走又来了第二条：回个错，别让页面空等。
                    replyJson("""{"zcodeSave":"error"}""")
                }
            }
            Control.End -> dispatch(SaveEvent.End)
            Control.Abort -> dispatch(SaveEvent.Abort)
            Control.Unsupported -> toast(R.string.save_unsupported)
        }
    }

    // ================================================================================ 状态机落地

    private fun dispatch(event: SaveEvent) {
        if (released) return
        applyStep(SaveTransferMachine.on(transfer, event))
    }

    private fun applyStep(step: SaveStep) {
        transfer = step.transfer
        when (val action = step.action) {
            null -> {}
            is SaveAction.AskTarget -> askTarget(action.request)
            SaveAction.Ready -> {
                armInactivityTimer()
                replyJson("""{"zcodeSave":"ready"}""")
            }
            SaveAction.Cancelled -> {
                disarmInactivityTimer()
                replyJson("""{"zcodeSave":"cancelled"}""")
            }
            SaveAction.Error -> {
                disarmInactivityTimer()
                discardTransfer()
                toast(R.string.save_failed)
                replyJson("""{"zcodeSave":"error"}""")
            }
            SaveAction.Done -> {
                disarmInactivityTimer()
                finishTransfer()
            }
            SaveAction.QuietReset -> {
                disarmInactivityTimer()
                discardTransfer()
            }
        }
    }

    private fun askTarget(request: SaveRequest) {
        val launched = try {
            picker.launchSavePicker(request)
        } catch (e: RuntimeException) {
            // 选择器起不来：这一步不支持。
            false
        }
        if (!launched) {
            toast(R.string.save_unsupported)
            dispatch(SaveEvent.TargetCancelled)
        }
    }

    // ================================================================================ 写盘

    /** 页面来了一块（已经过状态机记账）：后台队列里排队写。 */
    private fun enqueueWrite(bytes: ByteArray) {
        io.execute {
            if (out == null) {
                val uri = targetUri
                if (uri == null) return@execute
                try {
                    out = appContext.contentResolver.openOutputStream(uri, "w")
                    failedGen = -1
                } catch (e: IOException) {
                    failedGen = streamGen
                } catch (e: SecurityException) {
                    failedGen = streamGen
                }
                if (out == null) return@execute
            }
            val stream = out ?: return@execute
            try {
                stream.write(bytes)
            } catch (e: IOException) {
                failedGen = streamGen
            }
        }
    }

    /** 状态机说写完了：冲流、关流，然后按结果回话页面。 */
    private fun finishTransfer() {
        val uri = targetUri
        val gen = streamGen
        if (uri == null) {
            // 没选过位置就收到了 end：协议对不上，按失败收场。
            toast(R.string.save_failed)
            replyJson("""{"zcodeSave":"error"}""")
            return
        }
        io.execute {
            var ok = failedGen != gen
            try {
                out?.flush()
            } catch (e: IOException) {
                ok = false
            }
            try {
                out?.close()
            } catch (e: IOException) {
                ok = false
            }
            out = null
            main.post {
                if (released) return@post
                if (ok) {
                    toast(R.string.save_saved)
                    replyJson("""{"zcodeSave":"done"}""")
                } else {
                    deleteQuietly(uri)
                    toast(R.string.save_failed)
                    replyJson("""{"zcodeSave":"error"}""")
                }
            }
        }
    }

    /** 中途放弃：关流、删半截文件。位置还没选过就没什么可收的。 */
    private fun discardTransfer() {
        val uri = targetUri
        targetUri = null
        if (uri == null) return
        io.execute {
            try {
                out?.close()
            } catch (e: IOException) {
                // 关不掉就算了，接下来删文件。
            }
            out = null
            deleteQuietly(uri)
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        } catch (e: Exception) {
            // 删不掉就留着半截文件，别为它打扰用户。
        }
    }

    // ================================================================================ 计时与杂项

    private fun armInactivityTimer() {
        main.removeCallbacks(inactivityTimeout)
        main.postDelayed(inactivityTimeout, INACTIVITY_TIMEOUT_MS)
    }

    private fun disarmInactivityTimer() {
        main.removeCallbacks(inactivityTimeout)
    }

    private fun replyJson(json: String) {
        val proxy = reply ?: return
        try {
            proxy.postMessage(json)
        } catch (e: RuntimeException) {
            // WebView 已经不在了：没处回话，页面那头自有超时兜底。
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(appContext, res, Toast.LENGTH_SHORT).show()
    }

    private fun parseControl(raw: String?): Control? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(raw)
            when (obj.optString("zcodeSave")) {
                "begin" -> Control.Begin(
                    request = SaveRequest(
                        name = SaveProtocol.suggestedFileName(obj.optString("name")),
                        size = obj.optLong("size", -1L),
                        mime = obj.optString("mime").takeIf { it.isNotEmpty() },
                    ),
                )
                "end" -> Control.End
                "abort" -> Control.Abort
                "unsupported" -> Control.Unsupported
                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }

    private companion object {
        /** 收不到数据的等待上限。页面取数超时 30 秒（notes 第 8 节），这里取同样的数。 */
        const val INACTIVITY_TIMEOUT_MS = 30_000L
    }
}
