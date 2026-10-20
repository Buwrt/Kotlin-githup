package com.hubmobile.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

data class TabSpec(
    val key: String,
    val label: String,
    val icon: ImageVector,
    /** 选中时的填充版图标（可选）。Octicons 每个图标都有 outline / fill 两版 */
    val iconSelected: ImageVector? = null
)

private val CapsuleShape: Shape get() = RoundedCornerShape(percent = 50)

/**
 * 悬浮胶囊底栏 —— 复刻 index.html 的 #tabbar + .tab-ind。
 *
 * 几何（与 CSS 变量一一对应）：
 *   本体 64dp = 内衬 4 + 内容 56 + 内衬 4，左右留白 16，离屏幕底边抬升 14，
 *   宽屏时最大 520 不再拉通。
 *
 * 指示器是**两层**：
 *   内层本体（--nav-indicator: transparent，刻意留空）
 *   外层玻璃环（::before，inset -6px，比上下边界都凸出）
 * 原项目踩过的坑：只画内层那个圆，看起来就只是「底栏上贴了个浅色圆角块」，
 * 补上外环之后玻璃感才立得住。
 *
 * 位移过渡 .44s cubic-bezier(.2,.9,.2,1)，与原 CSS 完全一致。
 */
@Composable
fun GithupTabBar(
    tabs: List<TabSpec>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badge: Int = 0,
    style: NavStyle = NavStyle.Glass
) {
    val T = Githup
    val D = GithupDimens
    val count = tabs.size

    val glass = style == NavStyle.Glass
    // 朴素样式（classic）：贴底实心栏 —— 三样量全部归零，不做悬浮
    val lift = if (glass) D.tabbarLift else 0.dp
    val gutter = if (glass) D.tabbarGutter else 0.dp
    val radius: Shape = if (glass) CapsuleShape else RoundedCornerShape(0.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = lift)
            .height(D.tabbarHeight)
            .then(
                if (glass) {
                    Modifier.liquidGlass(
                        shape = radius,
                        surface = T.navSurface,
                        innerTop = T.navInnerTop,
                        innerMid = T.navInnerMid,
                        innerBottom = T.navInnerBottom,
                        highlight = Color.White,
                        darkRing = Color(0xFF1F2328),
                        elevation = 20.dp,
                        ambientShadow = T.navShadowAmbient,
                        keyShadow = T.navShadowKey
                    )
                } else {
                    Modifier
                        .drawBehind { drawRect(T.bg) }
                }
            )
    ) {
        val totalWidth = maxWidth
        val indWidth = (totalWidth - D.indicatorInset * 2) / count

        // ---- 指示器（两层：本体透明 + 外圈玻璃环）----
        if (glass) {
            val indOffset by animateDpAsState(
                targetValue = D.indicatorInset + indWidth * selectedIndex,
                animationSpec = tween(440, easing = CubicBezierEasing(0.2f, 0.9f, 0.2f, 1f)),
                label = "tabInd"
            )
            Box(
                modifier = Modifier
                    .offset(x = indOffset, y = D.indicatorInset)
                    .width(indWidth)
                    .fillMaxHeight()
                    .padding(0.dp)
            ) {
                // 外层玻璃环：向外扩 6dp，上下都顶出长条边界
                Box(
                    modifier = Modifier
                        .offset(x = -D.ringInset, y = -D.ringInset)
                        .width(indWidth + D.ringInset * 2)
                        .height(D.tabbarContent + D.ringInset * 2)
                        .glassRing(
                            shape = CapsuleShape,
                            ringBg = T.navRingBg,
                            highlight = Color.White
                        )
                )
            }
        }

        // ---- tab 按钮 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(all = D.tabbarInnerPad)
                .fillMaxHeight()
        ) {
            tabs.forEachIndexed { i, tab ->
                TabItem(
                    spec = tab,
                    selected = i == selectedIndex,
                    badge = if (tab.key == "notifications") badge else 0,
                    onSelect = { onSelect(i) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    spec: TabSpec,
    selected: Boolean,
    badge: Int,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val T = Githup
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 按压反馈：原项目用 scale 近似 violet_Box 的弹簧挤压
    val iconScale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "ico")
    val labelScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "lbl")
    // 选中态：图标上移 1dp
    val lift by animateDpAsState(if (selected) (-1).dp else 0.dp, label = "lift")

    val tint = if (selected) T.navAccent else T.fgMuted

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,   // 玻璃面上不要水波纹，用 scale 表达按压
                role = Role.Tab,
                onClick = onSelect
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.TopCenter) {
                Icon(
                    imageVector = (if (selected) spec.iconSelected ?: spec.icon else spec.icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(GithupDimens.tabIcon)
                        .scale(iconScale)
                        .offset(y = lift)
                )
                if (badge > 0) {
                    Badge(count = badge)
                }
            }
            Text(
                text = spec.label,
                color = tint,
                fontSize = GithupType.tabLabel,
                lineHeight = GithupType.tabLabel,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .scale(labelScale)
                    .graphicsLayer { }
            )
        }
    }
}

/** 通知小红点：min 16dp 宽、16dp 高、圆角 8dp、10sp 白字 */
@Composable
private fun Badge(count: Int, modifier: Modifier = Modifier) {
    val T = Githup
    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .offset(x = 11.dp, y = (-3).dp)
            .height(16.dp)
            .width((16 + (text.length - 1) * 6).dp)
            .background(
                color = T.danger,
                shape = RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

enum class NavStyle { Glass, Classic }

/** 供 Scaffold 计算内容底部留白：胶囊总高 + 抬升 + 底栏离底距离 */
fun contentBottomPadding(lift: Dp = GithupDimens.tabbarLift): Dp =
    lift + GithupDimens.tabbarHeight + 8.dp
