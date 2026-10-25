package com.hubmobile.app.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 路线 B 的主题。
 *
 * 配色直接抄 assets/web/css 里网页版用的 GitHub 色板，
 * 目的是「迁移过程中看不出换过皮」—— 同一个页面昨天是网页、
 * 今天是 Compose，颜色必须一致，否则用户会觉得 App 变了样。
 *
 * 只做了深色一套的精细对齐；浅色沿用 Material3 默认，
 * 等真正有浅色需求再逐项调。
 */

// GitHub 深色模式官方色板
object HubColors {
    val Canvas = Color(0xFF0D1117)      // 页面底色
    val Surface = Color(0xFF161B22)     // 卡片 / 顶栏
    val SurfaceHi = Color(0xFF21262D)   // 悬浮 / 输入框
    val Border = Color(0xFF30363D)      // 描边
    val Text = Color(0xFFE6EDF3)        // 主文字
    val Muted = Color(0xFF8B949E)       // 次要文字
    val Accent = Color(0xFF58A6FF)      // 链接 / 主色
    val Success = Color(0xFF3FB950)     // 绿（新增 / 通过）
    val Danger = Color(0xFFF85149)      // 红（删除 / 失败）
    val Warn = Color(0xFFD29922)        // 黄（提醒）
    val Done = Color(0xFFA371F7)        // 紫（完成 / 合并）
}

private val DarkScheme = darkColorScheme(
    primary = HubColors.Accent,
    onPrimary = Color(0xFF0D1117),
    primaryContainer = HubColors.SurfaceHi,
    onPrimaryContainer = HubColors.Text,
    secondary = HubColors.Muted,
    onSecondary = HubColors.Text,
    background = HubColors.Canvas,
    onBackground = HubColors.Text,
    surface = HubColors.Surface,
    onSurface = HubColors.Text,
    surfaceVariant = HubColors.SurfaceHi,
    onSurfaceVariant = HubColors.Muted,
    outline = HubColors.Border,
    error = HubColors.Danger,
    onError = Color.White
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0969DA),
    secondary = Color(0xFF59636E),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F8FA),
    onSurface = Color(0xFF1F2328),
    outline = Color(0xFFD1D9E0)
)

@Composable
fun HubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
