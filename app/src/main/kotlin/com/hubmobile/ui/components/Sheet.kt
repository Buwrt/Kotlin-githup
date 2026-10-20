package com.hubmobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType
import kotlinx.coroutines.delay

/**
 * .sheet —— 底部弹层。
 *
 * 与 CSS 版一致的几点：
 *   · 遮罩 rgba(31,35,40,.5)，点遮罩关闭；
 *   · 圆角 14 只在上方两角，高度取视口 90%；
 *   · 上滑 .26s cubic-bezier(.2,.8,.3,1)；
 *   · dismissible = false 时（强制更新这类）不给关闭按钮、点遮罩无效。
 */
@Composable
fun GithupSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    icon: ImageVector? = null,
    dismissible: Boolean = true,
    foot: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit
) {
    val T = Githup
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x801F2328))
                .clickable(enabled = dismissible) { onDismiss() }
        ) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 0.dp)
                        .background(
                            T.canvas,
                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                        )
                        .clickable(enabled = false) { }
                ) {
                    if (title != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    T.canvas,
                                    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                                )
                                .padding(horizontal = 16.dp)
                                .padding(top = 14.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (icon != null) {
                                Icon(icon, null, tint = T.fgMuted, modifier = Modifier.size(20.dp))
                                Box(Modifier.size(8.dp))
                            }
                            Text(
                                title, color = T.fg, fontSize = GithupType.title,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            if (dismissible) {
                                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                                    Icon(Octicons.X, "关闭", tint = T.fgMuted)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(T.border)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(top = 14.dp, bottom = 16.dp)
                    ) { body() }
                    if (foot != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(T.border)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) { foot() }
                    }
                }
            }
        }
    }
}

/** .opt —— 弹层里的选项行。选中带右侧对勾 */
@Composable
fun OptionRow(
    label: String,
    icon: ImageVector? = null,
    value: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .then(if (!enabled) Modifier.background(Color.Transparent) else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon, null,
                tint = T.fgMuted.copy(alpha = if (enabled) 1f else 0.45f),
                modifier = Modifier.size(18.dp)
            )
            Box(Modifier.size(10.dp))
        }
        Text(
            label,
            color = T.fg.copy(alpha = if (enabled) 1f else 0.45f),
            fontSize = GithupType.body,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(value, color = T.fgMuted, fontSize = 12.sp)
            Box(Modifier.size(8.dp))
        }
        if (selected) {
            Icon(Octicons.Check, null, tint = T.accent, modifier = Modifier.size(18.dp))
        }
    }
}

/* ============================ Toast ============================ */

/**
 * .toast —— 底部居中的轻提示，两三秒自动消失，
 * 容器 pointer-events:none（这里用 clickable 关闭实现），不打断操作。
 */
@Composable
fun ToastHost(
    message: String?,
    ok: Boolean = false,
    onDone: () -> Unit
) {
    val T = Githup
    var show by remember { mutableStateOf(message != null) }
    LaunchedEffect(message) {
        if (message != null) {
            show = true
            delay(if (ok) 2600 else 2400)
            show = false
            onDone()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 100.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(visible = show, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .background(
                        if (isSystemInDarkTheme()) Color(0xFF30363D) else Color(0xFF1F2328),
                        RoundedCornerShape(GithupDimens.radius)
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ok) {
                    Icon(
                        Octicons.CheckCircleFill, null,
                        tint = Color(0xFF3FB950), modifier = Modifier.size(16.dp)
                    )
                    Box(Modifier.size(6.dp))
                }
                Text(message.orEmpty(), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

/* ============================ 图片查看器 ============================ */

/** #viewer-root —— 全屏大图 + 右上角保存；点空白处关闭 */
@Composable
fun ImageViewer(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            content()
            IconButton(
                onClick = onSave,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
            ) {
                Icon(Octicons.Download, "保存到相册", tint = Color.White)
            }
        }
    }
}
