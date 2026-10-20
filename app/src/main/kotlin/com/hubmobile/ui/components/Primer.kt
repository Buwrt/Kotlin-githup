package com.hubmobile.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.weight

/* ============================================================
 * 通用块 —— 类名与 app.css 的 .card / .list-row / .btn / .seg / .chip
 * 一一对应，方便拿原项目前端对照改动。
 * ============================================================ */

/**
 * .card —— 白底、1px 描边、8dp 圆角、左右各留 12。
 * flat = true 时对应 .card.flat：通栏、只有上下边。
 */
@Composable
fun GithupCard(
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    content: @Composable () -> Unit
) {
    val T = Githup
    val shape = if (flat) RoundedCornerShape(0.dp) else RoundedCornerShape(GithupDimens.radius)
    val m = if (flat) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GithupDimens.pageGutter)
    }
    Column(
        modifier = modifier
            .then(m)
            .clip(shape)
            .background(T.bg)
            .border(1.dp, T.border, shape),
        content = content
    )
}

/**
 * .list-row —— 一行列表项：主区（标题 / 描述 / 元信息）+ 行尾操作区。
 *
 * 对齐原 CSS：padding 12×14、行间 1px 分割线、按压时整行换底。
 * alignItems 用 flex-start 是给多行文本准备的（标题在上、描述在下时
 * 不能把标题顶到中间）；行尾按钮只有一行高，自己居中（对应 .row-side）。
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    meta: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    side: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val T = Githup
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(T.bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = GithupDimens.rowPadH,
                vertical = GithupDimens.rowPadV
            ),
        verticalAlignment = Alignment.Top
    ) {
        if (leading != null) {
            Box(modifier = Modifier.padding(end = 10.dp)) { leading() }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = T.fg,
                fontSize = GithupType.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!desc.isNullOrEmpty()) {
                Text(
                    text = desc,
                    color = T.fgMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (meta != null) {
                Box(modifier = Modifier.padding(top = 6.dp)) { meta() }
            }
        }
        if (side != null) {
            Box(modifier = Modifier.padding(start = 10.dp).align(Alignment.CenterVertically)) {
                side()
            }
        }
    }
}

/** .row-meta —— 元信息行（星标数 / 分支数 / 时间…），12sp、可换行 */
@Composable
fun RowMeta(items: List<MetaItem>) {
    val T = Githup
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { it0 ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (it0.icon != null) {
                    Icon(
                        imageVector = it0.icon,
                        contentDescription = null,
                        tint = T.fgMuted,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(text = it0.text, color = T.fgMuted, fontSize = GithupType.meta)
            }
        }
    }
}

data class MetaItem(val text: String, val icon: ImageVector? = null)

/* ============================ 按钮 ============================ */

/**
 * .btn —— 6×12 内边距、6dp 圆角、min 高 32、14sp/500。
 * 变体：primary（绿）/ danger（红字）/ sm / lg / block。
 */
@Composable
fun GithupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Default,
    size: ButtonSize = ButtonSize.Medium,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    block: Boolean = false
) {
    val T = Githup
    val isPrimary = variant == ButtonVariant.Primary
    val isDanger = variant == ButtonVariant.Danger

    val padV: Dp
    val padH: Dp
    val minH: Dp
    val fs: TextUnit
    val iconSize: Dp
    when (size) {
        ButtonSize.Small -> { padV = 3.dp; padH = 8.dp; minH = 26.dp; fs = GithupType.chip; iconSize = 12.dp }
        ButtonSize.Medium -> { padV = 6.dp; padH = 12.dp; minH = 32.dp; fs = GithupType.body; iconSize = 14.dp }
        ButtonSize.Large -> { padV = 10.dp; padH = 16.dp; minH = 44.dp; fs = 15.sp; iconSize = 15.dp }
    }

    val bg = when {
        isPrimary -> T.btnPrimaryBg
        else -> T.btnBg
    }
    val fg = when {
        isPrimary -> T.btnPrimaryFg
        isDanger -> T.btnDangerFg
        else -> T.fg
    }
    val shape = RoundedCornerShape(GithupDimens.radiusSm)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = bg,
        contentColor = fg,
        border = androidx.compose.foundation.BorderStroke(1.dp, T.btnBorder),
        modifier = modifier.then(if (block) Modifier.fillMaxWidth() else Modifier)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = minH)
                .padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(iconSize))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = fs, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

enum class ButtonVariant { Default, Primary, Danger }
enum class ButtonSize { Small, Medium, Large }

/* ============================ 分段控件 ============================ */

/**
 * .seg —— 分段控件。选中项用「底色变白 + 加粗 + 底部 2px 强调色条」表达，
 * 对应 CSS 的 box-shadow: inset 0 -2px 0 var(--accent)。
 */
@Composable
fun <T> Segmented(
    items: List<SegItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val T = Githup
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(GithupDimens.radiusSm))
            .background(T.btnBg)
            .border(1.dp, T.btnBorder, RoundedCornerShape(GithupDimens.radiusSm))
    ) {
        items.forEachIndexed { i, item ->
            val active = item.key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .background(if (active) T.bg else Color.Transparent)
                    .then(
                        // 只在右侧画一条 1px 分隔线（对应 CSS 的 border-right），
                        // 不能用 .border()：那会把四条边都描上
                        if (i < items.size - 1) Modifier.drawRightDivider(T.borderMuted) else Modifier
                    )
                    .clickable { onSelect(item.key) }
                    .then(
                        if (active) {
                            Modifier.drawBottomAccent(T.accent)
                        } else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.label,
                    color = if (active) T.fg else T.fgMuted,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

data class SegItem<T>(val key: T, val label: String)

/** 选中项底部 2px 强调色条 —— 对应 CSS 的 box-shadow: inset 0 -2px 0 var(--accent) */
private fun Modifier.drawBottomAccent(color: Color): Modifier =
    drawWithContent {
        drawContent()
        drawLine(
            color = color,
            start = Offset(0f, size.height - 2.dp.toPx()),
            end = Offset(size.width, size.height - 2.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
    }

/** 段与段之间那条 1px 竖线 —— 对应 CSS 的 .seg button { border-right } */
private fun Modifier.drawRightDivider(color: Color): Modifier =
    drawWithContent {
        drawContent()
        drawLine(
            color = color,
            start = Offset(size.width - 1.dp.toPx(), 0f),
            end = Offset(size.width - 1.dp.toPx(), size.height),
            strokeWidth = 1.dp.toPx()
        )
    }

/* ============================ Chip ============================ */

/** .chip —— 胶囊描边标签；active 时填满强调色 */
@Composable
fun GithupChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val T = Githup
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (active) T.accentEmphasis else T.bg)
            .border(
                1.dp,
                if (active) T.accentEmphasis else T.border,
                RoundedCornerShape(percent = 50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) Color.White else T.fgMuted, modifier = Modifier.size(12.dp))
        }
        Text(
            text = text,
            color = if (active) Color.White else T.fgMuted,
            fontSize = GithupType.chip,
            maxLines = 1
        )
    }
}

/** .chips —— 横向滚动的一排 chip（不换行、隐藏滚动条） */
@Composable
fun ChipRow(
    items: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GithupDimens.pageGutter, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach {
            GithupChip(text = it, active = it == selected, onClick = { onSelect(it) })
        }
    }
}

/* ============================ 头像 ============================ */

/**
 * .avatar —— 圆形（方形传 square = true）。
 *
 * 这里刻意用「占位块」而不是接 Coil/Glide：本轮不引入图片加载库，
 * 也就不会有网络权限和额外的依赖。要显示真头像时，把 `url` 交给
 * rememberAsyncImagePainter 即可，形状与占位逻辑都不用动。
 *
 * 占位色用 --skeleton（#eaeef2 / #21262d），与原 CSS 一致。
 */
@Composable
fun Avatar(
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    square: Boolean = false,
    url: String = "",
    contentDescription: String? = null
) {
    val T = Githup
    val shape = if (square) RoundedCornerShape(GithupDimens.radiusSm)
    else RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(T.skeleton),
        contentAlignment = Alignment.Center
    ) {
        // 没有头像地址时，用 owner 首字符占位，避免整块灰圆
        if (url.isEmpty()) {
            Text(
                text = (contentDescription?.firstOrNull() ?: '?').uppercaseChar().toString(),
                color = T.fgSubtle,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/* ============================ 状态徽章 ============================ */

/**
 * .state —— Issue / PR 状态胶囊。
 * open 绿、merged/closed 紫（--done）、draft 灰。
 */
@Composable
fun StateBadge(
    state: IssueState,
    modifier: Modifier = Modifier
) {
    val T = Githup
    val (bg, icon, label) = when (state) {
        IssueState.Open -> Triple(T.successEmphasis, Octicons.IssueOpened, "待处理")
        IssueState.Closed -> Triple(T.done, Octicons.IssueClosed, "已关闭")
        IssueState.Merged -> Triple(T.done, Octicons.GitMerge, "已合并")
        IssueState.Draft -> Triple(T.neutralEmphasis, Octicons.GitPullRequestDraft, "草稿")
        IssueState.NotPlanned -> Triple(T.neutralEmphasis, Octicons.Skip, "未计划")
        IssueState.Pending -> Triple(T.successEmphasis, Octicons.GitPullRequest, "待合并")
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(label, color = Color.White, fontSize = GithupType.meta, fontWeight = FontWeight.SemiBold)
    }
}

enum class IssueState { Open, Closed, Merged, Draft, NotPlanned, Pending }

/* ============================ 分区标题 / 空态 / 骨架 ============================ */

/** .section-title —— 12sp/600 弱化色，两侧留 14 */
@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = T.fgMuted, fontSize = GithupType.section, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** .empty —— 居中图标 + 标题 + 说明 */
@Composable
fun EmptyState(
    icon: ImageVector = Octicons.Inbox,
    title: String,
    desc: String? = null,
    modifier: Modifier = Modifier
) {
    val T = Githup
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = T.fgMuted.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = T.fg, fontSize = GithupType.emptyTitle, fontWeight = FontWeight.SemiBold)
        if (!desc.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(desc, color = T.fgMuted, fontSize = GithupType.emptyDesc)
        }
    }
}

/** .skel —— 骨架屏 */
@Composable
fun SkeletonList(count: Int = 5, modifier: Modifier = Modifier) {
    val T = Githup
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        repeat(count) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(16.dp)
                    .padding(vertical = 2.dp)
                    .background(T.skeleton, RoundedCornerShape(GithupDimens.radiusSm))
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(16.dp)
                    .background(T.skeleton, RoundedCornerShape(GithupDimens.radiusSm))
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/* ============================ 顶栏 ============================ */

/**
 * #appbar —— 52dp 高、底部 1px 分隔线、下方 2px 进度条。
 * 左侧返回按钮（40×40）、中间标题（16sp/600、单行省略）、右侧动作区。
 */
@Composable
fun GithupTopBar(
    title: String,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    loading: Boolean = false,
    actions: (@Composable () -> Unit)? = null
) {
    val T = Githup
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(T.bg)
            .border(1.dp, T.border, RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GithupDimens.appbarHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                IconButton(onClick = { onBack?.invoke() }, modifier = Modifier.size(GithupDimens.iconBtn)) {
                    Icon(Octicons.ArrowLeft, "返回", tint = T.fgMuted)
                }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                color = T.fg,
                fontSize = GithupType.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            )
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
        if (loading) {
            GithupProgressBar()
        }
    }
}

/** .progress —— 2px 高的不确定进度条 */
@Composable
private fun GithupProgressBar() {
    val T = Githup
    val infinite = rememberInfiniteTransitionBar()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(2.dp)
                .offset(x = infinite)
                .background(T.accent, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun rememberInfiniteTransitionBar(): Dp {
    val inf = rememberInfiniteTransition(label = "bar")
    val f = inf.animateFloat(
        initialValue = -1f,
        targetValue = 3.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "barF"
    )
    // 直接用 .value 而不是 by 委托：省掉 getValue 导入，也避开 Float 的重载歧义
    return (f.value * 100f).dp
}

/* ============================ FAB ============================ */

/** .fab —— 52dp 圆形主色按钮，贴着底栏上沿 */
@Composable
fun GithupFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val T = Githup
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(GithupDimens.fabSize),
        containerColor = T.btnPrimaryBg,
        contentColor = Color.White,
        shape = RoundedCornerShape(percent = 50),
        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp
        )
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(24.dp))
    }
}

/* ============================ 筛选栏 ============================ */

/**
 * .filterbar —— 段控件与操作按钮并排一行，**谁都不许换行**；
 * 撑不下时横向滑动（滚动条隐藏），而不是把中文折断。
 */
@Composable
fun FilterBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GithupDimens.pageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}
