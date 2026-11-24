package com.lululu.zcoderemote.web

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.lululu.zcoderemote.BuildConfig
import com.lululu.zcoderemote.R
import com.lululu.zcoderemote.save.SaveCopyBridge
import com.lululu.zcoderemote.save.SaveRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/**
 * WebView 内核：持有全应用唯一的那个 WebView，决定它加载什么、怎么响应页面的回调。
 *
 * 放在 ViewModel 里，所以界面重建（转屏、切换深色、改字号、进出分屏）时 WebView 原样留着，页面不重新加载。
 * 界面层（MainActivity、M3 的电脑列表）只通过这个类的公开方法跟内核打交道：
 *
 * - 把链接交给内核：[openLink]；兜底版本：[setFallbackVersion]。
 * - 界面接线：[attachHost]、[restoreFromState]、[takeWebViewForDisplay]、[onWebViewReleased]、[saveToState]、[detachHost]。
 * - 用户操作：[handleBackPress]、[retry]、[onFileChooserResult]。
 *
 * 链接和登录凭证按密码对待：这个类里任何地方都不写日志、不打印地址。
 */
class RemoteWebViewModel(application: Application) : AndroidViewModel(application) {

    /** 界面层提供的、必须有 Activity 才能做的事。内核只在界面接上期间持有它。 */
    interface Host {
        /** 把外链交给系统浏览器。没有能接的应用、或者没弹成，返回 false。 */
        fun openInBrowser(url: String): Boolean

        /** 弹出文件选择器。弹不出来返回 false。 */
        fun showFileChooser(intent: Intent): Boolean

        /**
         * 「保存副本」（M4，方案第 16 条）：弹系统「保存文件」让用户选位置。
         * 发起成功（对话框起来了）返回 true；结果由 [onSavePickerResult] 回给内核。
         */
        fun launchSavePicker(request: SaveRequest): Boolean

        /**
         * 返回键要「回到电脑列表」（第 7 节表的大多数行）。列表盖在原来的 WebView 之上，不另建 WebView。
         */
        fun returnToComputerList()

        /**
         * 返回键要「回桌面」（第 7 节表：读页面属性的脚本没有返回结果、且 WebView 不能后退）。
         * 界面层实现成 `moveTaskToBack(true)`，和电脑列表上按返回键一样。
         */
        fun returnToDesktop()
    }

    companion object {
        private const val STATE_ENTRY_KIND = "web.entryKind"
        private const val ENTRY_KIND_LINK = "link"
        private const val ENTRY_KIND_FALLBACK = "fallback"

        /** 读页面属性的脚本这么久还没回来，就当它没有结果。页面卡住时返回键不能跟着卡死。 */
        private const val BACK_PROBE_TIMEOUT_MS = 800L

        /** 页面连续撞非 http(s) 的 scheme 时，提示至多隔这么久弹一次。 */
        private const val HINT_INTERVAL_MS = 2_000L

        /** `window.open` 的临时 WebView 最多活这么久。 */
        private const val POPUP_MAX_LIFETIME_MS = 5_000L
    }

    /** Application 的 Context：给 Toast 用。它和 Activity 无关，活多久都行。 */
    private val app: Application
        get() = getApplication()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * WebView 用的 Context。界面在的时候指向 Activity：WebView 自己弹的系统对话框（下拉框、alert）靠它拿到窗口；
     * 界面销毁时指回 Application。ViewModel 比 Activity 活得久，不能让 WebView 把旧 Activity 拖住。
     */
    private val webViewContext = MutableContextWrapper(application)

    private var host: Host? = null
    private var hostActivity: WeakReference<Activity>? = null

    /** 全应用唯一的 WebView。null 表示还没建，或者刚挂掉、等界面下次来取时重建。 */
    private var webView: WebView? = null

    /** 正挂在界面上的那个实例。渲染进程挂掉之后它会和 [webView] 不同：旧的要等界面撤下来才能销毁。 */
    private var displayed: WebView? = null

    /** 渲染进程已经挂掉的实例。它们只能被摘下来销毁，别的任何方法都不能再调。 */
    private val crashedViews = Collections.newSetFromMap(IdentityHashMap<WebView, Boolean>())

    /** WebView 刚挂掉、还没重建时，等重建后要加载的地址。 */
    private var pendingReloadUrl: String? = null

    private var cleared = false
    private var started = false

    /** 当前入口：最近一次主动加载的电脑链接或兜底入口。没主动加载过为 null。 */
    private var entry: EntryTarget? = null

    /** 页面最近一次的地址。渲染进程挂掉后照它重新加载。只在内存里。 */
    private var lastPageUrl: String? = null

    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserMultiple = false
    private var backProbeInFlight = false
    private var lastHintAt = -HINT_INTERVAL_MS

    private val _currentLink = MutableStateFlow<String?>(null)

    /** 当前电脑链接，原样。null 表示没有链接，走兜底入口。只在内存里：不写日志，不显示到界面。 */
    val currentLink: StateFlow<String?> = _currentLink.asStateFlow()

    private val _fallbackVersion = MutableStateFlow(EntrySelector.DEFAULT_FALLBACK_VERSION)

    /** 兜底版本。默认是常量 [EntrySelector.DEFAULT_FALLBACK_VERSION]，可以用 [setFallbackVersion] 改。 */
    val fallbackVersion: StateFlow<String> = _fallbackVersion.asStateFlow()

    private val _webViewEpoch = MutableStateFlow(0L)

    /**
     * 「保存副本」的通道（M4，方案第 16 条）：文档起始脚本 + WebMessageListener，只对
     * https://zcode.z.ai 生效。内核建好 WebView 就装进去；特性不支持时它自己什么都不装。
     * 系统保存框要 Activity，经 [Host.launchSavePicker] 找界面层。
     */
    private val saveBridge = SaveCopyBridge(
        app.applicationContext,
        picker = object : SaveCopyBridge.Picker {
            override fun launchSavePicker(request: SaveRequest): Boolean =
                host?.launchSavePicker(request) ?: false
        },
    )

    /**
     * 界面该换一个 WebView 实例来挂的时候加一（现在只有渲染进程挂掉会加）。
     * 界面用它做 key：变了就把旧的撤下来，再调 [takeWebViewForDisplay] 取新的。
     */
    val webViewEpoch: StateFlow<Long> = _webViewEpoch.asStateFlow()

    /** 断网页的状态（纯逻辑，见 [ErrorPageState]）。WebView 藏不藏、重试转不转圈都以它为准。 */
    private val errorState = ErrorPageState()

    private val _errorPageVisible = MutableStateFlow(false)

    /** 原生断网页现在该不该显示。 */
    val errorPageVisible: StateFlow<Boolean> = _errorPageVisible.asStateFlow()

    private val _retrying = MutableStateFlow(false)

    /** 用户按了「重试」，这一发还没结束。只在断网页显示期间有意义。 */
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    init {
        // 只在 debug 包里打开 WebView 调试（chrome://inspect 用）。release 包里这个分支在编译时就被去掉。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    // ================================================================================ 把链接交给内核

    /**
     * 把一条电脑链接交给内核：设为当前链接并原样加载。不拆开重建，不补全，不改写 `app_version`。
     * 空的或者不是 http(s) 的地址返回 false，什么都不做。
     *
     * 同一条链接重复交进来不会再加载一遍——「同一条」以「它就是当前入口」为准，不只是「它等于
     * [currentLink]」：从这台电脑的链接换到兜底入口之后再选回这台电脑，仍然要重新加载。
     * 界面重建、进程被杀后重新启动时，启动意图会原样再交一次，不能把用户正在看的页面冲掉。
     */
    fun openLink(link: String?): Boolean {
        val target = link?.trim().orEmpty()
        if (!NavigationPolicy.isHttpUrl(target)) return false
        val current = entry
        if (_currentLink.value == target && current is EntryTarget.Link && current.url == target) return true
        _currentLink.value = target
        if (started) loadEntry(EntryTarget.Link(target))
        return true
    }

    /**
     * 打开一条不带 `sid` 的入口链接（免扫码入口）：原样加载，但**不计入「当前电脑链接」**——
     * 复制当前链接、电脑列表的「使用中」都以电脑为准，入口链接不算电脑。
     */
    fun openEntryLink(link: String?): Boolean {
        val target = link?.trim().orEmpty()
        if (!NavigationPolicy.isHttpUrl(target)) return false
        if (started) loadEntry(EntryTarget.Link(target))
        return true
    }

    /** 打开免扫码入口：`https://zcode.z.ai/remote/v4?app_version=<兜底版本>`（不带 sid）。引导页的按钮用。 */
    fun openFallbackEntry() {
        loadEntry(EntryTarget.Fallback(EntrySelector.fallbackUrl(_fallbackVersion.value)))
    }

    /** 改兜底版本。不像版本号的文本一律回到默认值，免得别的字符被拼进地址。 */
    fun setFallbackVersion(version: String?) {
        _fallbackVersion.value = EntrySelector.normalizeFallbackVersion(version)
        val current = entry
        if (current is EntryTarget.Fallback) {
            entry = EntryTarget.Fallback(EntrySelector.fallbackUrl(_fallbackVersion.value))
        }
    }

    // ================================================================================ 用户操作

    /** 返回键。决策表见 docs/ARCHITECTURE.md 第 7 节。 */
    fun handleBackPress() {
        val view = webView ?: return
        val currentHost = host
        if (backProbeInFlight) return
        backProbeInFlight = true

        var settled = false
        fun settle(page: MobilePage) {
            if (settled) return
            settled = true
            backProbeInFlight = false
            when (decideBack(page, view.canGoBack())) {
                BackTarget.WEB_BACK -> view.goBack()
                BackTarget.COMPUTER_LIST -> currentHost?.returnToComputerList()
                BackTarget.DESKTOP -> currentHost?.returnToDesktop()
            }
        }

        val timeout = Runnable { settle(MobilePage.UNKNOWN) }
        mainHandler.postDelayed(timeout, BACK_PROBE_TIMEOUT_MS)
        view.evaluateJavascript(BackProbe.SCRIPT) { result ->
            mainHandler.removeCallbacks(timeout)
            settle(BackProbe.pageFromScriptResult(result))
        }
    }

    /**
     * 重新加载：断网页上的「重试」，以后也可以接菜单里的「重新加载」。
     *
     * 断网页不撤、WebView 也不放出来，要等新页面真正加载成功（见 [ErrorPageState]）；按钮先禁用、转个圈。
     */
    fun retry() {
        if (cleared) return
        errorState.onRetry()
        publishErrorState()
        val view = webView
        val target = entry
        val current = view?.url
        val sameDocument = view != null && current != null && NavigationPolicy.isHttpUrl(current) &&
            (target == null || EntrySelector.sameDocumentUrl(current, target.url))
        // 失败的就是入口这一页：原地重新加载，不在历史记录里再堆一条。
        // 否则（比如登录走到一半断了，或者 WebView 刚挂掉还没重建）回到入口重新打开。
        if (view != null && sameDocument) {
            startLoad(view, current)
            view.reload()
        } else {
            loadEntry(target ?: EntrySelector.selectInitialEntry(_currentLink.value, _fallbackVersion.value))
        }
    }

    /**
     * 「在浏览器中打开」（M3 设置项）：把当前页面交给系统浏览器。
     * 调用方负责先提示「同一台电脑同一时间只能一个页面」。当前没有打开的页面时返回 false。
     */
    fun openCurrentPageInBrowser(): Boolean {
        val view = webView ?: return false
        val url = view.url?.takeIf { NavigationPolicy.isHttpUrl(it) }
            ?: lastPageUrl
            ?: entry?.url?.takeIf { NavigationPolicy.isHttpUrl(it) }
            ?: return false
        return host?.openInBrowser(url) == true
    }

    /**
     * 「复制当前链接」（M3 设置项）：当前那条电脑链接的原样文本，交给调用方去复制。
     * 只用于用户明确确认过的复制，别处不要拿它做显示。没有电脑链接时为 null。
     */
    fun currentComputerLink(): String? = _currentLink.value

    /** 文件选择器关掉了。取消、或者选了却拿不到内容，都回调 null。 */
    fun onFileChooserResult(resultCode: Int, data: Intent?) {        val callback = pendingFileChooser ?: return
        val multiple = pendingFileChooserMultiple
        pendingFileChooser = null
        callback.onReceiveValue(pickedUris(resultCode, data, multiple))
    }

    /** 「保存副本」的系统保存框收场：[uri] 是用户选的位置，null 是取消了。 */
    fun onSavePickerResult(uri: Uri?) {
        saveBridge.onTargetPicked(uri)
    }

    /**
     * 主框架开始了新文档（RemoteWebViewClient.onPageStarted）：旧文档里的 Blob 已经没了，
     * 正在走的「保存副本」就此收场（不提示，页面都换了，提示没有意义）。
     */
    fun onNewDocumentStarted() {
        saveBridge.onDocumentChanged()
    }

    /**
     * 软键盘弹出、高度变了之后由外壳调用：WebView 变矮了，把页面里正在输入的框滚回可见区域（方案第 15 条 ④）。
     * 只滚动、不改页面；获得焦点的不是可输入的元素，或者页面不配合，就什么都不做。见 [InputProbe]。
     */
    fun revealFocusedInput() {
        val view = webView ?: return
        if (cleared) return
        view.evaluateJavascript(InputProbe.REVEAL_FOCUSED_SCRIPT, null)
    }

    // ================================================================================ 界面接线

    /** 界面接上了。在 onCreate 里、挂载 WebView 之前调用。 */
    fun attachHost(activity: Activity, host: Host) {
        this.host = host
        hostActivity = WeakReference(activity)
        webViewContext.baseContext = activity
    }

    /** 界面销毁了。在 onDestroy 里调用：放开 Activity，之后内核不再持有它。 */
    fun detachHost(activity: Activity) {
        if (hostActivity?.get() !== activity) return
        host = null
        hostActivity = null
        webViewContext.baseContext = app
    }

    /**
     * 开始运行，只在第一次调用时生效：进程被杀过就恢复 WebView 的状态。
     *
     * 必须在任何 loadUrl 之前调用：restoreState 只有在 WebView 还没加载过东西时才管用。
     * [state] 里除了 WebView 自己的状态，只有「当时打开的是哪个入口」，见 [saveToState]。
     *
     * 返回 true 表示 WebView 的状态恢复进去了；没有状态可恢复、或恢复不了（状态残缺、WebView 不收）
     * 返回 false，调用方照第一次打开处理（M3 起第一次打开什么由界面层决定）。
     *
     * M3 起这里**不再自动加载兜底入口**：没有可恢复的状态时打开什么由界面层决定——
     * 没有电脑或不止一台时显示电脑列表，只有一台时打开它的链接（[openLink]），
     * 引导页的「免扫码入口」走 [openFallbackEntry]。内核只是随时待命。
     */
    fun restoreFromState(state: Bundle?): Boolean {
        if (started) return false
        started = true
        ensureWebView()
        val saved = state ?: return false
        val view = webView ?: return false
        if (!restoreInto(view, saved)) return false
        val currentLink = _currentLink.value
        entry = when (saved.getString(STATE_ENTRY_KIND)) {
            ENTRY_KIND_LINK -> currentLink?.let { EntryTarget.Link(it) }
            ENTRY_KIND_FALLBACK -> EntryTarget.Fallback(EntrySelector.fallbackUrl(_fallbackVersion.value))
            else -> null
        }
        lastPageUrl = view.url?.takeIf { NavigationPolicy.isHttpUrl(it) } ?: entry?.url
        return true
    }

    /**
     * 进程被杀后恢复时，把「当时在用的那条电脑链接」补登记给内核（404 转兜底、「重新加载」都用它）。
     * 页面本身已经由 WebView 的状态恢复，这里只登记，**不加载**；内核已经有入口时什么都不做。
     */
    fun adoptRestoredLink(link: String?) {
        if (entry != null) return
        val target = link?.trim().orEmpty()
        if (!NavigationPolicy.isHttpUrl(target)) return
        _currentLink.value = target
        entry = EntryTarget.Link(target)
    }

    /** 恢复成功返回 true。恢复不了（状态残缺、WebView 不收）返回 false，调用方照第一次打开处理。 */
    private fun restoreInto(view: WebView, saved: Bundle): Boolean =
        runCatching { view.restoreState(saved) != null }.getOrDefault(false)

    /**
     * 把 WebView 的状态存进界面的状态里，进程被杀之后靠它回到原来的页面（方案第 8 条）。
     *
     * WebView 的状态里带着历史记录的地址，所以这个 Bundle 和链接一样对待：不写日志，不打出来。
     */
    fun saveToState(out: Bundle) {
        val view = webView ?: return
        runCatching { view.saveState(out) }
        val target = entry ?: return
        out.putString(
            STATE_ENTRY_KIND,
            when (target) {
                is EntryTarget.Link -> ENTRY_KIND_LINK
                is EntryTarget.Fallback -> ENTRY_KIND_FALLBACK
            },
        )
    }

    /** 界面要挂 WebView：取走当前的实例。同一个实例会被前后几个界面反复取走。 */
    fun takeWebViewForDisplay(): WebView {
        val view = ensureWebView()
        // 正常情况下它这时没有父视图（上一个界面已经销毁）。万一还挂着，先摘下来：一个 View 不能有两个父亲。
        (view.parent as? ViewGroup)?.removeView(view)
        displayed = view
        return view
    }

    /** 界面把这个实例撤下来了。只有不再是当前实例的才销毁；当前的留着给下一个界面接着用。 */
    fun onWebViewReleased(view: WebView) {
        if (displayed === view) displayed = null
        if (view !== webView || cleared) destroyWebView(view)
    }

    // ================================================================================ 页面回调（WebViewClient 调用）

    /** 当前入口。没主动加载过为 null。 */
    val activeEntry: EntryTarget?
        get() = entry

    /** 换成兜底入口。当前已经是兜底时不会走到这里（见 EntrySelector.decideOnHttpError），所以不会来回跳。 */
    fun switchToFallback() {
        loadEntry(EntryTarget.Fallback(EntrySelector.fallbackUrl(_fallbackVersion.value)))
    }

    /** 主框架加载失败：显示原生断网页。 */
    fun showNetworkErrorPage() {
        // 先把 WebView 藏起来。它自己那份错误页会把整条链接写在屏幕上，不能让它露出来；
        // 从这一刻起一直藏到之后某一次主框架真正加载成功，重试、换入口都不放（见 ErrorPageState）。
        webView?.visibility = View.INVISIBLE
        errorState.onLoadFailed()
        publishErrorState()
    }

    /**
     * 主框架加载成功了：记下当前地址，撤掉断网页。
     * 全应用只有这里会把藏起来的 WebView 放回去。
     */
    fun onPageLoadSucceeded(url: String) {
        rememberPageUrl(url)
        errorState.onLoadSucceeded()
        publishErrorState()
        webView?.visibility = View.VISIBLE
    }

    fun rememberPageUrl(url: String?) {
        if (url != null && NavigationPolicy.isHttpUrl(url)) lastPageUrl = url
    }

    /**
     * 渲染进程挂掉了。从这一刻起这个 WebView 实例不能再碰（除了从视图树上摘下来销毁）。
     *
     * 这里只做四件事：了结挂起的收尾（保存通道、文件选择器）、记下它挂了、让界面换一个实例、
     * 记下新实例该加载哪一页。新的 WebView 等界面下次来取（[takeWebViewForDisplay]）时才建，
     * 不在回调里当场新建。
     * 返回 true 表示 App 自己处理了；返回 false 系统会让整个 App 跟着崩。
     */
    fun onWebViewCrashed(view: WebView): Boolean {
        val wasCurrent = view === webView
        // 走到一半的保存没法继续了：页面在渲染进程里，进程挂了 Blob 也没了。收场，不提示。
        saveBridge.reset()
        // 挂起的文件选择器也要了结，别让结果回来时对已崩溃实例的回调再调 onReceiveValue。
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        crashedViews.add(view)
        // 没挂在界面上的现在就销毁；挂在界面上的等界面撤下来（onWebViewReleased）再销毁。
        if (displayed !== view) destroyWebView(view)
        if (wasCurrent) {
            webView = null
            // 重新加载页面最近一次的地址；它拿不到（或者已经不是网页地址）就回到入口。
            pendingReloadUrl = lastPageUrl?.takeIf { NavigationPolicy.isHttpUrl(it) }
                ?: (entry ?: EntrySelector.selectInitialEntry(_currentLink.value, _fallbackVersion.value)).url
            // 换进来的是新的 WebView，里面没有旧的那张错误页。
            errorState.onWebViewReplaced()
            publishErrorState()
            _webViewEpoch.value = _webViewEpoch.value + 1
        }
        return true
    }

    /** 页面想拉起别的 App（非 http(s) 的 scheme）：不交给系统，提示用户改用页面内的方式登录。 */
    fun showLoginHint() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHintAt < HINT_INTERVAL_MS) return
        lastHintAt = now
        Toast.makeText(app, R.string.web_login_scheme_blocked, Toast.LENGTH_LONG).show()
    }

    // ================================================================================ 页面回调（WebChromeClient 调用）

    /**
     * 页面要选文件（附件、反馈截图）。用系统选择器，支持多选；取消时回调 null。
     * 返回 false 时 WebView 自己按取消处理。
     */
    fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        val currentHost = host ?: return false
        // 上一次还没回结果的先了结，不然 WebView 会一直等着它，同一个输入框再点就没反应。
        pendingFileChooser?.onReceiveValue(null)
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val intent = params.createIntent().apply {
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pendingFileChooser = callback
        pendingFileChooserMultiple = multiple
        if (currentHost.showFileChooser(intent)) return true
        pendingFileChooser = null
        callback.onReceiveValue(null)
        return false
    }

    /**
     * 页面用 `window.open` 打开外链（方案第 15 条 ②）。
     *
     * 建一个临时 WebView 接住新窗口的地址，交给系统浏览器，然后释放临时 WebView。
     * 不是用户手势触发的返回 false，不弹。整个过程主 WebView 始终只有一个在线。
     */
    fun openPopupWindow(source: WebView, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (!isUserGesture) return false
        // 没有界面在前台时开不了系统浏览器，也不该弹窗。
        if (host == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(source.context)
        popup.settings.allowFileAccess = false
        val client = PopupWebViewClient(onOpenInBrowser = ::openInBrowser, onBlocked = ::showLoginHint)
        popup.webViewClient = client
        transport.webView = popup
        resultMsg.sendToTarget()
        client.expireAfter(popup, POPUP_MAX_LIFETIME_MS)
        return true
    }

    // ================================================================================ 内部

    private fun publishErrorState() {
        _errorPageVisible.value = errorState.covering
        _retrying.value = errorState.retrying
    }

    /** App 自己发起一次加载之前先告诉 client：同一条地址上一次失败的那次作废，这次是重新来。 */
    private fun startLoad(view: WebView, url: String) {
        (view.webViewClient as? RemoteWebViewClient)?.onAppNavigation(url)
    }

    private fun loadEntry(target: EntryTarget) {
        entry = target
        pendingReloadUrl = null
        val view = ensureWebView()
        // 这里不撤断网页、也不把 WebView 放出来：要是上一页是加载失败，WebView 里还是它自己那张带链接的错误页，
        // 新页面出来之前露出来的就是它。等新页面加载成功（onPageLoadSucceeded）再说。
        startLoad(view, target.url)
        view.loadUrl(target.url)
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = createWebView()
        webView = view
        // 是渲染进程挂掉之后重建的：接着加载挂掉时那一页。
        pendingReloadUrl?.let { url ->
            pendingReloadUrl = null
            startLoad(view, url)
            view.loadUrl(url)
        }
        return view
    }

    private fun createWebView(): WebView {
        val view = WebView(webViewContext)

        // 必须给出明确的 LayoutParams，而且宽高都得是 MATCH_PARENT。
        //
        // WebView 靠 LayoutParams 判断自己的高度是不是定死的。LayoutParams 为空（Compose 的 AndroidView 默认就不给）
        // 或者写成 wrap_content 时，它认为高度由内容决定，页面里的 vh、dvh 和 height 媒体查询一律按 0 算。
        // 手机页靠 min-h-dvh / 100dvh 撑满整屏，结果整页塌成 0 高，屏幕上只剩背景色。
        // 模拟器上实测：不设时 100vh = 0px，设了之后 100vh = 818px。
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        with(view.settings) {
            // 必开项（方案第 6 条）。domStorageEnabled 默认是 false：不开就没有 localStorage，登录态留不住。
            javaScriptEnabled = true
            domStorageEnabled = true
            // 让 window.open 走到 onCreateWindow；不设的话新窗口回调默认什么都不做、返回 false。
            setSupportMultipleWindows(true)
            // 保持关闭。API 30 以下默认是开的，所以写明。
            allowFileAccess = false
            // 标准的 Chrome 安卓串：去掉「; wv」和「Version/x.x」，其余（Chrome 版本号等）保留。
            userAgentString = UserAgent.toChromeUserAgent(WebSettings.getDefaultUserAgent(app))
            // 文字不跟随系统字号。WebView 默认在创建时把当时的系统字号折成文字缩放（模拟器上实测：字号 1.3 时冷启动，
            // 16px 的文字是 20.8px），可运行中改字号又不跟着变，渲染进程挂掉重建后还会按新字号重来，同一个页面三种样子。
            // Chrome 安卓版本来就不跟随系统字号；钉成 100，页面任何时候都按设计尺寸排。要看小字可以双指放大。
            textZoom = 100
        }
        view.webViewClient = RemoteWebViewClient(this)
        view.webChromeClient = RemoteWebChromeClient(this)
        // 保存副本的通道：要在首次 loadUrl 之前装（文档起始脚本只对装完之后开始的文档生效）。
        // 特性不支持时 install 什么都不做、返回 false，这里也不提示——拦不到点击就没有可提示的时机。
        saveBridge.install(view)
        return view
    }

    private fun openInBrowser(url: String) {
        val currentHost = host
        if (currentHost == null || !currentHost.openInBrowser(url)) {
            Toast.makeText(app, R.string.web_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /** 把选择器的结果翻译成 WebView 要的样子：没选、取消、选了却没内容，都是 null。 */
    private fun pickedUris(resultCode: Int, data: Intent?, multiple: Boolean): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK) return null
        val clip = data?.clipData
        val uris: List<Uri> = when {
            clip != null && clip.itemCount > 0 ->
                (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            else -> data?.data?.let { listOf(it) } ?: return null
        }
        // 只让选一个的输入框，哪怕选择器给了好几个也只收第一个。
        return (if (multiple) uris else uris.take(1)).toTypedArray()
    }

    private fun destroyWebView(view: WebView) {
        destroyWebView(view, crashed = crashedViews.remove(view))
    }

    override fun onCleared() {
        cleared = true
        saveBridge.release()
        mainHandler.removeCallbacksAndMessages(null)
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        host = null
        hostActivity = null
        webViewContext.baseContext = app
        pendingReloadUrl = null
        // 还挂在界面上的那个等界面撤下来再销毁（onWebViewReleased）；没挂着的现在就销毁。
        webView?.let { if (displayed !== it) destroyWebView(it) }
        webView = null
    }
}

/**
 * 销毁一个 WebView：先从视图树上摘下来，再销毁。
 *
 * [crashed] 为 true 表示它的渲染进程已经挂了：那种实例只能摘下来和销毁，别的任何方法都不能再调。
 */
internal fun destroyWebView(view: WebView, crashed: Boolean) {
    (view.parent as? ViewGroup)?.removeView(view)
    if (!crashed) {
        view.stopLoading()
        view.removeAllViews()
    }
    view.destroy()
}
