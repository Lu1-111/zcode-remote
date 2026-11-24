package com.lululu.zcoderemote.devices

import java.util.Locale

/**
 * 电脑链接的校验与归并（方案第 2、10 条；约定见 docs/ARCHITECTURE.md 第 10 节）。
 *
 * 纯逻辑，不引用任何 `android.*`，在电脑上就能跑单元测试。
 *
 * 链接原样保存、原样加载：这里只做判断，不拆开重建，不补全，不改写 `app_version`；
 * 其他没见过的参数原样保留。判断用的解析自己实现，不依赖平台类。
 */

/** 一台已保存的电脑。链接是密码级数据：不写日志，不在界面显示（界面只显示 [name]）。 */
data class Computer(
    /** 云端设备 id，电脑的唯一键。同一台电脑换了账号或端点会换成新的 sid，旧的随之失效。 */
    val sid: String,
    /** 电脑端给出的完整链接，原样文本。 */
    val link: String,
    /** 显示名：取链接里的 `name` 参数（已解码），可以由用户改名。 */
    val name: String,
    /** 第一次添加的时间（epoch 毫秒）。重复添加同一台电脑时保留。 */
    val addedAt: Long,
    /** 最后使用时间（epoch 毫秒）。0 表示还没有用过。 */
    val lastUsedAt: Long,
)

/** 校验一条文本之后得到的结论。 */
sealed interface LinkCheck {

    /** 一台具体的电脑（链接带 `sid`）。[name] 是解码后的 `name` 参数，链接里没带时为 null。 */
    data class Computer(val sid: String, val name: String?, val hasAppVersion: Boolean) : LinkCheck

    /** 没带 `sid`：不是一台具体的电脑，是「免扫码入口」。可以打开，不保存成电脑。 */
    data object Entry : LinkCheck

    /** 带 `hash` 或 `t` 参数：旧版链接。4.0.0 页面会直接判参数无效。 */
    data object Legacy : LinkCheck

    /** 不是 https、主机不是 `zcode.z.ai`、端口不是 443，或路径不是 `/remote/v4` 开头。 */
    data object Invalid : LinkCheck
}

object RemoteLink {

    const val HOST = "zcode.z.ai"
    const val PATH_PREFIX = "/remote/v4"

    /** 链接里没带 `name` 时的显示名。 */
    const val UNNAMED = "未命名电脑"

    // scheme://授权部分路径?查询#片段。查询串保持原样；片段（#后面的部分）只丢弃，不参与判断。
    private val URL_PARTS =
        Regex("^(?:([A-Za-z][A-Za-z0-9+.-]*)://)?([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#.*)?$")

    /**
     * 检查一条原样文本是不是 ZCode 的电脑链接。
     *
     * 判断的次序有讲究：先看地址本身（https、主机、路径），再看是不是旧版链接（带 `hash`/`t`），
     * 最后才看有没有 `sid`——带 `hash`/`t` 的链接哪怕有 `sid` 也是旧版（页面自己就是这么判的）。
     */
    fun check(raw: String?): LinkCheck {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return LinkCheck.Invalid
        val parts = URL_PARTS.matchEntire(text) ?: return LinkCheck.Invalid

        if (parts.groupValues[1].lowercase(Locale.ROOT) != "https") return LinkCheck.Invalid

        val authority = parts.groupValues[2]
        // 带用户信息的地址（https://user@zcode.z.ai/…）不是电脑端会生成的样子，不收。
        if (authority.isEmpty() || authority.contains('@')) return LinkCheck.Invalid
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && port != "443") return LinkCheck.Invalid
        if (authority.substringBefore(':').lowercase(Locale.ROOT) != HOST) return LinkCheck.Invalid

        // 路径以 /remote/v4 开头：要么正好是它，要么后面紧跟 /，不把 /remote/v42 这类算进来。
        val path = parts.groupValues[3].ifEmpty { "/" }
        if (path != PATH_PREFIX && !path.startsWith("$PATH_PREFIX/")) return LinkCheck.Invalid

        val params = parseQuery(parts.groupValues[4])
        // 参数名区分大小写，和页面用的 URLSearchParams 一致。
        if (params.containsKey("hash") || params.containsKey("t")) return LinkCheck.Legacy

        val sid = params["sid"]?.trim().orEmpty()
        if (sid.isEmpty()) return LinkCheck.Entry

        return LinkCheck.Computer(
            sid = sid,
            name = params["name"]?.trim()?.takeIf { it.isNotEmpty() },
            hasAppVersion = params.containsKey("app_version"),
        )
    }

    /** 链接里 `name` 参数对应的显示名；没有或为空用「未命名电脑」。 */
    fun displayName(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: UNNAMED

    /**
     * 归并（第 10 节）：把一条新链接并进已保存的列表。
     *
     * - 电脑的唯一键是 `sid`：相同的 sid 就是同一台电脑，**不产生重复项**；
     *   新链接覆盖旧链接（保存的还是新链接的原样文本），添加时间、最后使用时间保留。
     * - 电脑名跟着新链接更新；新链接没带 `name` 时保留原名（不把用户改过的名字冲掉）。
     * - `sid` 不同就是不同的电脑，即使 `mid` 相同也分开保存。
     * - 新电脑排在列表末尾。
     */
    fun merge(existing: List<Computer>, link: String, checked: LinkCheck.Computer, nowMs: Long): List<Computer> {
        val index = existing.indexOfFirst { it.sid == checked.sid }
        if (index < 0) {
            return existing + Computer(
                sid = checked.sid,
                link = link,
                name = displayName(checked.name),
                addedAt = nowMs,
                lastUsedAt = 0L,
            )
        }
        return existing.mapIndexed { i, computer ->
            if (i != index) {
                computer
            } else {
                computer.copy(
                    link = link,
                    name = if (checked.name != null) displayName(checked.name) else computer.name,
                )
            }
        }
    }

    // 分享文本里截取链接时，遇到这些字符就停：空白、引号、尖括号、中英文的括号引号和标点。
    private val LINK_IN_TEXT =
        Regex("""https://zcode\.z\.ai/remote/v4[^\s"'<>()（）「」『』，。、；：！？,;\\]*""")

    /**
     * 从一段分享文本里找出电脑链接：整段就是一条链接时原样返回（连同可能的片段），
     * 否则取第一处 `https://zcode.z.ai/remote/v4…`。找不到返回 null。
     * 返回的是文本里的原样子串，交回 [check] 判断。
     */
    fun findInText(text: String?): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (looksLikeOurLink(trimmed)) return trimmed
        return LINK_IN_TEXT.find(trimmed)?.value?.takeIf { it.isNotEmpty() }
    }

    /** 这段文本整体是不是一条指向 zcode.z.ai/remote/v4 的 https 地址（宽松判断，细节交给 [check]）。 */
    private fun looksLikeOurLink(text: String): Boolean {
        val parts = URL_PARTS.matchEntire(text) ?: return false
        if (parts.groupValues[1].lowercase(Locale.ROOT) != "https") return false
        val host = parts.groupValues[2].substringBefore('@').substringBefore(':').lowercase(Locale.ROOT)
        if (host != HOST) return false
        val path = parts.groupValues[3].ifEmpty { "/" }
        return path == PATH_PREFIX || path.startsWith("$PATH_PREFIX/")
    }

    /**
     * 解析查询串。键、值都按 `%XX` 解码，`+` 解码成空格（同页面用的 URLSearchParams）；
     * 同一个键出现多次时取最后一个（同 `URLSearchParams.get`）。
     * 解码只用于判断和显示，不用于重建链接。
     */
    internal fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (piece in query.split('&')) {
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            val key = decodePercent(if (eq >= 0) piece.substring(0, eq) else piece)
            val value = decodePercent(if (eq >= 0) piece.substring(eq + 1) else "")
            out[key] = value
        }
        return out
    }

    /**
     * 查询串成分的解码，做法和页面用的 URLSearchParams 一致：`%XX` 还原成字节，`+` 还原成空格，
     * 然后整串按 UTF-8 解码（解码不了的字节换成 U+FFFD）。非法的 `%XX`（不是两位十六进制）当字面量。
     * 所以 `%E7%AC%94…` 这种百分号编码的中文能还原成中文，不会变成一堆 Latin-1 字符。
     */
    internal fun decodePercent(raw: String): String {
        if (!raw.contains('%') && !raw.contains('+')) return raw
        val out = ArrayList<Byte>(raw.length + 8)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '+' -> {
                    out.add(' '.code.toByte())
                    i++
                }

                c == '%' && i + 3 <= raw.length -> {
                    val hi = hexValue(raw[i + 1])
                    val lo = hexValue(raw[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        out.add(((hi shl 4) or lo).toByte())
                        i += 3
                    } else {
                        out.add('%'.code.toByte())
                        i++
                    }
                }

                else -> {
                    for (b in c.toString().toByteArray(Charsets.UTF_8)) out.add(b)
                    i++
                }
            }
        }
        val bytes = ByteArray(out.size)
        for (j in out.indices) bytes[j] = out[j]
        return String(bytes, Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
