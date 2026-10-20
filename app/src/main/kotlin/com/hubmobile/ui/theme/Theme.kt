package com.hubmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌 —— 逐条对齐 app/src/main/assets/web/css/app.css 的 :root 与
 * html[data-theme="dark"] 两段。
 *
 * 原项目把整套配色做成 CSS 变量，靠切换 <html data-theme> 换肤；
 * 这里用 CompositionLocal 做同一件事：一份令牌、两套取值、全 App 生效。
 */

// ---------- 亮色（GitHub Primer Light）----------
private val LightTokens = GithupTokens(
    bg = Color(0xFFFFFFFF),
    canvas = Color(0xFFF6F8FA),
    canvasInset = Color(0xFFF6F8FA),
    border = Color(0xFFD0D7DE),
    borderMuted = Color(0xFFD8DEE4),
    fg = Color(0xFF1F2328),
    fgMuted = Color(0xFF59636E),
    fgSubtle = Color(0xFF818B98),
    accent = Color(0xFF0969DA),
    accentEmphasis = Color(0xFF0969DA),
    accentSubtle = Color(0xFFDDF4FF),
    btnBg = Color(0xFFF6F8FA),
    btnBgHover = Color(0xFFEEF1F4),
    btnBorder = Color(0x26000000),
    btnPrimaryBg = Color(0xFF1F883D),
    btnPrimaryHover = Color(0xFF1A7F37),
    btnPrimaryFg = Color(0xFFFFFFFF),
    btnDangerFg = Color(0xFFCF222E),
    success = Color(0xFF1A7F37),
    successEmphasis = Color(0xFF1F883D),
    successSubtle = Color(0xFFDAFBE1),
    danger = Color(0xFFCF222E),
    dangerSubtle = Color(0xFFFFEBE9),
    attention = Color(0xFF9A6700),
    attentionSubtle = Color(0xFFFFF8C5),
    done = Color(0xFF8250DF),
    doneSubtle = Color(0xFFFBEFff),
    neutralSubtle = Color(0xFFEAEEF2),
    neutralEmphasis = Color(0xFF6E7781),
    skeleton = Color(0xFFEAEEF2),
    codeBg = Color(0xFFF6F8FA),
    diffAddBg = Color(0xFFE6FFEC),
    diffAddGutter = Color(0xFFCCFFD8),
    diffDelBg = Color(0xFFFFEBE9),
    diffDelGutter = Color(0xFFFFD7D5),
    diffHunkBg = Color(0xFFDDF4FF),
    // 底栏：面本身几乎不参与「显不显眼」，玻璃感来自边缘高光 + 背景透出
    navSurface = Color(0x80E9EDF5),          // rgba(233,237,245,.5)
    navRingBg = Color(0x99FCFCFE),           // rgba(252,252,254,.6)
    navAccent = Color(0xFF6A5CAE),
    navInnerTop = Color(0x57FFFFFF),         // 内部亮线：自上而下渐弱（玻璃厚度）
    navInnerMid = Color(0x0DFFFFFF),
    navInnerBottom = Color(0x08FFFFFF),      // 底部很淡的深色罩，给文字兜底
    navShadowAmbient = Color(0x3D1F2328),
    navShadowKey = Color(0x211F2328),
    overlayShadow = Color(0x1F1F2328)
)

// ---------- 暗色（GitHub Primer Dark）----------
private val DarkTokens = GithupTokens(
    bg = Color(0xFF0D1117),
    canvas = Color(0xFF010409),
    canvasInset = Color(0xFF010409),
    border = Color(0xFF30363D),
    borderMuted = Color(0xFF21262D),
    fg = Color(0xFFE6EDF3),
    fgMuted = Color(0xFF8B949E),
    fgSubtle = Color(0xFF6E7681),
    accent = Color(0xFF4493F8),
    accentEmphasis = Color(0xFF1F6FEB),
    accentSubtle = Color(0x26388BFD),
    btnBg = Color(0xFF21262D),
    btnBgHover = Color(0xFF30363D),
    btnBorder = Color(0x1AF0F6FC),
    btnPrimaryBg = Color(0xFF238636),
    btnPrimaryHover = Color(0xFF2EA043),
    btnPrimaryFg = Color(0xFFFFFFFF),
    btnDangerFg = Color(0xFFF85149),
    success = Color(0xFF3FB950),
    successEmphasis = Color(0xFF238636),
    successSubtle = Color(0x262EA043),
    danger = Color(0xFFF85149),
    dangerSubtle = Color(0x26F85149),
    attention = Color(0xFFD29922),
    attentionSubtle = Color(0x26D29922),
    done = Color(0xFFAB7DF8),
    doneSubtle = Color(0x26A371F7),
    neutralSubtle = Color(0x666E7681),
    neutralEmphasis = Color(0xFF6E7681),
    skeleton = Color(0xFF21262D),
    codeBg = Color(0xFF161B22),
    diffAddBg = Color(0x262EA043),
    diffAddGutter = Color(0x402EA043),
    diffDelBg = Color(0x26F85149),
    diffDelGutter = Color(0x40F85149),
    diffHunkBg = Color(0x26388BFD),
    // 深色下玻璃面取带蓝紫的深色（色相提供材质感），重点放在高光
    navSurface = Color(0x801A1926),          // rgba(26,25,38,.5)
    navRingBg = Color(0x17FFFFFF),           // rgba(255,255,255,.09)
    navAccent = Color(0xFFA99CE4),
    navInnerTop = Color(0x1CFFFFFF),
    navInnerMid = Color(0x03FFFFFF),
    navInnerBottom = Color(0x29000000),
    navShadowAmbient = Color(0xB8000000),
    navShadowKey = Color(0x8C000000),
    overlayShadow = Color(0x99010409)
)

/** 一整套配色令牌 */
data class GithupTokens(
    val bg: Color,
    val canvas: Color,
    val canvasInset: Color,
    val border: Color,
    val borderMuted: Color,
    val fg: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    val accent: Color,
    val accentEmphasis: Color,
    val accentSubtle: Color,
    val btnBg: Color,
    val btnBgHover: Color,
    val btnBorder: Color,
    val btnPrimaryBg: Color,
    val btnPrimaryHover: Color,
    val btnPrimaryFg: Color,
    val btnDangerFg: Color,
    val success: Color,
    val successEmphasis: Color,
    val successSubtle: Color,
    val danger: Color,
    val dangerSubtle: Color,
    val attention: Color,
    val attentionSubtle: Color,
    val done: Color,
    val doneSubtle: Color,
    val neutralSubtle: Color,
    val neutralEmphasis: Color,
    val skeleton: Color,
    val codeBg: Color,
    val diffAddBg: Color,
    val diffAddGutter: Color,
    val diffDelBg: Color,
    val diffDelGutter: Color,
    val diffHunkBg: Color,
    val navSurface: Color,
    val navRingBg: Color,
    val navAccent: Color,
    val navInnerTop: Color,
    val navInnerMid: Color,
    val navInnerBottom: Color,
    val navShadowAmbient: Color,
    val navShadowKey: Color,
    val overlayShadow: Color
)

/**
 * 度量令牌 —— 对齐 CSS 里的 --appbar-h / --tabbar-h / --tabbar-lift 等。
 * 胶囊本体 64 = 内衬 4 + 内容 56 + 内衬 4。
 */
object GithupDimens {
    val appbarHeight = 52.dp
    val tabbarHeight = 64.dp
    val tabbarInnerPad = 4.dp
    val tabbarContent = 56.dp
    val tabbarLift = 14.dp      // 胶囊离屏幕底边的抬升
    val tabbarGutter = 16.dp    // 左右留白
    val tabbarMax = 520.dp      // 平板/宽屏下不再整条拉通
    val radius = 8.dp
    val radiusSm = 6.dp
    val iconBtn = 40.dp         // 触控区：52px 顶栏放得下 + 手指点得到
    val fabSize = 52.dp
    val fabGap = 12.dp
    val pageGutter = 12.dp
    val rowPadH = 14.dp
    val rowPadV = 12.dp
    val tabIcon = 22.dp
    val indicatorInset = 4.dp
    val ringInset = 6.dp        // 外圈玻璃环向外扩出的量
}

/** 字号 —— 对齐 CSS：正文 14、标题 16/600、元信息 12、底栏标签 10 */
object GithupType {
    val body = 14.sp
    val title = 16.sp
    val meta = 12.sp
    val chip = 12.sp
    val tabLabel = 10.sp
    val section = 12.sp
    val mono = 13.sp
    val emptyTitle = 16.sp
    val emptyDesc = 13.sp
}

internal val LocalGithupTokens = staticCompositionLocalOf { LightTokens }

@Composable
fun GithupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val tokens = if (darkTheme) DarkTokens else LightTokens
    androidx.compose.runtime.CompositionLocalProvider(LocalGithupTokens provides tokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) {
                androidx.compose.material3.darkColorScheme(
                    primary = tokens.accent,
                    background = tokens.canvas,
                    surface = tokens.bg,
                    onSurface = tokens.fg
                )
            } else {
                androidx.compose.material3.lightColorScheme(
                    primary = tokens.accent,
                    background = tokens.canvas,
                    surface = tokens.bg,
                    onSurface = tokens.fg
                )
            },
            content = content
        )
    }
}

/** 在任意 Composable 里取当前令牌，写法同 CSS 的 var(--xx) */
val Githup: GithupTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalGithupTokens.current
