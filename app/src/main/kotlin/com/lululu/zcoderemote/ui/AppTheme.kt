package com.lululu.zcoderemote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App 自己的主题（M3）：默认深色，与手机页面的深色风格一致——近黑的背景、灰色的次要文字、
 * 柔和的浅蓝作强调色（和启动图标的底色 #161616 同一族）。
 *
 * 不跟随系统的深浅色：网页那头是固定的深色页面，原生界面跟着系统变浅色会把 WebView
 * 衬在一块浅色中间，观感是断开的。M2 阶段只有网页看不出来；M3 有了原生界面就定了死。
 * 系统栏的图标颜色在 MainActivity 里用 SystemBarStyle.dark 钉成浅色，和这里配套。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF0A2A5A),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFA6A6A6),
    surfaceContainer = Color(0xFF1B1B1B),
    surfaceContainerHigh = Color(0xFF242424),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601C10),
    outline = Color(0xFF3D3D3D),
)

@Composable
fun ZCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
