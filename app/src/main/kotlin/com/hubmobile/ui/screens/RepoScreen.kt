package com.hubmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.GithupCard
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SegItem
import com.hubmobile.ui.components.Segmented
import com.hubmobile.ui.components.SectionTitle
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.theme.GithupType

/**
 * 仓库详情 —— 对齐 page-repo.js：
 *   ① 头部卡（owner/name + 描述 + 语言条 + star / fork / watch + 分支 / 标签）
 *   ② 「代码 / 议题 / PR / Actions / 发布」分段
 *   ③ 分段内容（这里用议题列表示意，真数据接上即可）
 */
@Composable
fun RepoScreen(
    repo: Repo = Sample.repos.first(),
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    var tab by remember { mutableStateOf(RepoTab.Code) }

    LazyColumn(modifier = modifier.fillMaxSize().background(T.canvas)) {
        item {
            GithupCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (repo.isPrivate) Octicons.Lock else Octicons.Repo,
                            null, tint = T.fgMuted, modifier = Modifier.size(16.dp)
                        )
                        Text(
                            repo.owner + " / ",
                            color = T.fgMuted, fontSize = GithupType.body,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                        Text(
                            repo.name, color = T.fg, fontSize = GithupType.body,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (repo.desc.isNotEmpty()) {
                        Text(
                            repo.desc, color = T.fgMuted, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // 语言条
                    if (repo.lang.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(langColor(repo.lang), RoundedCornerShape(percent = 50))
                            )
                            Text(repo.lang, color = T.fgMuted, fontSize = GithupType.meta)
                        }
                    }
                    // star / fork / watch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CountCell(Octicons.Star, formatCount(repo.stars))
                        CountCell(Octicons.RepoForked, formatCount(repo.forks))
                        CountCell(Octicons.Eye, "12")
                    }
                }
            }
        }

        item {
            Box(modifier = Modifier.padding(horizontal = GithupDimens.pageGutter, vertical = 10.dp)) {
                Segmented(
                    items = RepoTab.values().map { SegItem(it, it.label) },
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        when (tab) {
            RepoTab.Code -> {
                item { SectionTitle("文件") }
                itemsIndexed(listOf("app", "gradle", "docs", "README.md", "build.gradle")) { i, f ->
                    FileRow(name = f, isDir = !f.contains("."))
                    if (i < 4) RowDivider()
                }
            }
            RepoTab.Issues -> {
                itemsIndexed(Sample.issues) { i, it0 ->
                    IssueRow(item = it0, onClick = { })
                    if (i < Sample.issues.lastIndex) RowDivider()
                }
            }
            else -> {
                item {
                    com.hubmobile.ui.components.EmptyState(
                        Octicons.Inbox, "这里还没有内容",
                        "切到真数据后显示 ${tab.label}"
                    )
                }
            }
        }
    }
}

enum class RepoTab(val label: String) {
    Code("代码"), Issues("议题"), Pulls("PR"), Actions("Actions"), Releases("发布")
}

@Composable
private fun CountCell(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val T = Githup
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = T.fgMuted, modifier = Modifier.size(14.dp))
        Text(text, color = T.fg, fontSize = GithupType.body, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FileRow(name: String, isDir: Boolean) {
    val T = Githup
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(T.bg)
            .padding(horizontal = GithupDimens.rowPadH, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDir) Octicons.FileDirectoryFill else Octicons.File,
            null, tint = if (isDir) T.accent else T.fgMuted, modifier = Modifier.size(16.dp)
        )
        Text(
            name, color = T.fg, fontSize = GithupType.body,
            modifier = Modifier.weight(1f).padding(start = 10.dp)
        )
        Icon(Octicons.ChevronRight, null, tint = T.fgSubtle, modifier = Modifier.size(16.dp))
    }
}
