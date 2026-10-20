package com.hubmobile.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hubmobile.ui.components.Avatar
import com.hubmobile.ui.components.EmptyState
import com.hubmobile.ui.components.FilterBar
import com.hubmobile.ui.components.GithupButton
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SegItem
import com.hubmobile.ui.components.Segmented
import com.hubmobile.ui.components.StateBadge
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

/**
 * 搜索 —— 顶部输入框，下面是「类型分段 + 排序段控件」的筛选栏，
 * 再往下是结果列表。
 *
 * 筛选栏照 .filterbar 的规矩：**并排一行、谁都不许换行**，
 * 撑不下时横向滑动，而不是把中文折断（原项目专门为「收藏夹」折成三行踩过坑）。
 */
@Composable
fun SearchScreen(
    repos: List<Repo> = Sample.repos,
    users: List<User> = listOf(Sample.me),
    onRepo: (Repo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    var q by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(SearchType.Repo) }
    var sort by remember { mutableStateOf(RepoSort.Updated) }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 10.dp)) {
            GithupSearchField(
                value = q,
                onValueChange = { q = it },
                placeholder = "搜索仓库、议题、用户…"
            )
        }

        Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 4.dp)) {
            Segmented(
                items = SearchType.values().map { SegItem(it, it.label) },
                selected = type,
                onSelect = { type = it },
                modifier = Modifier.fillMaxWidth()
            )
        }

        FilterBar {
            Segmented(
                items = RepoSort.values().map { SegItem(it, it.label) },
                selected = sort,
                onSelect = { sort = it },
                modifier = Modifier.weight(0.62f)
            )
            GithupButton(
                text = "筛选",
                icon = Octicons.Filter,
                onClick = { },
                size = com.hubmobile.ui.components.ButtonSize.Small
            )
        }

        val results = when (type) {
            SearchType.Repo -> repos.filter { it.fullName.contains(q, true) || it.desc.contains(q, true) }
            else -> emptyList()
        }

        if (q.isEmpty()) {
            EmptyState(Octicons.Search, "输入关键词开始搜索", "支持仓库名、描述、语言")
            return@Column
        }

        if (results.isEmpty() && type != SearchType.User) {
            EmptyState(Octicons.Inbox, "没有找到结果", "换个关键词试试")
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            when (type) {
                SearchType.Repo -> itemsIndexed(results) { i, r ->
                    RepoRow(repo = r, onClick = { onRepo(r) })
                    if (i < results.lastIndex) RowDivider()
                }
                SearchType.User -> itemsIndexed(users) { i, u ->
                    UserRow(user = u, onClick = { })
                    if (i < users.lastIndex) RowDivider()
                }
                SearchType.Issue -> {
                    val list = Sample.issues.filter { it.title.contains(q, true) }
                    itemsIndexed(list) { i, it0 ->
                        IssueRow(item = it0, onClick = { })
                        if (i < list.lastIndex) RowDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun UserRow(user: User, onClick: () -> Unit) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = GithupDimens.rowPadH, vertical = GithupDimens.rowPadV),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(size = 32.dp, url = user.avatar, contentDescription = user.login)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                user.name.ifEmpty { user.login },
                color = T.fg, fontSize = GithupType.body, fontWeight = FontWeight.SemiBold
            )
            Text("@${user.login}", color = T.fgMuted, fontSize = 13.sp)
        }
        Icon(Octicons.ChevronRight, null, tint = T.fgSubtle, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun IssueRow(item: IssueItem, onClick: () -> Unit) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = GithupDimens.rowPadH, vertical = 10.dp)
    ) {
        StateBadge(item.state)
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(item.title, color = T.fg, fontSize = GithupType.body, maxLines = 2)
            Text(
                "#${item.number} · ${item.author} · ${item.time}",
                color = T.fgMuted, fontSize = GithupType.meta,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
