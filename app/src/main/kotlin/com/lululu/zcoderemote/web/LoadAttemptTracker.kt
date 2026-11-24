package com.lululu.zcoderemote.web

/**
 * 跟踪主框架的一次导航：它是不是已经开始了，是不是以错误告终，onPageFinished 来的时候该不该算加载完成。
 *
 * 纯逻辑，不引用任何 `android.*`。WebView 各个回调的先后顺序不能想当然，模拟器（WebView 124）上实测：
 *
 * ```
 * 正常加载：  onPageStarted → onPageFinished
 * HTTP 错误： onReceivedHttpError → onPageStarted → onPageFinished   （错误先于「开始」报）
 * 网络错误：  onReceivedError → onPageFinished                      （没有 onPageStarted）
 * ```
 *
 * 所以不能「在 onPageStarted 里记下这次导航，再看之后有没有出错」：HTTP 错误比 onPageStarted 先到，
 * 那样记下的失败会被紧跟着的 onPageStarted 冲掉，兜底入口自己 404 时断网页一闪就被撤掉了。
 * 这里改成：哪个回调先来都算这次导航开始，直到它的 onPageFinished 才收尾。
 *
 * 还有一种「只有结束、没有开始」的通知，不算加载完成（模拟器上实测到的两种）：
 *
 * ```
 * 被顶掉的导航：loadUrl(A) 还没拿到响应就 loadUrl(B) → onPageFinished(A)，A 从没开始过
 * 被拦下的跳转：302 跳到 weixin://，shouldOverrideUrlLoading 拦下 → onPageFinished(weixin://…)
 * ```
 *
 * 这两种里 WebView 里显示的都还是原来那一页。要是把它当成 A 加载完成，就会去读「当前文档」的状态码，
 * 读到的其实是原来那一页的，还会多打一行 pageLoaded。
 */
class LoadAttemptTracker {

    /** 有一次导航已经开始，还没等到它的 onPageFinished。 */
    private var open = false

    /** 这次导航的地址。用来分辨「同一次导航的后续回调」和「上一次没收尾就开始的另一次导航」。 */
    private var attemptUrl: String? = null

    /** 这次导航不算加载成功：网络错误，或者兜底入口自己 404。 */
    private var failed = false

    /** 上一次收尾的地址。没有 onPageStarted 的重复 onPageFinished 靠它挡掉。 */
    private var lastFinishedUrl: String? = null

    /** onPageFinished 来了，这次导航的结论。 */
    enum class Outcome {
        /** 加载完成了一页。 */
        SUCCEEDED,

        /** 收尾了，但这次是以错误告终的，不能当成加载完成。 */
        FAILED,

        /** 这次导航根本没开始（被新的导航顶掉，或者被拦下），什么都没加载出来。 */
        ABANDONED,

        /** 同一次加载被重复通知了，不是新的导航。 */
        DUPLICATE,
    }

    /** WebView 报告开始加载一个文档。 */
    fun onPageStarted(url: String?) {
        openAttempt(url)
    }

    /**
     * WebView 报告主框架出了 HTTP 错误。
     *
     * [fatal] 为 true 表示这份文档不会作为页面留在屏幕上（要换兜底入口，或者换成原生断网页），
     * 它的 onPageFinished 不算加载完成；为 false 表示服务器那份错误正文照常显示，算加载完成了一页。
     */
    fun onHttpError(url: String?, fatal: Boolean) {
        openAttempt(url)
        if (fatal) failed = true
    }

    /** WebView 报告主框架加载失败（连不上、域名解析不了、超时等）。 */
    fun onNetworkError(url: String?) {
        openAttempt(url)
        failed = true
    }

    /** WebView 报告加载完成。返回这次导航的结论，见 [Outcome]。 */
    fun onPageFinished(url: String?): Outcome {
        if (!open) {
            // 没有「开始」只有「结束」：上一次的重复通知，或者一次没开始就被顶掉、被拦下的导航。
            // 两种都不算加载完成；地址也记下来，它之后的重复通知一律挡掉。
            val duplicate = url == lastFinishedUrl
            lastFinishedUrl = url
            return if (duplicate) Outcome.DUPLICATE else Outcome.ABANDONED
        }
        open = false
        lastFinishedUrl = url
        return if (failed) Outcome.FAILED else Outcome.SUCCEEDED
    }

    /**
     * App 自己发起了加载（重试、换入口）。同一条地址如果还挂着一次没收尾的失败，把那次作废：这次是重新来。
     * 别的地址不动，留着给上一次导航的迟到回调。
     */
    fun onAppNavigation(url: String?) {
        if (open && url != null && url == attemptUrl) open = false
    }

    /** 哪个回调先来都算这次导航开始。返回 true 表示开了一次新的；false 表示是同一次导航的后续回调。 */
    private fun openAttempt(url: String?): Boolean {
        if (open && url != null && url != attemptUrl) {
            // 上一次还没收尾就来了别的地址：那已经是另一次导航（比如 404 之后我们自己加载了兜底入口）。
            open = false
        }
        if (open) return false
        open = true
        failed = false
        attemptUrl = url
        return true
    }
}
