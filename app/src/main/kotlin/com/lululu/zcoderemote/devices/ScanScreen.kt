package com.lululu.zcoderemote.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.lululu.zcoderemote.R

/**
 * 添加电脑的「扫码」方式（方案第 10 条）：App 内相机扫码。
 *
 * - 用 journeyapps 的 zxing-android-embedded，把扫码视图**内嵌**在这一页里：
 *   不用它自带的 CaptureActivity，全应用仍然只有一个 Activity（docs/ARCHITECTURE.md 第 3 节第 1 条）。
 * - 只解二维码（QR_CODE）。
 * - CAMERA 权限用到时才申请；被拒绝时引导改用粘贴。
 * - 识别结果按第 10 节校验：旧版链接（带 hash/t）给「旧版链接，请在电脑端重新打开远控弹窗扫码」，
 *   不合式的给提示，都继续扫；免扫码入口的二维码可以打开但不保存成电脑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onScannedComputer: (link: String, checked: LinkCheck.Computer) -> Unit,
    onScannedEntry: (link: String) -> Unit,
    onSwitchToPaste: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val legacyText = stringResource(R.string.link_legacy)
    val invalidText = stringResource(R.string.scan_invalid)

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var askedOnce by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 连续解码模式下同一个二维码会一帧一帧地报同一个结果，这里只取第一次，隔几秒才允许再报。
    var lastHandled by remember { mutableStateOf<String?>(null) }
    var lastHandledAt by remember { mutableLongStateOf(0L) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (!granted) message = null
    }

    BackHandler(onBack = onBack)

    // 权限跟着界面生命周期复查：用户可能去系统设置里授/回收权限，也可能从权限弹窗回来，
    // 每次回到前台都按系统里的真实状态刷新，别卡在旧的判断上。
    LifecycleResumeEffect(Unit) {
        cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    // 进入这一页才申请相机权限（方案第 10 条：用到时才申请）。同一次停留只主动问一次。
    LaunchedEffect(cameraGranted) {
        if (!cameraGranted && !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary,
                ),
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (cameraGranted) {
                ScannerArea(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    onResult = { value ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (value == lastHandled && now - lastHandledAt < 4000L) return@ScannerArea
                        lastHandled = value
                        lastHandledAt = now
                        when (val checked = RemoteLink.check(value)) {
                            is LinkCheck.Computer -> onScannedComputer(value, checked)
                            LinkCheck.Entry -> onScannedEntry(value)
                            LinkCheck.Legacy -> message = legacyText
                            LinkCheck.Invalid -> message = invalidText
                        }
                    },
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.scan_permission_needed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(stringResource(R.string.scan_grant_permission))
                        }
                        TextButton(onClick = onSwitchToPaste) {
                            Text(stringResource(R.string.scan_switch_to_paste))
                        }
                    }
                }
            }

            // 底部：提示与扫码结果的说服信息（这里不显示任何链接）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message ?: stringResource(R.string.scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 内嵌的扫码视图。相机随生命周期开关：离开这一页就暂停，回来再继续。 */
@Composable
private fun ScannerArea(
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            DecoratedBarcodeView(context).apply {
                setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
                setStatusText("")
                decodeContinuous(
                    BarcodeCallback { result ->
                        val value = result?.text ?: return@BarcodeCallback
                        onResult(value)
                    },
                )
                viewRef.value = this
            }
        },
        onRelease = { it.pause() },
    )

    // 视图建好或权限变化时开一次相机。
    val view = viewRef.value
    LaunchedEffect(view) {
        view?.resume()
    }

    // 跟着界面生命周期走：后台就停相机，回前台再开。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val scanner = viewRef.value
            when (event) {
                Lifecycle.Event.ON_RESUME -> scanner?.resume()
                Lifecycle.Event.ON_PAUSE -> scanner?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewRef.value?.pause()
        }
    }
}
