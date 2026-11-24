package com.lululu.zcoderemote.web

/**
 * UA（方案第 6 条）。纯逻辑，不引用任何 `android.*`。
 *
 * WebView 默认的 UA 比同样内核的 Chrome 多两处标记：设备信息末尾的「`; wv`」和「`Version/x.x`」。
 * 把这两处去掉，剩下的就是标准 Chrome 安卓串；Chrome 版本号、系统版本这些其余部分原样保留，
 * 和实际内核保持一致。
 */
object UserAgent {
    // 「; wv)」：只在设备信息那一段的右括号前出现。
    private val WEBVIEW_MARK = Regex(""";\s*wv\s*(?=\))""")

    // 「Version/4.0」：连同它后面的空格一起去掉，前后两个词之间正好留一个空格。
    private val VERSION_TOKEN = Regex("""\s*\bVersion/[\d.]+""")

    fun toChromeUserAgent(webViewUserAgent: String): String =
        webViewUserAgent
            .replace(WEBVIEW_MARK, "")
            .replace(VERSION_TOKEN, "")
}
