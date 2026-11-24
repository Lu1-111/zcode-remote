package com.lululu.zcoderemote.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lululu.zcoderemote.R
import kotlinx.coroutines.delay

/** 软键盘停稳之后再等多久让内核把输入框滚进可见区域。系统的键盘动画是 300ms 左右，这里留了余量。 */
private const val IME_SETTLE_DELAY_MS = 200L

/**
 * WebView 的 Compose 外壳（方案第 1、15 条）：挂载内核里那个 WebView，留出系统栏的空白，盖上原生断网页。
 *
 * WebView 实例在 [model] 里。界面重建时这里重新执行，取回的还是同一个实例，页面不重新加载。
 * 接返回键用 [BackHandler]（走 OnBackPressedDispatcher）。清单里写明了 enableOnBackInvokedCallback：
 * 返回键经 OnBackInvokedCallback 交给 App，系统不再派发 KEYCODE_BACK，直接覆写 onBackPressed 或 onKeyDown 接不到。
 * 不写的话 Android 13 到 15 走的是旧的按键派发，和新系统上不是同一条路（docs/ARCHITECTURE.md 第 7 节）。
 */
@Composable
fun RemoteWebView(
    model: RemoteWebViewModel,
    modifier: Modifier = Modifier,
    backHandlingEnabled: Boolean = true,
) {
    val epoch by model.webViewEpoch.collectAsState()
    val errorPageVisible by model.errorPageVisible.collectAsState()
    val retrying by model.retrying.collectAsState()

    // 系统栏、刘海和软键盘要让开的地方（方案第 15 条 ④）。铺到系统栏下面之后窗口不会自己缩，得自己留白；
    // 页面的 viewport 没有 viewport-fit=cover，也是这个预期。取三者的并集，哪一边大用哪一边：
    // 横屏时三键导航在侧边，软键盘弹出时键盘比导航栏高。
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)

    // M3 起电脑列表等原生界面盖在 WebView 之上（同一个 WebView，不重建）。盖着的时候返回键归原生界面，
    // 用 enabled 关掉这一层，返回键交给界面上后注册的那个 BackHandler。
    BackHandler(enabled = backHandlingEnabled) { model.handleBackPress() }

    // 软键盘弹出有个动画，高度一帧一帧地变。WebView 跟着变矮之后，页面不会自己把正在输入的框滚回可见区域，
    // 所以等键盘停稳了让内核滚一次。键盘高度每变一次都重来，最后一次停稳之后才会执行。
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            delay(IME_SETTLE_DELAY_MS)
            model.revealFocusedInput()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(insets),
    ) {
        // 内核换了 WebView 实例（渲染进程挂掉之后）时 epoch 变化：旧的被撤下（onRelease），再挂上新的。
        key(epoch) {
            AndroidView(
                factory = { model.takeWebViewForDisplay() },
                modifier = Modifier.fillMaxSize(),
                onRelease = { model.onWebViewReleased(it) },
            )
        }
        // 断网页盖在 WebView 上。内核这时已经把 WebView 藏起来了，它自己那份错误页不会露出来。
        if (errorPageVisible) {
            RemoteWebErrorPage(retrying = retrying, onRetry = { model.retry() })
        }
    }
}

/**
 * 原生断网页（方案第 15 条 ⑤）。
 *
 * 文案按方案 risks 第 3 条写：主框架加载失败时，用户最该去确认的是电脑那头，不要把它说成登录问题。
 * 页面上不出现任何地址。
 *
 * 按了「重试」之后这一页先不撤（WebView 要等新页面真正加载成功才放出来，见 [ErrorPageState]），
 * 所以要让人看得出重试已经在进行：按钮禁用，转个圈。
 */
@Composable
internal fun RemoteWebErrorPage(retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.web_error_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.web_error_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, enabled = !retrying) {
            Text(text = stringResource(R.string.web_error_retry))
        }
        if (retrying) {
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.web_error_retrying),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
