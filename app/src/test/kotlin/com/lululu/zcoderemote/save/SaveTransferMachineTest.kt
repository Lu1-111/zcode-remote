package com.lululu.zcoderemote.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存副本的状态机：请求、选位置、收块、收尾，以及各种打回 Idle 的路径。
 * 状态机只管记账和判对错；写盘、回话、提示都由桥（SaveCopyBridge）做。
 */
class SaveTransferMachineTest {

    private val request = SaveRequest(name = "report.pdf", size = 1000, mime = "application/pdf")

    private fun stepOf(state: SaveTransfer, event: SaveEvent): SaveStep =
        SaveTransferMachine.on(state, event)

    // ---------------------------------------------------------------- 请求

    @Test
    fun idleRequest_asksForTarget() {
        val step = stepOf(SaveTransfer.Idle, SaveEvent.Request(request))
        assertEquals(SaveTransfer.AwaitingTarget(request), step.transfer)
        assertEquals(SaveAction.AskTarget(request), step.action)
    }

    @Test
    fun busyStatesIgnoreNewRequests() {
        for (state in listOf(
            SaveTransfer.AwaitingTarget(request),
            SaveTransfer.Receiving(request, 100),
        )) {
            val step = stepOf(state, SaveEvent.Request(SaveRequest("other.bin", 10, null)))
            assertEquals(state, step.transfer)
            assertNull(step.action)
        }
    }

    // ---------------------------------------------------------------- 选位置

    @Test
    fun targetPicked_startsReceivingAndTellsThePage() {
        val step = stepOf(SaveTransfer.AwaitingTarget(request), SaveEvent.TargetPicked)
        assertEquals(SaveTransfer.Receiving(request, 0), step.transfer)
        assertEquals(SaveAction.Ready, step.action)
    }

    @Test
    fun targetCancelled_returnsToIdleAndTellsThePage() {
        val step = stepOf(SaveTransfer.AwaitingTarget(request), SaveEvent.TargetCancelled)
        assertEquals(SaveTransfer.Idle, step.transfer)
        assertEquals(SaveAction.Cancelled, step.action)
    }

    @Test
    fun targetCancelledWhileReceivingIsAProtocolError() {
        val step = stepOf(SaveTransfer.Receiving(request, 100), SaveEvent.TargetCancelled)
        assertEquals(SaveTransfer.Idle, step.transfer)
        assertEquals(SaveAction.Error, step.action)
    }

    @Test
    fun targetEventsInIdleChangeNothing() {
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.TargetPicked).action)
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.TargetCancelled).action)
    }

    // ---------------------------------------------------------------- 收块

    @Test
    fun chunksWithinTheDeclaredSizeAreAccepted() {
        val state = SaveTransfer.Receiving(request, 0)
        val step = stepOf(state, SaveEvent.Chunk(400))
        assertEquals(SaveTransfer.Receiving(request, 400), step.transfer)
        assertNull(step.action)
    }

    @Test
    fun theLastChunkReachesExactlyTheDeclaredSize() {
        val state = SaveTransfer.Receiving(request, 600)
        val step = stepOf(state, SaveEvent.Chunk(400))
        assertEquals(SaveTransfer.Receiving(request, 1000), step.transfer)
        assertNull(step.action)
    }

    @Test
    fun aChunkThatOverrunsTheDeclaredSizeIsAProtocolError() {
        val state = SaveTransfer.Receiving(request, 600)
        val step = stepOf(state, SaveEvent.Chunk(401))
        assertEquals(SaveTransfer.Idle, step.transfer)
        assertEquals(SaveAction.Error, step.action)
    }

    @Test
    fun anEmptyChunkIsIgnoredWithoutStateChange() {
        val state = SaveTransfer.Receiving(request, 100)
        val step = stepOf(state, SaveEvent.Chunk(0))
        assertEquals(SaveTransfer.Receiving(request, 100), step.transfer)
        assertNull(step.action)
    }

    @Test
    fun chunksOutsideReceivingChangeNothing() {
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.Chunk(1)).action)
        assertNull(stepOf(SaveTransfer.AwaitingTarget(request), SaveEvent.Chunk(1)).action)
    }

    // ---------------------------------------------------------------- 收尾

    @Test
    fun endWithAllBytesReceivedCompletes() {
        val state = SaveTransfer.Receiving(request, 1000)
        val step = stepOf(state, SaveEvent.End)
        assertEquals(SaveTransfer.Idle, step.transfer)
        assertEquals(SaveAction.Done, step.action)
    }

    @Test
    fun endWithMissingBytesIsAProtocolError() {
        val state = SaveTransfer.Receiving(request, 999)
        val step = stepOf(state, SaveEvent.End)
        assertEquals(SaveTransfer.Idle, step.transfer)
        assertEquals(SaveAction.Error, step.action)
    }

    @Test
    fun endOutsideReceivingChangesNothing() {
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.End).action)
        assertNull(stepOf(SaveTransfer.AwaitingTarget(request), SaveEvent.End).action)
    }

    // ---------------------------------------------------------------- 放弃与打断

    @Test
    fun pageAbort_quietsBackToIdleFromAnywhereBusy() {
        for (state in listOf(
            SaveTransfer.AwaitingTarget(request),
            SaveTransfer.Receiving(request, 100),
        )) {
            val step = stepOf(state, SaveEvent.Abort)
            assertEquals(SaveTransfer.Idle, step.transfer)
            // 页面自己放弃的：不用回话。
            assertEquals(SaveAction.QuietReset, step.action)
        }
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.Abort).action)
    }

    @Test
    fun appReset_quietsBackToIdleFromAnywhereBusy() {
        for (state in listOf(
            SaveTransfer.AwaitingTarget(request),
            SaveTransfer.Receiving(request, 100),
        )) {
            val step = stepOf(state, SaveEvent.Reset)
            assertEquals(SaveTransfer.Idle, step.transfer)
            assertEquals(SaveAction.QuietReset, step.action)
        }
        assertNull(stepOf(SaveTransfer.Idle, SaveEvent.Reset).action)
    }

    // ---------------------------------------------------------------- 完整走一遍

    @Test
    fun aFullTransferVisitsEveryStateInOrder() {
        var state: SaveTransfer = SaveTransfer.Idle
        val actions = mutableListOf<SaveAction>()

        fun fire(event: SaveEvent) {
            val step = SaveTransferMachine.on(state, event)
            state = step.transfer
            step.action?.let(actions::add)
        }

        fire(SaveEvent.Request(request))
        fire(SaveEvent.TargetPicked)
        fire(SaveEvent.Chunk(600))
        fire(SaveEvent.Chunk(400))
        fire(SaveEvent.End)

        assertEquals(SaveTransfer.Idle, state)
        assertEquals(
            listOf(SaveAction.AskTarget(request), SaveAction.Ready, SaveAction.Done),
            actions,
        )
    }

    @Test
    fun aTransferThatCannotBeRescuedEndsInIdle() {
        // 任何一步出错之后，下一条请求都能正常走：不会卡在中间态。
        var state: SaveTransfer = SaveTransfer.Idle
        fun fire(event: SaveEvent): SaveStep {
            val step = SaveTransferMachine.on(state, event)
            state = step.transfer
            return step
        }

        fire(SaveEvent.Request(request))
        fire(SaveEvent.TargetPicked)
        val error = fire(SaveEvent.Chunk(2000))
        assertEquals(SaveAction.Error, error.action)
        assertTrue(state == SaveTransfer.Idle)

        val next = fire(SaveEvent.Request(request))
        assertEquals(SaveTransfer.AwaitingTarget(request), next.transfer)
    }
}
