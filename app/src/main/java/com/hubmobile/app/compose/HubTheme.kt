package com.hubmobile.app.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 配色 —— 与 assets/web/css/app.css 里的 CSS 变量逐项对齐。
 *
 * 之前这一版是"凭印象挑的 GitHub 色"，跟网页版对不上（比如页面底色用了
 * #0d1117，而网页版 --canvas 其实是 #010409；accent 用了 #58a6ff，
 * 网页版 --accent 是 #4493f8）。现在改成照抄 CSS 变量，浅色深色两套都对齐。
 *
 * 命名沿用 CSS 变量的含义，同时保留 HubColors 旧字段名以免打断已写好的页面。
 */

@Immutable
object HubColors {
    /* ---- 深色（html[data-theme="dark"]）---- */
    val CanvasDark = Color(0xFF010409)      // --canvas 页面底色
    val BgDark = Color(0xFF0D1117)          // --bg 卡片 / 列表底色
    val BgHiDark = Color(0xFF21262D)        // --btn-bg 按钮 / 输入框
    val BorderDark = Color(0xFF30363D)      // --border
    val BorderMutedDark = Color(0xFF21262D) // --border-muted
    val FgDark = Color(0xFFE6EDF3)          // --fg 主文字
    val FgMutedDark = Color(0xFF8B949E)     // --fg-muted
    val FgSubtleDark = Color(0xFF6E7681)    // --fg-subtle
    val AccentDark = Color(0xFF4493F8)      // --accent 链接
    val AccentEmphasisDark = Color(0xFF1F6FEB) // --accent-emphasis 按钮
    val AccentSubtleDark = Color(0x26568BFD)   // --accent-subtle rgba(56,139,253,.15)
    val PrimaryDark = Color(0xFF238636)     // --btn-primary-bg
    val SuccessDark = Color(0xFF3FB950)
    val DangerDark = Color(0xFFF85149)
    val AttentionDark = Color(0xFFD29922)
    val DoneDark = Color(0xFFAB7DF8)
    val NeutralSubtleDark = Color(0x666E7681)  // --neutral-subtle rgba(110,118,129,.4)
    val HeaderDark = Color(0xFF010409)      // --header-bg
    val TabbarDark = Color(0xFF010409)      // --tabbar-bg
    val CodeBgDark = Color(0xFF161B22)

    /* ---- 浅色（:root）---- */
    val CanvasLight = Color(0xFFF6F8FA)
    val BgLight = Color(0xFFFFFFFF)
    val BgHiLight = Color(0xFFF6F8FA)
    val BorderLight = Color(0xFFD0D7DE)
    val BorderMutedLight = Color(0xFFD8DEE4)
    val FgLight = Color(0xFF1F2328)
    val FgMutedLight = Color(0xFF59636E)
    val FgSubtleLight = Color(0xFF818B98)
    val AccentLight = Color(0xFF0969DA)
    val AccentEmphasisLight = Color(0xFF0969DA)
    val AccentSubtleLight = Color(0xFFDDF4FF)
    val PrimaryLight = Color(0xFF1F883D)
    val SuccessLight = Color(0xFF1A7F37)
    val DangerLight = Color(0xFFCF222E)
    val AttentionLight = Color(0xFF9A6700)
    val DoneLight = Color(0xFF8250DF)
    val NeutralSubtleLight = Color(0xFFEAEEF2)
    val HeaderLight = Color(0xFFFFFFFF)
    val TabbarLight = Color(0xFFFFFFFF)
    val CodeBgLight = Color(0xFFF6F8FA)

    /* ---- 旧字段名（兼容已写好的页面）---- */
    val Canvas = CanvasDark
    val Surface = BgDark
    val SurfaceHi = BgHiDark
    val Border = BorderDark
    val Text = FgDark
    val Muted = FgMutedDark
    val Accent = AccentDark
    val Success = SuccessDark
    val Danger = DangerDark
    val Warn = AttentionDark
    val Done = DoneDark
    val FgMuted = FgMutedDark
}

/** 当前生效的配色，随深色/浅色切换 */
@Immutable
class HubColorSet(
    val canvas: Color,
    val bg: Color,
    val bgHi: Color,
    val border: Color,
    val borderMuted: Color,
    val fg: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val accent: Color,
    val accentEmphasis: Color,
    val accentSubtle: Color,
    val primary: Color,
    val success: Color,
    val danger: Color,
    val attention: Color,
    val done: Color,
    val neutralSubtle: Color,
    val header: Color,
    val tabbar: Color,
    val codeBg: Color
)

private val DarkSet = HubColorSet(
    canvas = HubColors.CanvasDark, bg = HubColors.BgDark, bgHi = HubColors.BgHiDark,
    border = HubColors.BorderDark, borderMuted = HubColors.BorderMutedDark,
    fg = HubColors.FgDark, fgMuted = HubColors.FgMutedDark, fgSubtle = HubColors.FgSubtleDark,
    accent = HubColors.AccentDark, accentEmphasis = HubColors.AccentEmphasisDark,
    accentSubtle = HubColors.AccentSubtleDark, primary = HubColors.PrimaryDark,
    success = HubColors.SuccessDark, danger = HubColors.DangerDark,
    attention = HubColors.AttentionDark, done = HubColors.DoneDark,
    neutralSubtle = HubColors.NeutralSubtleDark, header = HubColors.HeaderDark,
    tabbar = HubColors.TabbarDark, codeBg = HubColors.CodeBgDark
)

private val LightSet = HubColorSet(
    canvas = HubColors.CanvasLight, bg = HubColors.BgLight, bgHi = HubColors.BgHiLight,
    border = HubColors.BorderLight, borderMuted = HubColors.BorderMutedLight,
    fg = HubColors.FgLight, fgMuted = HubColors.FgMutedLight, fgSubtle = HubColors.FgSubtleLight,
    accent = HubColors.AccentLight, accentEmphasis = HubColors.AccentEmphasisLight,
    accentSubtle = HubColors.AccentSubtleLight, primary = HubColors.PrimaryLight,
    success = HubColors.SuccessLight, danger = HubColors.DangerLight,
    attention = HubColors.AttentionLight, done = HubColors.DoneLight,
    neutralSubtle = HubColors.NeutralSubtleLight, header = HubColors.HeaderLight,
    tabbar = HubColors.TabbarLight, codeBg = HubColors.CodeBgLight
)

val LocalHubColors = staticCompositionLocalOf { DarkSet }

private val DarkScheme = darkColorScheme(
    primary = HubColors.AccentDark,
    onPrimary = Color(0xFF010409),
    secondary = HubColors.FgMutedDark,
    background = HubColors.CanvasDark,
    surface = HubColors.BgDark,
    surfaceVariant = HubColors.BgHiDark,
    onSurface = HubColors.FgDark,
    onSurfaceVariant = HubColors.FgMutedDark,
    outline = HubColors.BorderDark,
    error = HubColors.DangerDark
)

private val LightScheme = lightColorScheme(
    primary = HubColors.AccentLight,
    secondary = HubColors.FgMutedLight,
    background = HubColors.CanvasLight,
    surface = HubColors.BgLight,
    surfaceVariant = HubColors.BgHiLight,
    onSurface = HubColors.FgLight,
    onSurfaceVariant = HubColors.FgMutedLight,
    outline = HubColors.BorderLight,
    error = HubColors.DangerLight
)

/**
 * 网页版 data-theme 是 light / dark 两套，这里跟随系统。
 * （网页版还有个用户在设置里手动切换的开关，原生版暂未做该设置项。）
 */
@Composable
fun HubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val set = if (darkTheme) DarkSet else LightSet
    androidx.compose.runtime.CompositionLocalProvider(LocalHubColors provides set) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            content = content
        )
    }
}
