package com.lululu.zcoderemote.save

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存副本的纯逻辑：大小上限（50MB）和分块计划（256KB 一块）。
 * 上限是沿用页面的 50MB（方案第 16 条），分块的两边（页面 blob.slice / 原生记账）必须切得一样。
 */
class SaveProtocolTest {

    // ---------------------------------------------------------------- 大小上限

    @Test
    fun limitIs50MB() {
        assertEquals(50L * 1024 * 1024, SaveProtocol.MAX_FILE_BYTES)
        assertEquals(52428800L, SaveProtocol.MAX_FILE_BYTES)
    }

    @Test
    fun sizesUpToTheLimitAreAccepted() {
        assertTrue(SaveProtocol.isWithinLimit(0))
        assertTrue(SaveProtocol.isWithinLimit(1))
        assertTrue(SaveProtocol.isWithinLimit(SaveProtocol.MAX_FILE_BYTES))
    }

    @Test
    fun sizesBeyondTheLimitAreRejected() {
        assertFalse(SaveProtocol.isWithinLimit(SaveProtocol.MAX_FILE_BYTES + 1))
        assertFalse(SaveProtocol.isWithinLimit(Long.MAX_VALUE))
    }

    @Test
    fun negativeSizesAreRejected() {
        assertFalse(SaveProtocol.isWithinLimit(-1))
        assertFalse(SaveProtocol.isWithinLimit(Long.MIN_VALUE))
    }

    // ---------------------------------------------------------------- 分块计划

    @Test
    fun emptyFileHasNoChunks() {
        val plan = SaveChunkPlan.of(0)
        assertEquals(0, plan.chunkCount)
        var visited = 0
        plan.forEach { _, _ -> visited++ }
        assertEquals(0, visited)
    }

    @Test
    fun oneByteIsOneChunk() {
        val plan = SaveChunkPlan.of(1)
        assertEquals(1, plan.chunkCount)
        assertEquals(0L until 1L, plan.rangeOf(0))
    }

    @Test
    fun lessThanOneChunkIsOneChunk() {
        val plan = SaveChunkPlan.of(SaveProtocol.CHUNK_BYTES - 1L)
        assertEquals(1, plan.chunkCount)
        assertEquals(0L until SaveProtocol.CHUNK_BYTES - 1L, plan.rangeOf(0))
    }

    @Test
    fun exactlyOneChunkIsOneChunk() {
        val plan = SaveChunkPlan.of(SaveProtocol.CHUNK_BYTES.toLong())
        assertEquals(1, plan.chunkCount)
        assertEquals(0L until SaveProtocol.CHUNK_BYTES.toLong(), plan.rangeOf(0))
    }

    @Test
    fun oneByteOverOneChunkIsTwoChunks() {
        val plan = SaveChunkPlan.of(SaveProtocol.CHUNK_BYTES + 1L)
        assertEquals(2, plan.chunkCount)
        assertEquals(0L until SaveProtocol.CHUNK_BYTES.toLong(), plan.rangeOf(0))
        assertEquals(SaveProtocol.CHUNK_BYTES.toLong() until SaveProtocol.CHUNK_BYTES + 1L, plan.rangeOf(1))
    }

    @Test
    fun theLimitFitsInExactly200Chunks() {
        val plan = SaveChunkPlan.of(SaveProtocol.MAX_FILE_BYTES)
        assertEquals(200, plan.chunkCount)
        // 全部区间连起来正好盖住整个文件，不重不漏。
        var expectedStart = 0L
        plan.forEach { index, range ->
            assertEquals(index.toLong() * SaveProtocol.CHUNK_BYTES, range.first)
            assertEquals(expectedStart, range.first)
            expectedStart = range.last + 1
            assertTrue(range.last - range.first + 1 <= SaveProtocol.CHUNK_BYTES)
        }
        assertEquals(SaveProtocol.MAX_FILE_BYTES, expectedStart)
    }

    @Test
    fun aNonRoundTotalLeavesAPartialLastChunk() {
        val plan = SaveChunkPlan.of(1000, chunkBytes = 300)
        assertEquals(4, plan.chunkCount)
        assertEquals(0L until 300L, plan.rangeOf(0))
        assertEquals(300L until 600L, plan.rangeOf(1))
        assertEquals(600L until 900L, plan.rangeOf(2))
        assertEquals(900L until 1000L, plan.rangeOf(3))
    }

    @Test
    fun rangeBeyondTheCountThrows() {
        val plan = SaveChunkPlan.of(1000, chunkBytes = 300)
        try {
            plan.rangeOf(4)
            throw AssertionError("expected IndexOutOfBoundsException")
        } catch (expected: IndexOutOfBoundsException) {
            // 该抛的抛了。
        }
    }

    @Test
    fun aCustomChunkSizeIsRespected() {
        val plan = SaveChunkPlan.of(10, chunkBytes = 4)
        assertEquals(3, plan.chunkCount)
        assertEquals(8L until 10L, plan.rangeOf(2))
    }

    // ---------------------------------------------------------------- 文件名清洗

    @Test
    fun plainNamesPassThrough() {
        assertEquals("report.pdf", SaveProtocol.suggestedFileName("report.pdf"))
        assertEquals("报表 2026.xlsx", SaveProtocol.suggestedFileName("报表 2026.xlsx"))
    }

    @Test
    fun nullOrBlankFallsBackToDefault() {
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName(null))
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName(""))
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName("   "))
    }

    @Test
    fun onlyTheLastPathSegmentIsKept() {
        // 页面给的 download 属性不照单全收：文件名里不该带目录。
        assertEquals("passwd", SaveProtocol.suggestedFileName("../../etc/passwd"))
        assertEquals("b.txt", SaveProtocol.suggestedFileName("a\\b.txt"))
        assertEquals("c.png", SaveProtocol.suggestedFileName("/path/to/c.png"))
    }

    @Test
    fun dotNamesFallBackToDefault() {
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName("."))
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName(".."))
        assertEquals(SaveProtocol.DEFAULT_FILE_NAME, SaveProtocol.suggestedFileName("/\\/"))
    }

    @Test
    fun controlCharactersAreStripped() {
        assertEquals("ab.txt", SaveProtocol.suggestedFileName("a\nb\t\r.txt"))
    }

    @Test
    fun longNamesAreTruncated() {
        val long = "x".repeat(500)
        assertEquals(SaveProtocol.MAX_NAME_CHARS, SaveProtocol.suggestedFileName(long).length)
        // 截断之后仍是同一个前缀。
        assertTrue(SaveProtocol.suggestedFileName(long).startsWith("x"))
    }

    @Test
    fun leadingDotsInARealNameAreKept() {
        assertEquals(".hidden", SaveProtocol.suggestedFileName(".hidden"))
    }
}
