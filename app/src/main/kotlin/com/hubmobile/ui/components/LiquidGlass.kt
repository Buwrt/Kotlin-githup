package com.hubmobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃。
 *
 * 原项目用 CSS backdrop-filter + 一段内联 SVG feTurbulence/feDisplacementMap 做
 * 「折射」，并在 app.css 里留了一段很长的踩坑记录，核心结论是：
 *
 *   玻璃感不来自「面的颜色」，而来自 ① 边缘的折射高光 ② 背景的透出。
 *   面上沿实测只比背景亮 6 左右，四边还有一圈由「玻璃厚度」造成的暗环，
 *   而且**只有顶部是亮的，左右两端其实是暗的** —— 四边一样亮的描边一定假。
 *
 * Compose 没有 backdrop-filter，这里按样板的三段结构用绘制还原：
 *   ① 半透明面（让背景透出，对应 --nav-surface）
 *   ② 内部自上而下的明暗渐变（玻璃厚度，对应 --nav-inner）
 *   ③ 外圈暗环 + 顶部内高光 + 底部弱反光 + 左右极轻内提亮（对应 --nav-liquid）
 *
 * 这么做在静态观感上与原版基本等价；真正的背景模糊需要把下层内容渲进
 * 纹理（Android 12+ 可用 RenderEffect），对底栏这种大面积玻璃性价比不高，
 * 这里不做，保持滚动与惯性滑动时的帧率。
 */
fun Modifier.liquidGlass(
    shape: Shape,
    surface: Color,
    innerTop: Color,
    innerMid: Color,
    innerBottom: Color,
    highlight: Color,
    darkRing: Color,
    elevation: Dp = 20.dp,
    ambientShadow: Color = Color(0x3D1F2328),
    keyShadow: Color = Color(0x211F2328),
    soft: Boolean = false
): Modifier {
    val scale = if (soft) 0.5f else 1f
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = ambientShadow,
            spotColor = keyShadow
        )
        .clip(shape)
        .drawWithCache {
            // ② 内部亮线：一块向下渐弱的白色 + 一层很淡的深色罩给文字兜底
            val innerBrush = Brush.verticalGradient(
                0.00f to innerTop,
                0.22f to innerMid,
                0.46f to Color.Transparent,
                1.00f to innerBottom
            )
            // ③ 顶部内高光：起于暗环内侧、向下衰减
            val topBrush = Brush.verticalGradient(
                0.00f to highlight.copy(alpha = 0f),
                0.05f to highlight.copy(alpha = 1f * scale),
                0.10f to highlight.copy(alpha = 0.85f * scale),
                0.26f to highlight.copy(alpha = 0.35f * scale),
                0.46f to Color.Transparent
            )
            // 底部一条更弱的反光
            val bottomBrush = Brush.verticalGradient(
                0.86f to Color.Transparent,
                0.97f to highlight.copy(alpha = 0.45f * scale),
                1.00f to Color.Transparent
            )
            // 左右两端：刻意压得很轻，比面还暗，绝不能变成描边
            val sideBrush = Brush.horizontalGradient(
                0.00f to highlight.copy(alpha = 0.5f * scale),
                0.06f to Color.Transparent,
                0.94f to Color.Transparent,
                1.00f to highlight.copy(alpha = 0.5f * scale)
            )
            onDrawBehind {
                // ① 面：半透明，背景从底下透出来
                drawRect(color = surface)
                drawRect(brush = innerBrush)
                drawRect(brush = topBrush)
                drawRect(brush = bottomBrush)
                drawRect(brush = sideBrush)
                // 外圈暗环：1px 实心暗线 + 一层软扩散，四边都要 —— 这是「厚度」
                drawRoundRect(
                    color = darkRing.copy(alpha = 0.10f),
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = cornerRadiusOf(this)
                )
                drawRoundRect(
                    color = darkRing.copy(alpha = 0.07f),
                    style = Stroke(width = 3.5.dp.toPx()),
                    cornerRadius = cornerRadiusOf(this)
                )
            }
        }
}

private fun cornerRadiusOf(scope: DrawScope): androidx.compose.ui.geometry.CornerRadius =
    androidx.compose.ui.geometry.CornerRadius(scope.size.minDimension / 2f)

/**
 * 玻璃外环 —— 选中指示器外面那一圈。
 * 原项目的结论：选中项是**两层**，外层一圈近白半透明玻璃环比长条更凸出，
 * 上下都顶出长条的边界；只画内层那个圆，看起来就只是「贴了个浅色圆角块」。
 */
fun Modifier.glassRing(
    shape: Shape,
    ringBg: Color,
    highlight: Color,
    soft: Boolean = false
): Modifier {
    val s = if (soft) 0.55f else 1f
    return this
        .clip(shape)
        .drawWithCache {
            val brush = Brush.verticalGradient(
                0.00f to highlight.copy(alpha = 0.95f * s),
                0.30f to highlight.copy(alpha = 0.35f * s),
                0.80f to Color.Transparent,
                1.00f to highlight.copy(alpha = 0.35f * s)
            )
            onDrawBehind {
                drawRect(color = ringBg)
                drawRect(brush = brush)
            }
        }
}

/** 顶部那条更弱的玻璃（顶栏用），力度减半 */
fun Modifier.liquidGlassSoft(
    shape: Shape,
    surface: Color,
    highlight: Color
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val brush = Brush.verticalGradient(
            0f to highlight.copy(alpha = 0.5f),
            0.5f to highlight.copy(alpha = 0.12f),
            1f to Color.Transparent
        )
        onDrawBehind {
            drawRect(color = surface)
            drawRect(brush = brush)
        }
    }

internal fun DrawScope.insetRect(
    color: Color,
    left: Float, top: Float, right: Float, bottom: Float
) {
    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(
            (size.width - left - right).coerceAtLeast(0f),
            (size.height - top - bottom).coerceAtLeast(0f)
        )
    )
}
