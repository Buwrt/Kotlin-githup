package com.hubmobile.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.Avatar
import com.hubmobile.ui.components.EmptyState
import com.hubmobile.ui.components.GithupButton
import com.hubmobile.ui.components.GithupCard
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.SectionTitle
import com.hubmobile.ui.components.SkeletonList
import com.hubmobile.ui.store.LocalStore
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupType
import androidx.compose.foundation.layout.weight

/**
 * 首页 —— 对齐 page-home.js 的登录态首页：
 *   ① 顶部身份卡（40dp 头像 + 名字 + @login + 右侧进入）
 *   ② 3 列快捷网格（我的仓库 / Star / 议题 / PR / Gist / 组织）
 *   ③ 「我的仓库」分区 + 仓库列表
 * 未登录时（guestHome）退化成「登录提示卡 + 热门仓库」。
 */
@Composable
fun HomeScreen(
    loggedIn: Boolean = true,
    me: User = Sample.me,
    repos: List<Repo> = Sample.repos,
    onRepo: (Repo) -> Unit = {},
    onStar: (Repo) -> Unit = {},
    onQuick: (QuickItem) -> Unit = {},
    onLogin: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val T = Githup
    // 星标存在 SharedPreferences 里，改完靠这个计数把列表重算一遍
    var starTick by remember { mutableIntStateOf(0) }
    val shown = remember(starTick, repos) {
        repos.map { it.copy(starred = LocalStore.isStarred(it.fullName)) }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        if (!loggedIn) {
            item { GuestCard(onLogin = onLogin) }
            item {
                SectionTitle("热门仓库")
                if (shown.isEmpty()) SkeletonList(4) else RepoCardList(shown, onRepo)
            }
            return@LazyColumn
        }

        item {
            GithupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(size = 40.dp, url = me.avatar, contentDescription = me.login)
                    Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            me.name.ifEmpty { me.login },
                            color = T.fg, fontSize = GithupType.body, fontWeight = FontWeight.SemiBold
                        )
                        Text("@${me.login}", color = T.fgMuted, fontSize = 13.sp)
                    }
                    IconButton(onClick = { onQuick(QuickItem("me", Octicons.Person, "/${me.login}")) },
                        modifier = Modifier.size(32.dp)) {
                        Icon(Octicons.ChevronRight, "我的主页", tint = T.fgMuted)
                    }
                }
                QuickGrid(
                    items = listOf(
                        QuickItem("我的仓库", Octicons.Repo, "/${me.login}?tab=repos"),
                        QuickItem("我的 Star", Octicons.Star, "/${me.login}?tab=stars"),
                        QuickItem("我的议题", Octicons.IssueOpened, "/issues/mine"),
                        QuickItem("我的 PR", Octicons.GitPullRequest, "/pulls/mine"),
                        QuickItem("我的 Gist", Octicons.Code, "/gists"),
                        QuickItem("组织", Octicons.Organization, "/${me.login}?tab=orgs")
                    ),
                    onItem = onQuick
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Octicons.Repo, null, tint = T.fgMuted, modifier = Modifier.size(14.dp))
                    Text(
                        "我的仓库", color = T.fgMuted,
                        fontSize = GithupType.section, fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BtnSm(text = "新建", icon = Octicons.Plus, onClick = { })
                    BtnSm(text = "全部", onClick = { })
                }
            }
        }

        if (shown.isEmpty()) {
            item { EmptyState(Octicons.Repo, "还没有仓库", "点击「新建」创建第一个仓库") }
        } else {
            itemsIndexed(shown) { i, r ->
                RepoRow(
                    repo = r,
                    onClick = { onRepo(r) },
                    onStar = {
                        val on = LocalStore.toggleStar(r.fullName)
                        starTick++
                        onStar(r.copy(starred = on))
                    }
                )
                if (i < shown.lastIndex) RowDivider()
            }
        }
    }
}

/** 未登录首页的提示卡（guestHome） */
@Composable
private fun GuestCard(onLogin: () -> Unit) {
    val T = Githup
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Octicons.MarkGithub, null, tint = T.fg, modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("未登录浏览模式", color = T.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "登录后可查看动态、通知，并参与 Issue / PR 协作",
                    color = T.fgMuted, fontSize = 12.sp
                )
            }
        }
        GithupButton(
            text = "立即登录", onClick = onLogin,
            variant = com.hubmobile.ui.components.ButtonVariant.Primary,
            size = com.hubmobile.ui.components.ButtonSize.Large,
            block = true,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** .btn.sm —— 分区标题右侧的小按钮 */
@Composable
private fun BtnSm(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Box {
        com.hubmobile.ui.components.GithupButton(
            text = text, onClick = onClick,
            size = com.hubmobile.ui.components.ButtonSize.Small,
            icon = icon
        )
    }
}
