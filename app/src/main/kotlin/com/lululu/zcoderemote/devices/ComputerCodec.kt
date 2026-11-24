package com.lululu.zcoderemote.devices

/**
 * 电脑列表在 DataStore 里的打包与解包。
 *
 * 纯逻辑，不引用任何 `android.*`（也不引用 DataStore），在电脑上就能跑单元测试。
 *
 * 格式：一条电脑是一条记录，记录之间用 U+001E（记录分隔符），字段之间用 U+001F（单元分隔符），
 * 字段依次是 sid、link、name、addedAt、lastUsedAt。这两个控制字符不会出现在正常文本里，
 * 但字段值仍然先转义（`%` 和控制字符），链接里的 `%` 不会被误解。
 * 解析不了的记录（字段数不对、数字不是数字）整条跳过：宁可少一条，不让一条坏数据拖垮整个列表。
 */
internal object ComputerCodec {

    private const val RECORD_SEP = '\u001E'
    private const val FIELD_SEP = '\u001F'
    private const val FIELD_COUNT = 5

    fun pack(computers: List<Computer>): String =
        computers.joinToString(RECORD_SEP.toString()) { computer ->
            listOf(
                computer.sid,
                computer.link,
                computer.name,
                computer.addedAt.toString(),
                computer.lastUsedAt.toString(),
            ).joinToString(FIELD_SEP.toString()) { escape(it) }
        }

    fun unpack(raw: String?): List<Computer> {
        val text = raw ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return text.split(RECORD_SEP).mapNotNull { record ->
            val fields = record.split(FIELD_SEP)
            if (fields.size != FIELD_COUNT) return@mapNotNull null
            val sid = unescape(fields[0]).trim()
            if (sid.isEmpty()) return@mapNotNull null
            val addedAt = unescape(fields[3]).toLongOrNull() ?: return@mapNotNull null
            val lastUsedAt = unescape(fields[4]).toLongOrNull() ?: return@mapNotNull null
            Computer(
                sid = sid,
                link = unescape(fields[1]),
                name = unescape(fields[2]),
                addedAt = addedAt,
                lastUsedAt = lastUsedAt,
            )
        }
    }

    private fun escape(value: String): String {
        if (!value.any { it == '%' || it == RECORD_SEP || it == FIELD_SEP || it == '\n' || it == '\r' }) {
            return value
        }
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '%' -> out.append("%25")
                RECORD_SEP -> out.append("%1E")
                FIELD_SEP -> out.append("%1F")
                '\n' -> out.append("%0A")
                '\r' -> out.append("%0D")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 3 <= value.length) {
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out.append(((hi shl 4) or lo).toChar())
                    i += 3
                } else {
                    out.append(c)
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
