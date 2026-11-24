package com.lululu.zcoderemote.web

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail

/**
 * 主 WebView 的 [WebChromeClient]：文件选择、新窗口。
 *
 * 具体怎么做都在 [RemoteWebViewModel] 里，这里只转发：那两件事都需要 Activity（结果回调、系统浏览器），
 * Activity 是换了又换的，不能让 WebView 攥着它。
 *
 * 故意不覆写 [onPermissionRequest]：保持默认拒绝。页面的摄像头、麦克风、定位请求一律不批。
 */
internal class RemoteWebChromeClient(private val kernel: RemoteWebViewModel) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = kernel.showFileChooser(filePathCallback, fileChooserParams)

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        kernel.openPopupWindow(view, isUserGesture, resultMsg)
}

/**
 * `window.open` 弹出来的新窗口用的临时 WebView 的 [WebViewClient]。
 *
 * 它只有一个用处：接住新窗口第一个要打开的地址。接到之后交给系统浏览器，自己立刻销毁。
 * 临时 WebView 从不挂到界面上，里面什么都不加载，所以主 WebView 始终只有一个在线。
 */
internal class PopupWebViewClient(
    private val onOpenInBrowser: (String) -> Unit,
    private val onBlocked: () -> Unit,
) : WebViewClient() {

    // 自己的 Handler：临时 WebView 的寿命不受内核的 ViewModel 管辖，ViewModel 清掉之后它也要能自己收尾。
    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var popup: WebView? = null
    private val releaseRunnable = Runnable { popup?.let(::release) }

    /** 兜底：页面一直不往新窗口里放地址时，到时间也要把这个临时 WebView 放掉，不能留着。 */
    fun expireAfter(view: WebView, lifetimeMs: Long) {
        popup = view
        handler.postDelayed(releaseRunnable, lifetimeMs)
    }

    private fun release(view: WebView) {
        if (released) return
        released = true
        handler.removeCallbacks(releaseRunnable)
        popup = null
        destroyWebView(view, crashed = false)
    }

    // 不在 WebViewClient 的回调里当场销毁 WebView：那会儿 WebView 内部还在用它。排到下一轮消息再做。
    private fun releaseSoon(view: WebView) {
        handler.post { release(view) }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) takeUrl(view, request.url.toString())
        // 临时 WebView 里什么都不加载，子框架的请求也一并挡掉。
        return true
    }

    private fun takeUrl(view: WebView, url: String) {
        if (released) return
        when (NavigationPolicy.actionFor(NavigationPolicy.schemeOf(url))) {
            // 外链：交给系统浏览器。
            NavigationAction.LOAD_IN_WEBVIEW -> {
                onOpenInBrowser(url)
                releaseSoon(view)
            }
            // 和主 WebView 同一条规矩：非 http(s) 的 scheme 不交给系统，提示用户改用页面内的方式登录。
            NavigationAction.BLOCK_WITH_LOGIN_HINT -> {
                onBlocked()
                releaseSoon(view)
            }
            // about:blank：新窗口刚建好还是空的，页面可能接着才往里放地址，先等等。
            // 其余内部地址（blob:、data:）系统浏览器打不开，直接放掉。
            NavigationAction.LOAD_INTERNAL -> if (url != "about:blank") releaseSoon(view)
        }
    }

    /** POST 跳转不经过 shouldOverrideUrlLoading，所以这里再兜一道。 */
    override fun onPageFinished(view: WebView, url: String?) {
        if (url != null && NavigationPolicy.isHttpUrl(url)) takeUrl(view, url)
    }

    /** 临时 WebView 的渲染进程也会挂。不接住的话整个 App 跟着崩。 */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        releaseSoon(view)
        return true
    }
}
