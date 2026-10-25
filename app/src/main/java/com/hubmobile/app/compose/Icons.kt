package com.hubmobile.app.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/*
 * 图标：全部用 Canvas 画，对照 assets/web/js/icons.js 里的 SVG 形状。
 *
 * 为什么不引 material-icons-extended：那个包会往 APK 里塞几 MB 的矢量图，
 * 而这里只需要十来个。自己画代价是一次性的，包体是长期的。
 *
 * 所有图标统一"描边派"：线宽 1.6dp、圆头端点，视觉上与网页版 feather 风格一致。
 */

private val W = 1.6f   // 线宽系数（相对 24dp 视口）

/** 24dp 视口下的坐标都按 0..24 写，这里统一缩放到实际尺寸 */
private fun DrawScope.s(v: Float) = v * size.width / 24f

@Composable
fun HubIcon(
    name: String,
    modifier: Modifier = Modifier,
    tint: Color = HubColors.FgMuted
) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        fun s(v: Float) = v * w / 24f
        val stroke = Stroke(width = s(W), cap = StrokeCap.Round)
        val fill = androidx.compose.ui.graphics.drawscope.Fill

        when (name) {
            "home" -> {
                // 屋顶 + 屋身
                drawLine(tint, Offset(s(3f), s(11f)), Offset(s(12f), s(3f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(12f), s(3f)), Offset(s(21f), s(11f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(5f), s(9.5f)), Offset(s(5f), s(21f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(19f), s(9.5f)), Offset(s(19f), s(21f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(3f), s(21f)), Offset(s(21f), s(21f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(10f), s(21f)), Offset(s(10f), s(15f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(14f), s(21f)), Offset(s(14f), s(15f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(10f), s(15f)), Offset(s(14f), s(15f)), s(W), StrokeCap.Round)
            }
            "bell" -> {
                // 钟身：两侧弧 + 底边
                drawPath(Path().apply {
                    moveTo(s(6f), s(16f))
                    cubicTo(s(6f), s(9f), s(9f), s(6f), s(12f), s(6f))
                    cubicTo(s(15f), s(6f), s(18f), s(9f), s(18f), s(16f))
                    lineTo(s(6f), s(16f)); close()
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                drawLine(tint, Offset(s(4.5f), s(16f)), Offset(s(19.5f), s(16f)), s(W), StrokeCap.Round)
                // 钟锤
                drawPath(Path().apply {
                    moveTo(s(9.5f), s(19f))
                    cubicTo(s(9.5f), s(17.2f), s(14.5f), s(17.2f), s(14.5f), s(19f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
            }
            "telescope" -> {
                // 罗盘：圆 + 内部指针
                drawCircle(tint, s(9f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                drawPath(Path().apply {
                    moveTo(s(15.5f), s(8.5f)); lineTo(s(13.8f), s(13.8f)); lineTo(s(8.5f), s(15.5f))
                    lineTo(s(10.2f), s(10.2f)); close()
                }, tint)
            }
            "search" -> {
                drawCircle(tint, s(7f), Offset(s(10.5f), s(10.5f)), style = Stroke(width = s(W)))
                drawLine(tint, Offset(s(15.5f), s(15.5f)), Offset(s(20.5f), s(20.5f)), s(W), StrokeCap.Round)
            }
            "person" -> {
                drawCircle(tint, s(4f), Offset(s(12f), s(8f)), style = Stroke(width = s(W)))
                drawPath(Path().apply {
                    moveTo(s(4f), s(21f))
                    cubicTo(s(4f), s(17f), s(8f), s(14f), s(12f), s(14f))
                    cubicTo(s(16f), s(14f), s(20f), s(17f), s(20f), s(21f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
            }
            "repo" -> {
                // 书本：两页
                drawLine(tint, Offset(s(4f), s(5f)), Offset(s(9f), s(5f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(4f), s(5f)), Offset(s(4f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(4f), s(19f)), Offset(s(9f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(9f), s(5f)), Offset(s(9f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(15f), s(5f)), Offset(s(20f), s(5f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(20f), s(5f)), Offset(s(20f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(20f), s(19f)), Offset(s(15f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(11f), s(5f)), Offset(s(13f), s(5f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(11f), s(19f)), Offset(s(13f), s(19f)), s(W), StrokeCap.Round)
            }
            "star" -> {
                starPath(this, s(12f), s(12f), s(9f)).let {
                    drawPath(it, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                }
            }
            "star-filled" -> drawPath(starPath(this, s(12f), s(12f), s(9f)), tint)
            "issue" -> {
                drawCircle(tint, s(8.5f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                drawCircle(tint, s(3.2f), Offset(s(12f), s(12f)))
            }
            "pr" -> {
                drawCircle(tint, s(2.6f), Offset(s(6f), s(6f)))
                drawCircle(tint, s(2.6f), Offset(s(6f), s(18f)))
                drawCircle(tint, s(2.6f), Offset(s(18f), s(18f)))
                drawLine(tint, Offset(s(6f), s(8.6f)), Offset(s(6f), s(15.4f)), s(W), StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(s(17f), s(7f)); lineTo(s(21f), s(11f)); lineTo(s(17f), s(15f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                drawLine(tint, Offset(s(21f), s(11f)), Offset(s(8.6f), s(11f)), s(W), StrokeCap.Round)
            }
            "code-square" -> {
                drawRoundRect(tint, Offset(s(3f), s(3f)), Size(s(18f), s(18f)),
                    androidx.compose.ui.geometry.CornerRadius(s(3f), s(3f)),
                    style = Stroke(width = s(W)))
                drawLine(tint, Offset(s(9.5f), s(9f)), Offset(s(14.5f), s(15f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(14.5f), s(9f)), Offset(s(9.5f), s(15f)), s(W), StrokeCap.Round)
            }
            "organization" -> {
                drawRoundRect(tint, Offset(s(9f), s(3f)), Size(s(6f), s(5f)),
                    androidx.compose.ui.geometry.CornerRadius(s(2f), s(2f)),
                    style = Stroke(width = s(W)))
                drawLine(tint, Offset(s(4f), s(21f)), Offset(s(4f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(20f), s(21f)), Offset(s(20f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(12f), s(8f)), Offset(s(12f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(4f), s(12f)), Offset(s(20f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(4f), s(21f)), Offset(s(20f), s(21f)), s(W), StrokeCap.Round)
            }
            "chevron-right" -> drawPath(Path().apply {
                moveTo(s(9f), s(5f)); lineTo(s(16f), s(12f)); lineTo(s(9f), s(19f))
            }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
            "chevron-left" -> drawPath(Path().apply {
                moveTo(s(15f), s(5f)); lineTo(s(8f), s(12f)); lineTo(s(15f), s(19f))
            }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
            "plus" -> {
                drawLine(tint, Offset(s(12f), s(5f)), Offset(s(12f), s(19f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(5f), s(12f)), Offset(s(19f), s(12f)), s(W), StrokeCap.Round)
            }
            "sync" -> {
                drawPath(Path().apply {
                    moveTo(s(20f), s(12f))
                    cubicTo(s(20f), s(16.4f), s(16.4f), s(20f), s(12f), s(20f))
                    cubicTo(s(8f), s(20f), s(4.6f), s(17f), s(4.1f), s(13.2f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                drawPath(Path().apply {
                    moveTo(s(4f), s(12f))
                    cubicTo(s(4f), s(7.6f), s(7.6f), s(4f), s(12f), s(4f))
                    cubicTo(s(16f), s(4f), s(19.4f), s(7f), s(19.9f), s(10.8f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                drawLine(tint, Offset(s(17.5f), s(4f)), Offset(s(21f), s(11f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(6.5f), s(20f)), Offset(s(3f), s(13f)), s(W), StrokeCap.Round)
            }
            "gear" -> {
                drawCircle(tint, s(3f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                drawCircle(tint, s(8f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                for (i in 0 until 6) {
                    rotate(i * 60f, Offset(s(12f), s(12f))) {
                        drawLine(tint, Offset(s(12f), s(1.5f)), Offset(s(12f), s(4.5f)), s(W), StrokeCap.Round)
                    }
                }
            }
            "alert" -> {
                drawCircle(tint, s(9f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                drawLine(tint, Offset(s(12f), s(7.5f)), Offset(s(12f), s(13f)), s(W), StrokeCap.Round)
                drawCircle(tint, s(1f), Offset(s(12f), s(16f)))
            }
            "zap" -> drawPath(Path().apply {
                moveTo(s(13f), s(2f)); lineTo(s(5f), s(13f)); lineTo(s(11f), s(13f))
                lineTo(s(10f), s(22f)); lineTo(s(19f), s(10f)); lineTo(s(12.5f), s(10f)); close()
            }, tint)
            "fork" -> {
                drawCircle(tint, s(2.6f), Offset(s(6f), s(5f)))
                drawCircle(tint, s(2.6f), Offset(s(18f), s(5f)))
                drawCircle(tint, s(2.6f), Offset(s(12f), s(19f)))
                drawLine(tint, Offset(s(6f), s(7.6f)), Offset(s(6f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(18f), s(7.6f)), Offset(s(18f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(6f), s(12f)), Offset(s(18f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(12f), s(12f)), Offset(s(12f), s(16.4f)), s(W), StrokeCap.Round)
            }
            "mark-github" -> {
                drawCircle(tint, s(9.5f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
                drawPath(Path().apply {
                    moveTo(s(8f), s(21.5f)); lineTo(s(8f), s(18f))
                    cubicTo(s(4.5f), s(17.2f), s(2.5f), s(15f), s(2.5f), s(11f))
                    lineTo(s(5f), s(11f))
                    cubicTo(s(5f), s(13.5f), s(6f), s(15f), s(8f), s(15.9f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
            }
            "book" -> {
                drawPath(Path().apply {
                    moveTo(s(4f), s(5f)); cubicTo(s(4f), s(4f), s(5f), s(3.5f), s(6f), s(3.5f))
                    lineTo(s(18f), s(3.5f)); cubicTo(s(19f), s(3.5f), s(20f), s(4f), s(20f), s(5f))
                    lineTo(s(20f), s(19f)); cubicTo(s(20f), s(20f), s(19f), s(20.5f), s(18f), s(20.5f))
                    lineTo(s(6f), s(20.5f)); cubicTo(s(5f), s(20.5f), s(4f), s(20f), s(4f), s(19f)); close()
                }, tint, style = Stroke(width = s(W)))
                drawLine(tint, Offset(s(12f), s(3.8f)), Offset(s(12f), s(20.2f)), s(W), StrokeCap.Round)
            }
            "download" -> {
                drawLine(tint, Offset(s(12f), s(3f)), Offset(s(12f), s(15f)), s(W), StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(s(7f), s(10f)); lineTo(s(12f), s(15f)); lineTo(s(17f), s(10f))
                }, tint, style = Stroke(width = s(W), cap = StrokeCap.Round))
                drawLine(tint, Offset(s(4f), s(20f)), Offset(s(20f), s(20f)), s(W), StrokeCap.Round)
            }
            "logout" -> {
                drawLine(tint, Offset(s(14f), s(4f)), Offset(s(20f), s(4f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(17f), s(8f)), Offset(s(20f), s(12f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(17f), s(16f)), Offset(s(20f), s(20f)), s(W), StrokeCap.Round)
                drawLine(tint, Offset(s(9f), s(12f)), Offset(s(20f), s(12f)), s(W), StrokeCap.Round)
                drawRoundRect(tint, Offset(s(3f), s(6f)), Size(s(10f), s(12f)),
                    androidx.compose.ui.geometry.CornerRadius(s(2f), s(2f)),
                    style = Stroke(width = s(W)))
            }
            else -> drawCircle(tint, s(8f), Offset(s(12f), s(12f)), style = Stroke(width = s(W)))
        }
    }
}

/** 五角星路径：网页版 star 图标用的就是这个形状 */
private fun starPath(scope: androidx.compose.ui.graphics.drawscope.DrawScope,
                     cx: Float, cy: Float, r: Float): Path {
    val p = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = Math.toRadians((-90 + i * 36).toDouble())
        val x = cx + (Math.cos(a) * rad).toFloat()
        val y = cy + (Math.sin(a) * rad).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}
