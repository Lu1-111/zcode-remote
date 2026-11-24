package com.lululu.zcoderemote.web

import java.util.Locale

/**
 * 导航策略（方案第 5 条）。纯逻辑，不引用任何 `android.*`。
 *
 * 所有 http、https 地址一律留在 WebView 里，包括登录授权页和回跳页，不做域名白名单。
 * 非 http(s) 的 scheme 拦下来，不交给系统。
 */

/** 一次导航请求该怎么处理。 */
enum class NavigationAction {
    /** 照常在 WebView 里加载：所有 http、https。 */
    LOAD_IN_WEBVIEW,

    /**
     * 网页自己的内部地址（`about:`、`blob:`、`data:`）。
     * 它们出不了 WebView，也拉不起别的 App，照常加载、不提示；拦下反而会让页面里的预览、弹层失效。
     */
    LOAD_INTERNAL,

    /** 其余 scheme（`weixin://`、`intent://`、`market://` 等）：拦下，不交给系统，提示用户改用页面内的方式登录。 */
    BLOCK_WITH_LOGIN_HINT,
}

object NavigationPolicy {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val HTTP_URL = Regex("^[Hh][Tt][Tt][Pp][Ss]?://")
    private val INTERNAL_SCHEMES = setOf("about", "blob", "data")

    private const val HTTP = "http"
    private const val HTTPS = "https"

    /** 取地址的 scheme（转成小写）。看不出 scheme 时返回 null。 */
    fun schemeOf(url: String?): String? = url?.let { SCHEME.find(it)?.groupValues?.get(1) }?.let(::normalizeScheme)

    fun isHttp(scheme: String?): Boolean {
        val normalized = scheme?.let(::normalizeScheme) ?: return false
        return normalized == HTTP || normalized == HTTPS
    }

    /** 是不是一条 http 或 https 地址。只看开头，不拆开、不补全。 */
    fun isHttpUrl(url: String?): Boolean = url != null && HTTP_URL.containsMatchIn(url)

    /** [scheme] 大写小写都认：直接传 `Uri.getScheme()` 的原样结果进来也可以。 */
    fun actionFor(scheme: String?): NavigationAction {
        val normalized = scheme?.let(::normalizeScheme)
        return when {
            normalized == HTTP || normalized == HTTPS -> NavigationAction.LOAD_IN_WEBVIEW
            normalized in INTERNAL_SCHEMES -> NavigationAction.LOAD_INTERNAL
            else -> NavigationAction.BLOCK_WITH_LOGIN_HINT
        }
    }

    // scheme 只由 ASCII 字母、数字和 + - . 组成，所以按字符转小写就够了，不依赖区域设置。
    private fun normalizeScheme(scheme: String): String = scheme.lowercase(Locale.ROOT)
}
