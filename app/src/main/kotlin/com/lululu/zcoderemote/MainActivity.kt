package com.lululu.zcoderemote

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.lululu.zcoderemote.devices.AddComputerScreen
import com.lululu.zcoderemote.devices.Computer
import com.lululu.zcoderemote.devices.ComputerListScreen
import com.lululu.zcoderemote.devices.ComputerStore
import com.lululu.zcoderemote.devices.LinkCheck
import com.lululu.zcoderemote.devices.RemoteLink
import com.lululu.zcoderemote.devices.ScanScreen
import com.lululu.zcoderemote.devices.StartupDestination
import com.lululu.zcoderemote.devices.selectStartupDestination
import com.lululu.zcoderemote.save.SaveRequest
import com.lululu.zcoderemote.ui.ZCodeTheme
import com.lululu.zcoderemote.web.RemoteWebView
import com.lululu.zcoderemote.web.RemoteWebViewModel
import kotlinx.coroutines.launch

/**
 * 全应用唯一的 Activity，也是唯一的导出组件。
 *
 * 它自己不管 WebView，只做分发和接线：
 * - WebView 内核在 `web/`（M2），入口是 [RemoteWebViewModel]；
 * - 电脑管理在 `devices/`（M3）：电脑列表、添加（扫码/粘贴/系统分享）、设置与更多；
 * - 这一页持有「现在显示哪一屏」的状态，并把返回键、意图、剪贴板这些需要 Activity 的事接给各屏。
 *
 * singleTask + [onNewIntent]：从别的 App 用「打开链接」「分享」再次唤起时不会叠出第二个实例，
 * 全 App 始终只有一个 WebView 页面在线（方案第 11、13 条）。
 * 电脑列表等原生界面**盖在** WebView 之上（还是那一个 WebView，不重建、不另建）。
 */
class MainActivity : ComponentActivity(), RemoteWebViewModel.Host {

    private val model: RemoteWebViewModel by viewModels()
    private val store by lazy { ComputerStore(applicationContext) }

    /** 这几屏会来回切，放快照状态里；配置变更不重建（清单里的 configChanges），进程被杀后按保存的标记恢复。 */
    private enum class AppScreen { LIST, ADD, SCAN, WEB }

    private var screen by mutableStateOf(AppScreen.LIST)
    private var computers by mutableStateOf<List<Computer>>(emptyList())
    private var storeReady by mutableStateOf(false)
    private var addScreenText by mutableStateOf("")

    /** 冷启动时意图里带来的链接（等 DataStore 的电脑列表就绪后一起处理）。 */
    private var pendingIncomingLink: String? = null

    /** 这一次 onCreate 是不是进程被杀后的恢复（有界面状态可恢复）。 */
    private var restoredFromState = false

    /** 进程被杀后的恢复里，WebView 的状态有没有真的恢复进去；没有就照第一次打开处理。 */
    private var webViewRestored = false

    /**
     * 文件选择器。必须在 Activity 进入 STARTED 之前注册，所以放在属性初始化里。
     * 界面重建后新的 Activity 重新注册，选择器的结果会交到新 Activity，再由内核转给 WebView（内核没换）。
     */
    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            model.onFileChooserResult(result.resultCode, result.data)
        }

    /**
     * 「保存副本」的系统保存框（M4，方案第 16 条）。必须在 STARTED 之前注册，同样放在属性初始化里。
     * MIME 固定二进制：CreateDocument 的类型在注册时就定死，发起一次只换建议文件名；
     * 文件的字节由页面发过来，类型对保存没有影响。结果（选好的位置或取消）交回内核。
     */
    private val saveDocumentLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            model.onSavePickerResult(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 铺到系统栏下面，WebView 外壳自己留白（RemoteWebView）。App 固定深色，系统栏图标也钉成浅色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        model.attachHost(this, this)
        restoredFromState = savedInstanceState != null

        // 顺序有讲究：先把意图里的链接记下来（不加载），再恢复内核，
        // 等电脑列表就绪后统一决定这次打开什么。
        pendingIncomingLink = extractLinkFromIntent(intent)
        webViewRestored = model.restoreFromState(savedInstanceState)
        if (restoredFromState) {
            // 进程被杀后回来：当时在哪一屏记在界面状态里；页面本身由 WebView 的状态恢复。
            screen = if (savedInstanceState?.getBoolean(STATE_SHOW_WEB, true) ?: true) AppScreen.WEB else AppScreen.LIST
        }

        setContent {
            ZCodeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }

        // 电脑列表（DataStore）。第一份列表到手后再决定冷启动去哪一屏。
        lifecycleScope.launch {
            store.computers.collect { list ->
                computers = list
                if (!storeReady) {
                    storeReady = true
                    onStoreReadyForStartup()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask：从别的 App 再次唤起时走这里，不会叠出第二个 Activity 或 WebView。
        setIntent(intent)
        val link = extractLinkFromIntent(intent) ?: return
        if (storeReady) handleIncomingLink(link) else pendingIncomingLink = link
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // WebView 的状态（历史记录）存进界面的状态里，进程被杀后内核据此恢复（方案第 8 条）。
        model.saveToState(outState)
        outState.putBoolean(STATE_SHOW_WEB, screen == AppScreen.WEB)
    }

    override fun onDestroy() {
        // 先放开 Activity 再走销毁：内核比 Activity 活得久，不能攥着它。
        model.detachHost(this)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 界面

    /**
     * 界面的根。WebView 那一层**始终**在下面（盖着不等于销毁：还是同一个实例，页面不重新加载），
     * 原生界面按需盖在上面。返回键：盖着的时候归原生界面（RemoteWebView 的 BackHandler 被关掉）。
     */
    @Composable
    private fun AppRoot() {
        val currentLink by model.currentLink.collectAsState()
        Box(modifier = Modifier.fillMaxSize()) {
            RemoteWebView(model, backHandlingEnabled = screen == AppScreen.WEB)
            when (screen) {
                AppScreen.WEB -> Unit
                AppScreen.LIST -> ComputerListScreen(
                    computers = computers,
                    currentLink = currentLink,
                    pageActionsAvailable = model.activeEntry != null,
                    onOpen = ::openComputer,
                    onRename = { sid, name -> lifecycleScope.launch { store.rename(sid, name) } },
                    onDelete = { sid -> lifecycleScope.launch { store.delete(sid) } },
                    onAddComputer = { screen = AppScreen.ADD },
                    onScan = { screen = AppScreen.SCAN },
                    onOpenFallbackEntry = { model.openFallbackEntry(); screen = AppScreen.WEB },
                    onReload = { model.retry() },
                    onOpenInBrowser = { onOpenInBrowserRequested() },
                    onCopyLink = { onCopyLinkRequested() },
                    onSignOut = { onSignOutRequested() },
                    onBackToDesktop = { moveTaskToBack(true) },
                )

                AppScreen.ADD -> AddComputerScreen(
                    text = addScreenText,
                    onTextChange = { addScreenText = it },
                    readClipboard = ::readClipboardText,
                    onAddComputer = { link, checked ->
                        lifecycleScope.launch {
                            val computer = store.upsert(link, checked, System.currentTimeMillis())
                            openComputer(computer)
                        }
                    },
                    onOpenEntryLink = { link ->
                        model.openEntryLink(link)
                        screen = AppScreen.WEB
                    },
                    onScan = { screen = AppScreen.SCAN },
                    onBack = { screen = AppScreen.LIST },
                )

                AppScreen.SCAN -> ScanScreen(
                    onScannedComputer = { link, checked ->
                        lifecycleScope.launch {
                            val computer = store.upsert(link, checked, System.currentTimeMillis())
                            openComputer(computer)
                        }
                    },
                    onScannedEntry = { link ->
                        model.openEntryLink(link)
                        screen = AppScreen.WEB
                    },
                    onSwitchToPaste = { screen = AppScreen.ADD },
                    onBack = { screen = AppScreen.LIST },
                )
            }
        }
    }

    // ------------------------------------------------------------------ 打开什么

    /** DataStore 的第一份电脑列表到手了：决定这次启动打开什么。 */
    private fun onStoreReadyForStartup() {
        val incoming = pendingIncomingLink
        if (incoming != null) {
            pendingIncomingLink = null
            handleIncomingLink(incoming)
            return
        }
        if (restoredFromState && webViewRestored) {
            // 进程被杀后回来：当时在哪一屏记在界面状态里；页面本身由 WebView 的状态恢复。
            // 只把「当时在用的那台电脑」补登记给内核（404 转兜底、「重新加载」要用它）。
            if (screen == AppScreen.WEB) {
                mostRecentlyUsedComputer()?.let { model.adoptRestoredLink(it.link) }
            }
            return
        }
        // 有保存的界面状态、但 WebView 的状态恢复不了（状态残缺、WebView 不收）：
        // 不能停在空白页，照第一次打开处理——往下走 selectStartupDestination 那条路。
        when (val destination = selectStartupDestination(computers)) {
            is StartupDestination.OpenTheOnlyComputer -> openComputer(destination.computer)
            StartupDestination.ComputerList -> screen = AppScreen.LIST
        }
    }

    /** 打开一台电脑：链接原样交给内核加载，记一次最后使用。 */
    private fun openComputer(computer: Computer) {
        model.openLink(computer.link)
        screen = AppScreen.WEB
        lifecycleScope.launch { store.markUsed(computer.sid, System.currentTimeMillis()) }
    }

    /**
     * 处理「打开链接」「分享」带进来的一条文本：是电脑就归并进列表再打开；
     * 是免扫码入口就直接打开；是旧版链接或不合式就带到添加页，把原因说给用户听。
     */
    private fun handleIncomingLink(link: String) {
        when (val checked = RemoteLink.check(link)) {
            is LinkCheck.Computer -> lifecycleScope.launch {
                val computer = store.upsert(link, checked, System.currentTimeMillis())
                openComputer(computer)
            }

            LinkCheck.Entry -> {
                model.openEntryLink(link)
                screen = AppScreen.WEB
            }

            LinkCheck.Legacy, LinkCheck.Invalid -> {
                addScreenText = link
                screen = AppScreen.ADD
            }
        }
    }

    /** 从意图里取链接：ACTION_VIEW 直接取数据，ACTION_SEND（text/plain）从分享文本里找。
     *  超过 [MAX_INCOMING_LINK_CHARS] 的（远大于真实链接）整个不收。 */
    private fun extractLinkFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.trim()?.takeIf { it.isNotEmpty() }
            Intent.ACTION_SEND ->
                if (intent.type != "text/plain") {
                    null
                } else {
                    RemoteLink.findInText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                }

            else -> null
        }?.takeIf { it.length <= MAX_INCOMING_LINK_CHARS }
    }

    /** 最近用过的一台电脑（按最后使用时间，其次按添加时间）。一台都没有时为 null。 */
    private fun mostRecentlyUsedComputer(): Computer? =
        computers.maxByOrNull { if (it.lastUsedAt > 0) it.lastUsedAt else it.addedAt }

    // ------------------------------------------------------------------ 设置与更多

    private fun onOpenInBrowserRequested() {
        if (!model.openCurrentPageInBrowser()) {
            Toast.makeText(this, R.string.web_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /** 「复制当前链接」：用户已在列表页确认过，这里只管复制。Android 13 起系统自己会提示已复制。 */
    private fun onCopyLinkRequested() {
        val link = model.currentComputerLink()
        if (link == null) {
            Toast.makeText(this, R.string.more_no_link_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, R.string.more_no_link_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), link))
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.more_link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 「退出登录」（方案第 7 条）：清掉 WebView 的全部存储和 Cookie（等同页面的「切换账号」），
     * 然后重新加载当前入口，让页面回到登录页。已添加的电脑不受影响。
     * 用户已在列表页的确认框里二次确认过。
     */
    private fun onSignOutRequested() {
        WebStorage.getInstance().deleteAllData()
        // Cookie 的移除是异步的：等它回调、落盘之后再重载当前入口，免得新页面赶在 Cookie 清掉之前加载出来，
        // 也免得进程恰在这中间被杀时清理没落盘（登录态本体在 localStorage，deleteAllData 是同步的，不受影响）。
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            runOnUiThread {
                cookies.flush()
                if (model.activeEntry != null) {
                    model.retry()
                }
            }
        }
        Toast.makeText(this, R.string.more_signed_out, Toast.LENGTH_SHORT).show()
    }

    /** 「读取剪贴板」确认之后：读剪贴板的纯文本。读不到返回 null。 */
    private fun readClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return try {
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: RuntimeException) {
            null
        }
    }

    // ------------------------------------------------------------------ 内核要用到的、需要 Activity 的几件事

    override fun openInBrowser(url: String): Boolean {
        return try {
            // 让浏览器开在自己的任务里。不带这个标志的话它会压在本 App 的任务上，下次从桌面点回本 App，
            // 看到的是浏览器而不是遥控页。
            startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            // 设备上没有能打开网页的浏览器。
            false
        } catch (e: SecurityException) {
            // 接住它的浏览器不让外部启动。
            false
        }
    }

    override fun showFileChooser(intent: Intent): Boolean {
        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // 设备上没有文件选择器。
            false
        }
    }

    /** 「保存副本」：弹系统保存框。内核会在弹不成时提示「暂不支持保存」。 */
    override fun launchSavePicker(request: SaveRequest): Boolean {
        return try {
            saveDocumentLauncher.launch(request.name)
            true
        } catch (e: ActivityNotFoundException) {
            // 设备上没有能接 ACTION_CREATE_DOCUMENT 的文档提供方。
            false
        }
    }

    // 返回键要「回到电脑列表」（docs/ARCHITECTURE.md 第 7 节）：列表盖在 WebView 之上，不另建 WebView。
    override fun returnToComputerList() {
        screen = AppScreen.LIST
    }

    /** 返回键「回桌面」（第 7 节表：读页面属性的脚本没有返回结果且不能后退）：把任务退到后台。 */
    override fun returnToDesktop() {
        moveTaskToBack(true)
    }

    private companion object {
        /** 进程被杀前是不是停在网页那一屏。 */
        const val STATE_SHOW_WEB = "app.showWeb"

        /**
         * 意图带进来的链接最多收这么长（字符数）。真实的电脑链接远小于这个数；
         * 恶意 App 可以借 ACTION_SEND 塞近 1MB 的伪链接进来，超长的整个不收，免得写进添加页的状态和存储。
         */
        const val MAX_INCOMING_LINK_CHARS = 8_192
    }
}
