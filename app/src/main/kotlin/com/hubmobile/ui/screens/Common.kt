package com.hubmobile.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import java.util.Locale
import com.hubmobile.ui.components.GithupChip
import com.hubmobile.ui.components.ListRow
import com.hubmobile.ui.components.MetaItem
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.RowMeta
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType
import androidx.compose.foundation.layout.weight

/**
 * repoRow —— 仓库列表行。
 * 标题 owner/name，下面是描述，元信息行依次是：语言色点 + 语言名、星标、分支、更新时间。
 */
@Composable
fun RepoRow(
    repo: Repo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onStar: (() -> Unit)? = null
) {
    val T = Githup
    ListRow(
        title = repo.fullName,
        desc = repo.desc.ifEmpty { null },
        meta = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (repo.lang.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(langColor(repo.lang), RoundedCornerShape(percent = 50))
                        )
                        Text(repo.lang, color = T.fgMuted, fontSize = 12.sp)
                    }
                }
                RowMeta(
                    listOf(
                        MetaItem(formatCount(repo.stars), Octicons.Star),
                        MetaItem(formatCount(repo.forks), Octicons.RepoForked),
                        MetaItem(repo.updated)
                    )
                )
            }
        },
        side = {
            if (repo.isPrivate) {
                GithupChip(text = "私有", active = false, onClick = {}, icon = Octicons.Lock)
            } else if (onStar != null) {
                Icon(
                    imageVector = if (repo.starred) Octicons.StarFill else Octicons.Star,
                    contentDescription = "星标",
                    tint = if (repo.starred) Color(0xFFE3B341) else T.fgSubtle,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onStar() }
                )
            }
        },
        onClick = onClick,
        modifier = modifier
    )
}

/** 行分隔线 —— 1px，左侧从文字起始处开始（对齐 .list-row 的 14dp 内边距） */
@Composable
fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GithupDimens.rowPadH)
            .height(1.dp)
            .background(Githup.borderMuted)
    )
}

/** 仓库列表（非惰性，用于嵌在 LazyColumn 的 item 里） */
@Composable
fun RepoCardList(
    repos: List<Repo>,
    onRepo: (Repo) -> Unit,
    modifier: Modifier = Modifier
) {
    val T = Githup
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(T.bg)
            .border(1.dp, T.border, RoundedCornerShape(0.dp))
    ) {
        repos.forEachIndexed { i, r ->
            RepoRow(repo = r, onClick = { onRepo(r) })
            if (i < repos.lastIndex) RowDivider()
        }
    }
}

/**
 * .input —— 与 CSS 一致：8×12 内边距、6dp 圆角、1px 描边；
 * 聚焦时描边转强调色并加一圈 3px 的强调色光晕（box-shadow: 0 0 0 3px）。
 */
@Composable
fun GithupSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null
) {
    val T = Githup
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GithupDimens.radiusSm)
    val borderColor = if (focused) T.accentEmphasis else T.border

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(T.bg)
            .border(1.dp, borderColor, shape)
            .then(
                if (focused) Modifier.border(3.dp, T.accentSubtle, shape) else Modifier
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Octicons.Search, null, tint = T.fgMuted, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            if (readOnly) {
                Text(placeholder, color = T.fgSubtle, fontSize = 14.sp, maxLines = 1)
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = T.fg, fontSize = 14.sp),
                    cursorBrush = SolidColor(T.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isEmpty()) {
                    Text(placeholder, color = T.fgSubtle, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        if (value.isNotEmpty() && !readOnly) {
            Icon(
                Octicons.X, "清空",
                tint = T.fgMuted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onValueChange("") }
            )
        }
    }
}

/** 设置 / 菜单里的一行：左侧图标、主标题、副标题、右侧值 + 箭头 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val T = Githup
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(T.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = GithupDimens.rowPadH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = T.fgMuted, modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = T.fg, fontSize = GithupType.body)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, color = T.fgMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (value != null) {
            Text(value, color = T.fgMuted, fontSize = GithupType.meta)
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        }
        Icon(Octicons.ChevronRight, null, tint = T.fgSubtle, modifier = Modifier.size(16.dp))
    }
}

/** 通知 / 动态事件的类型图标 */
fun kindIcon(kind: NotifyKind): ImageVector = when (kind) {
    NotifyKind.Issue -> Octicons.IssueOpened
    NotifyKind.PullRequest -> Octicons.GitPullRequest
    NotifyKind.Release -> Octicons.Tag
    NotifyKind.Mention -> Octicons.Comment
    NotifyKind.Discussion -> Octicons.Comment
}
