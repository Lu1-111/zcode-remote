package com.lululu.zcoderemote.web

/**
 * 入口选择（方案第 2、3、4 条）：打开哪个地址，主文档 404 时怎么办。
 *
 * 纯逻辑，不引用任何 `android.*`，在电脑上就能跑单元测试。
 *
 * 链接原样加载：这里只做判断，不拆开重建，不补全，不改写。
 */

/** 要加载的入口。 */
sealed interface EntryTarget {
    /** 保存下来的电脑链接，原样。 */
    data class Link(override val url: String) : EntryTarget

    /** 兜底入口：`https://zcode.z.ai/remote/v4?app_version=<兜底版本>`，不带 sid。 */
    data class Fallback(override val url: String) : EntryTarget

    val url: String
}

/** 主文档返回 HTTP 错误之后做什么。 */
enum class EntryAction {
    /** 什么都不做，照浏览器的方式处理。 */
    NONE,

    /** 电脑链接的主文档 404 了：改去加载兜底入口。 */
    SWITCH_TO_FALLBACK,

    /** 兜底入口自己也 404 了：显示原生错误页。兜底是最后一道，不再来回跳。 */
    SHOW_ERROR_PAGE,
}

object EntrySelector {
    /** 兜底入口的固定前缀。后面只接兜底版本，不带 sid。 */
    const val FALLBACK_BASE_URL: String = "https://zcode.z.ai/remote/v4?app_version="

    /**
     * 兜底版本的默认值（常量）。设置里改过的话以 [RemoteWebViewModel.fallbackVersion] 为准。
     * ZCode 桌面端升级后要跟着换：先按技术笔记第 11 节确认新版本目录在线上存在，再改这里。
     */
    const val DEFAULT_FALLBACK_VERSION: String = "4.0.0"

    private const val HTTP_NOT_FOUND = 404

    // 版本号只允许字母、数字和 . _ + -。它要拼进地址，别的字符（&、#、空格……）一律不收。
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]*")

    /** 把用户填的兜底版本收拾成能拼进地址的样子；不像版本号的用默认值。 */
    fun normalizeFallbackVersion(raw: String?): String {
        val version = raw?.trim().orEmpty()
        return if (VERSION.matches(version)) version else DEFAULT_FALLBACK_VERSION
    }

    fun fallbackUrl(fallbackVersion: String?): String =
        FALLBACK_BASE_URL + normalizeFallbackVersion(fallbackVersion)

    /**
     * 第一次打开时加载哪个地址：有链接就原样加载链接；没有链接（或不是 http(s) 地址）就加载兜底入口。
     * 带不带 sid 不在这里判断，那是 M3 的链接校验的事。
     */
    fun selectInitialEntry(link: String?, fallbackVersion: String?): EntryTarget {
        val target = link?.trim().orEmpty()
        return if (NavigationPolicy.isHttpUrl(target)) {
            EntryTarget.Link(target)
        } else {
            EntryTarget.Fallback(fallbackUrl(fallbackVersion))
        }
    }

    /**
     * 主框架或子资源返回 HTTP 状态码之后怎么办。
     *
     * 只有「当前入口自己的主文档返回 404」才会动：电脑链接 404 换兜底，兜底 404 显示原生错误页。
     * 子资源的 404、页面自己跳到的别的文档的 404、不是 404 的状态码，都不触发。
     */
    fun decideOnHttpError(
        entry: EntryTarget?,
        requestUrl: String?,
        isForMainFrame: Boolean,
        statusCode: Int,
        fallbackVersion: String?,
    ): EntryAction {
        if (!isForMainFrame || statusCode != HTTP_NOT_FOUND) return EntryAction.NONE
        val target = entry ?: return EntryAction.NONE
        if (!sameDocumentUrl(requestUrl, target.url)) return EntryAction.NONE
        return when (target) {
            is EntryTarget.Link -> EntryAction.SWITCH_TO_FALLBACK
            is EntryTarget.Fallback -> EntryAction.SHOW_ERROR_PAGE
        }
    }

    // scheme://用户信息@主机:端口路径?查询#片段
    private val URL_PARTS = Regex("^(?:([A-Za-z][A-Za-z0-9+.-]*)://)?([^/?#]*)([^?#]*)(?:\\?([^#]*))?(?:#.*)?$")

    /**
     * 两个地址是不是同一份文档。先按原样比；不一样再归一之后比。
     * 传入的地址不合法（看不出 scheme 和主机）时返回 false。
     */
    fun sameDocumentUrl(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val left = forComparison(a) ?: return false
        val right = forComparison(b) ?: return false
        return left == right
    }

    /**
     * 只用来比较，结果不拿去加载。WebView 把地址交给回调时已经规范化过，和交进去的原样文本可能写法不同：
     * 这里把 scheme、主机转成小写，去掉用户信息、默认端口和片段，空路径按 `/` 算。查询串保持原样。
     */
    private fun forComparison(url: String): String? {
        val match = URL_PARTS.matchEntire(url) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        val (host, port) = splitAuthority(authority.substringAfterLast('@')) ?: return null
        if (scheme.isEmpty() || host.isEmpty()) return null
        val defaultPort = when (scheme) {
            "https" -> "443"
            "http" -> "80"
            else -> null
        }
        val portPart = if (port.isNotEmpty() && port != defaultPort) ":$port" else ""
        val path = match.groupValues[3].ifEmpty { "/" }
        val query = match.groupValues[4]
        return "$scheme://$host$portPart$path${if (query.isEmpty()) "" else "?$query"}"
    }

    /** `主机:端口` 拆成 (主机, 端口)；主机转成小写。IPv6 字面量带方括号，原样保留。 */
    private fun splitAuthority(authority: String): Pair<String, String>? {
        return if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            val rest = authority.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(':')) return null
            authority.substring(0, end + 1).lowercase() to rest.substringAfter(':', "")
        } else {
            val parts = authority.split(':')
            if (parts.size > 2) return null
            parts[0].lowercase() to parts.getOrElse(1) { "" }
        }
    }
}
