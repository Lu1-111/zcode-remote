package com.lululu.zcoderemote.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lululu.zcoderemote.SmokeLog

/**
 * 主 WebView 的 [WebViewClient]：导航策略、主文档 404 转兜底、断网页、pageLoaded 日志。
 *
 * 这里记的是「这一个 WebView 实例」的加载状态。渲染进程挂掉后会换新的实例和新的 client，状态自然归零。
 * WebView 各个回调的先后顺序不能想当然，怎么兜见 [LoadAttemptTracker]。
 *
 * 故意不覆写的方法：
 * - [onReceivedSslError]：证书有问题由 WebView 自己拦下，App 不替用户放行；
 * - 其它 scheme 的加载不交给系统：全在 [shouldOverrideUrlLoading] 里处理。
 */
internal class RemoteWebViewClient(private val kernel: RemoteWebViewModel) : WebViewClient() {

    private val attempts = LoadAttemptTracker()

    /** 主文档的 HTTP 状态码，从 onReceivedHttpError 拿到；连同它是哪次请求的。 */
    private var httpStatus: Int? = null
    private var httpStatusUrl: String? = null

    // region 导航策略（方案第 5 条）

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return when (NavigationPolicy.actionFor(request.url.scheme)) {
            // 所有 http、https 都留在 WebView 里，包括登录授权页和回跳页。没有域名白名单。
            NavigationAction.LOAD_IN_WEBVIEW, NavigationAction.LOAD_INTERNAL -> false
            NavigationAction.BLOCK_WITH_LOGIN_HINT -> {
                // 只在主框架提示：藏在页面里的 iframe 也可能来撞，不值得为此打扰用户。
                if (request.isForMainFrame) kernel.showLoginHint()
                true
            }
        }
    }

    // endregion

    // region 加载过程

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        attempts.onPageStarted(url)
        // 新文档开始了：旧文档里建的 Blob 已经没了，走了一半的「保存副本」就地收场（M4）。
        kernel.onNewDocumentStarted()
        // 上一个文档留下的状态码作废。同一个地址的留着：HTTP 错误会先于 onPageStarted 报到。
        if (url != null && url != httpStatusUrl) clearHttpStatus()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        when (attempts.onPageFinished(url)) {
            // 以错误告终的那一次、根本没开始就被顶掉的那一次，都不算加载完成：不记 pageLoaded，也不撤断网页。
            // 重复通知不是新的导航，什么都不做。
            LoadAttemptTracker.Outcome.FAILED,
            LoadAttemptTracker.Outcome.ABANDONED,
            LoadAttemptTracker.Outcome.DUPLICATE,
            -> Unit
            LoadAttemptTracker.Outcome.SUCCEEDED -> {
                if (url != null) kernel.onPageLoadSucceeded(url)
                logPageLoaded(view, url)
            }
        }
    }

    /** 主框架加载失败（连不上、域名解析不了、超时等）：显示原生断网页。子框架失败不管。 */
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val url = request.url.toString()
        attempts.onNetworkError(url)
        clearHttpStatus()
        kernel.showNetworkErrorPage()
    }

    /** 主文档返回了 HTTP 错误：只有「当前入口的主文档 404」才动，见 [EntrySelector.decideOnHttpError]。 */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        // 子资源（图片、脚本、接口）的 404 与兜底无关，照常交给页面自己处理。
        if (!request.isForMainFrame) return
        val url = request.url.toString()
        httpStatus = errorResponse.statusCode
        httpStatusUrl = url
        val action = EntrySelector.decideOnHttpError(
            entry = kernel.activeEntry,
            requestUrl = url,
            isForMainFrame = true,
            statusCode = errorResponse.statusCode,
            fallbackVersion = kernel.fallbackVersion.value,
        )
        // 这份文档要不要留在屏幕上：换兜底入口、换成原生断网页的，不算加载完成；其余的照常算。
        val documentReplaced = action != EntryAction.NONE
        attempts.onHttpError(url, fatal = documentReplaced)
        when (action) {
            EntryAction.NONE -> Unit
            EntryAction.SWITCH_TO_FALLBACK -> kernel.switchToFallback()
            EntryAction.SHOW_ERROR_PAGE -> kernel.showNetworkErrorPage()
        }
    }

    /** 页面用 pushState 改了地址时也会走到这里：记下当前地址，渲染进程挂掉后照它重新加载。 */
    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        kernel.rememberPageUrl(url)
    }

    // endregion

    // region 渲染进程挂掉

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        kernel.onWebViewCrashed(view)

    // endregion

    // region 内部

    /** App 自己发起了加载（重试、换入口）。见 [LoadAttemptTracker.onAppNavigation]。 */
    fun onAppNavigation(url: String?) {
        attempts.onAppNavigation(url)
        if (url != null && url == httpStatusUrl) clearHttpStatus()
    }

    private fun clearHttpStatus() {
        httpStatus = null
        httpStatusUrl = null
    }

    /**
     * 主框架加载完成时正好打印一行 pageLoaded（约定见 docs/ARCHITECTURE.md 第 8 节）。
     * 只有主机名和状态码，其余什么都不打。非 http(s) 的文档（about:blank 等）不打。
     */
    private fun logPageLoaded(view: WebView, url: String?) {
        val scheme = NavigationPolicy.schemeOf(url)
        if (!NavigationPolicy.isHttp(scheme)) return
        val host = Uri.parse(url).host

        // 4xx、5xx 用 onReceivedHttpError 给的状态码；2xx WebView 不直接给，问页面自己的 navigation 记录。
        val known = httpStatus?.takeIf { httpStatusUrl == url }
        clearHttpStatus()
        if (known != null) {
            SmokeLog.pageLoaded(host, known)
            return
        }
        view.evaluateJavascript(STATUS_SCRIPT) { result ->
            SmokeLog.pageLoaded(host, parseStatus(result))
        }
    }

    /**
     * evaluateJavascript 把数字结果按 JSON 编码后交回来：`"200"`；拿不到是 `null`。
     * 0 表示页面自己也不知道（非 http(s) 文档、跨源重定向等），不当成状态码。
     */
    private fun parseStatus(result: String?): Int? =
        result?.removeSurrounding("\"")?.toIntOrNull()?.takeIf { it in 100..599 }

    /** 只读，不改页面。Chromium 109 起才有 responseStatus，没有或者读不出来就得到空串。 */
    private companion object {
        const val STATUS_SCRIPT =
            "(function(){try{var e=performance.getEntriesByType('navigation');" +
                "var n=e&&e[0];return n&&typeof n.responseStatus==='number'?String(n.responseStatus):''" +
                "}catch(x){return ''}})()"
    }

    // endregion
}
