package com.lululu.zcoderemote.save

/**
 * 保存副本的纯逻辑（方案第 16 条；docs/ARCHITECTURE.md 9.3）：大小上限、分块计划、文件名清洗。
 *
 * 不引用任何 android.*，单元测试在电脑上直接跑（SaveProtocolTest）。
 */
object SaveProtocol {

    /**
     * 单文件上限：50MB。这是**页面上限的沿用**（方案第 16 条「沿用页面 50MB 上限」）：
     * 页面的平台层在取数前就按 `50*1024*1024` 拒掉了更大的文件，这里只是同样的防线，
     * 防的是页面改版或者传输时虚报大小。
     */
    const val MAX_FILE_BYTES: Long = 50L * 1024 * 1024

    /**
     * 分块大小：256KB。原生↔页面每来回应答（发一块、等一次 ack），同时在路上的数据最多一块，
     * 50MB 也就切 200 块，节奏上没有压力。
     */
    const val CHUNK_BYTES: Int = 256 * 1024

    /** 文件名清洗不出来时的兜底名。 */
    const val DEFAULT_FILE_NAME: String = "zcode-file"

    /** 大小在上限之内（0 到 50MB，含两端）才接手。 */
    fun isWithinLimit(size: Long): Boolean = size in 0..MAX_FILE_BYTES

    /**
     * 把页面报上来的文件名清洗成能交给系统保存框的形态。`a.download` 的值是页面给的，
     * 不能照单全收：
     * - 带路径分隔符的只取最后一段（和浏览器处理 Content-Disposition 一个思路）；
     * - 控制字符扔掉；
     * - 剩下的空的、或者只剩「.」「..」，给兜底名；
     * - 太长的截短。不改扩展名——保存框里用户自己能看到和改。
     */
    fun suggestedFileName(raw: String?): String {
        var text = (raw ?: "").trim()
        // 只取最后一段非空路径段。
        text = text.split('/', '\\').filterNot { it.isBlank() }.lastOrNull() ?: ""
        // 控制字符（含换行）扔掉；其余字符（中文、空格、点、emoji）都留给系统保存框处理。
        text = text.filter { !it.isISOControl() }
        if (text.isEmpty() || text == "." || text == "..") return DEFAULT_FILE_NAME
        if (text.length > MAX_NAME_CHARS) text = text.take(MAX_NAME_CHARS)
        return text
    }

    /** 文件名最多保留多少个字符。 */
    const val MAX_NAME_CHARS: Int = 64
}

/**
 * 一次保存的分块计划：把 totalBytes 按 chunkBytes 切成若干块，每块是一个字节区间
 * `[offsetInclusive, offsetExclusive)`。页面那头用 `blob.slice(offset, end)` 切同样的块
 * （SaveCopyScript 里插入的 CHUNK 常量就是 [SaveProtocol.CHUNK_BYTES]），两边对不上是 bug。
 */
class SaveChunkPlan private constructor(
    /** 文件总字节数。 */
    val totalBytes: Long,
    /** 每块的最大字节数。 */
    val chunkBytes: Int,
    /** 块数。totalBytes 为 0 时是 0 块（空文件不用发数据块）。 */
    val chunkCount: Int,
) {
    /** 第 index 块（从 0 数）的字节区间。越界抛 IndexOutOfBoundsException。 */
    fun rangeOf(index: Int): LongRange {
        if (index < 0 || index >= chunkCount) {
            throw IndexOutOfBoundsException("chunk index $index out of 0..${chunkCount - 1}")
        }
        val offset = index.toLong() * chunkBytes
        val end = minOf(offset + chunkBytes, totalBytes)
        return offset until end
    }

    /** 依次给出每一块。空文件（0 块）什么都不给。 */
    inline fun forEach(block: (index: Int, range: LongRange) -> Unit) {
        for (i in 0 until chunkCount) block(i, rangeOf(i))
    }

    companion object {
        fun of(totalBytes: Long, chunkBytes: Int = SaveProtocol.CHUNK_BYTES): SaveChunkPlan {
            require(totalBytes >= 0) { "totalBytes must be >= 0, got $totalBytes" }
            require(chunkBytes > 0) { "chunkBytes must be > 0, got $chunkBytes" }
            val count = if (totalBytes == 0L) 0 else ((totalBytes + chunkBytes - 1) / chunkBytes).toInt()
            return SaveChunkPlan(totalBytes, chunkBytes, count)
        }
    }
}
