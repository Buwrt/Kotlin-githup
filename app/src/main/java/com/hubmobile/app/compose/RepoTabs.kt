package com.hubmobile.app.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 仓库页的子页 —— 对齐 page-repo.js 的 TABS：
 *   代码 / 议题 / 拉取请求 / Actions / 发布 / 更多
 * 「更多」里是：提交 / 分支 / 贡献者 / 仓库设置
 */

/* ---------------- 拉取请求 ---------------- */

@Composable
fun PullsScreen(
    owner: String,
    name: String,
    onOpen: (String) -> Unit,
    onOpenDetail: (Int) -> Unit = { n ->
        onOpen("https://github.com/$owner/$name/pull/$n")
    }
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var tab by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<List<GitHubApi.PrInfo>>(emptyList()) }
    var closed by remember { mutableStateOf<List<GitHubApi.PrInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name) {
        loading = true; err = null
        try {
            open = withContext(Dispatchers.IO) { GitHubApi.pulls(ctx, owner, name, "open") }
            closed = withContext(Dispatchers.IO) { GitHubApi.pulls(ctx, owner, name, "closed") }
            loading = false
        } catch (e: Throwable) {
                /* 协程取消必须原样抛出：吞掉它会破坏结构化并发，
                   而且 CancellationException 的 message 恒为 null */
               if (e is kotlinx.coroutines.CancellationException) throw e
               err = errText(e)
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.accent) {
            Tab(tab == 0, { tab = 0 }) { Text("开启 ${open.size}", fontSize = 12.sp) }
            Tab(tab == 1, { tab = 1 }) { Text("已关闭 ${closed.size}", fontSize = 12.sp) }
        }
        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!)
            else -> {
                val list = if (tab == 0) open else closed
                if (list.isEmpty()) CenteredText(if (tab == 0) "没有开启的 PR" else "没有已关闭的 PR")
                else LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(list) { p ->
                        PrRow(p) { onOpenDetail(p.number) }
                        Divider(color = c.borderMuted, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrRow(p: GitHubApi.PrInfo, onClick: () -> Unit) {
    val c = LocalHubColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            HubIcon(
                if (p.merged) "pr" else if (p.draft) "book" else "pr",
                Modifier.size(15.dp).padding(top = 2.dp),
                when { p.merged -> c.done; p.draft -> c.fgSubtle; else -> c.success }
            )
            Spacer(Modifier.width(8.dp))
            Text(p.title, color = c.fg, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${p.number}", color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(p.user, color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(GitHubApi.timeAgo(p.createdAt), color = c.fgMuted, fontSize = 11.sp)
            if (p.merged) {
                Spacer(Modifier.width(8.dp))
                Text("已合并", color = c.done, fontSize = 11.sp)
            }
            if (p.draft) {
                Spacer(Modifier.width(8.dp))
                Text("草稿", color = c.fgSubtle, fontSize = 11.sp)
            }
        }
    }
}

/* ---------------- Actions ---------------- */

@Composable
fun ActionsScreen(owner: String, name: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var list by remember { mutableStateOf<List<GitHubApi.RunInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name) {
        loading = true; err = null
        try {
            list = withContext(Dispatchers.IO) { GitHubApi.runs(ctx, owner, name) }
            loading = false
        } catch (e: Throwable) {
                /* 协程取消必须原样抛出：吞掉它会破坏结构化并发，
                   而且 CancellationException 的 message 恒为 null */
               if (e is kotlinx.coroutines.CancellationException) throw e
               err = errText(e)
            loading = false
        }
    }

    when {
        loading -> CenteredLoading()
        err != null -> CenteredText(err!!)
        list.isEmpty() -> CenteredText("还没有工作流运行记录")
        else -> LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { r ->
                RunRow(r) {
                    onOpen("https://github.com/$owner/$name/actions/runs/${r.id}")
                }
                Divider(color = c.borderMuted, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun RunRow(r: GitHubApi.RunInfo, onClick: () -> Unit) {
    val c = LocalHubColors.current
    val ok = "success" == r.conclusion
    val fail = r.conclusion.isNotBlank() && !ok
    val dot = when {
        r.status == "in_progress" || r.status == "queued" -> c.attention
        ok -> c.success
        fail -> c.danger
        else -> c.fgSubtle
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, color = c.fg, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row {
                Text("#${r.number}", color = c.fgMuted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(r.branch, color = c.fgMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(r.event, color = c.fgSubtle, fontSize = 11.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(GitHubApi.timeAgo(r.createdAt), color = c.fgSubtle, fontSize = 11.sp)
        }
    }
}

/* ---------------- 发布 Releases ---------------- */

@Composable
fun ReleasesScreen(owner: String, name: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var list by remember { mutableStateOf<List<GitHubApi.ReleaseInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name) {
        loading = true; err = null
        try {
            list = withContext(Dispatchers.IO) { GitHubApi.releases(ctx, owner, name) }
            loading = false
        } catch (e: Throwable) {
                /* 协程取消必须原样抛出：吞掉它会破坏结构化并发，
                   而且 CancellationException 的 message 恒为 null */
               if (e is kotlinx.coroutines.CancellationException) throw e
               err = errText(e)
            loading = false
        }
    }

    when {
        loading -> CenteredLoading()
        err != null -> CenteredText(err!!)
        list.isEmpty() -> CenteredText("还没有发布版本")
        else -> LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { r ->
                Column(Modifier.fillMaxWidth()
                    .clickable { onOpen("https://github.com/$owner/$name/releases/tag/${r.tag}") }
                    .padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HubIcon("tag", Modifier.size(14.dp), c.success)
                        Spacer(Modifier.width(7.dp))
                        Text(r.tag, color = c.fg, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (r.prerelease) {
                            Box(Modifier.background(c.neutralSubtle, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)) {
                                Text("预发布", color = c.fgMuted, fontSize = 10.sp)
                            }
                        }
                        if (r.draft) {
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.background(c.neutralSubtle, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)) {
                                Text("草稿", color = c.fgMuted, fontSize = 10.sp)
                            }
                        }
                    }
                    if (r.name.isNotBlank() && r.name != r.tag) {
                        Spacer(Modifier.height(4.dp))
                        Text(r.name, color = c.fgMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(GitHubApi.timeAgo(r.publishedAt), color = c.fgSubtle, fontSize = 11.sp)
                    if (r.assets.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        r.assets.take(3).forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HubIcon("download", Modifier.size(11.dp), c.fgSubtle)
                                Spacer(Modifier.width(5.dp))
                                Text(a.name, color = c.fgMuted, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text(GitHubApi.humanSize(a.size), color = c.fgSubtle,
                                    fontSize = 11.sp)
                            }
                        }
                    }
                }
                Divider(color = c.borderMuted, thickness = 0.5.dp)
            }
        }
    }
}

/* ---------------- 更多：提交 / 分支 / 贡献者 ---------------- */

@Composable
fun MoreScreen(owner: String, name: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var tab by remember { mutableStateOf(0) }
    var commits by remember { mutableStateOf<List<GitHubApi.CommitInfo>>(emptyList()) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var contribs by remember { mutableStateOf<List<GitHubApi.ContributorInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name) {
        loading = true; err = null
        try {
            commits = withContext(Dispatchers.IO) { GitHubApi.commits(ctx, owner, name) }
            branches = withContext(Dispatchers.IO) { GitHubApi.branches(ctx, owner, name) }
            contribs = withContext(Dispatchers.IO) { GitHubApi.contributors(ctx, owner, name) }
            loading = false
        } catch (e: Throwable) {
                /* 协程取消必须原样抛出：吞掉它会破坏结构化并发，
                   而且 CancellationException 的 message 恒为 null */
               if (e is kotlinx.coroutines.CancellationException) throw e
               err = errText(e)
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.accent) {
            Tab(tab == 0, { tab = 0 }) { Text("提交", fontSize = 12.sp) }
            Tab(tab == 1, { tab = 1 }) { Text("分支 ${branches.size}", fontSize = 12.sp) }
            Tab(tab == 2, { tab = 2 }) { Text("贡献者", fontSize = 12.sp) }
        }
        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!)
            tab == 0 -> LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(commits) { cm ->
                    Column(Modifier.fillMaxWidth()
                        .clickable { onOpen("https://github.com/$owner/$name/commit/${cm.sha}") }
                        .padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(cm.message.substringBefore('\n'), color = c.fg,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Row {
                            Text(cm.author, color = c.fgMuted, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(cm.sha.take(7), color = c.fgSubtle, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(GitHubApi.timeAgo(cm.date), color = c.fgSubtle, fontSize = 11.sp)
                        }
                    }
                    Divider(color = c.borderMuted, thickness = 0.5.dp)
                }
            }
            tab == 1 -> LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(branches) { b ->
                    Row(Modifier.fillMaxWidth()
                        .clickable { onOpen("https://github.com/$owner/$name/tree/$b") }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        HubIcon("fork", Modifier.size(14.dp), c.fgMuted)
                        Spacer(Modifier.width(9.dp))
                        Text(b, color = c.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        HubIcon("chevron-right", Modifier.size(14.dp), c.fgSubtle)
                    }
                    Divider(color = c.borderMuted, thickness = 0.5.dp)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(contribs) { ct ->
                    Row(Modifier.fillMaxWidth()
                        .clickable { onOpen("https://github.com/${ct.login}") }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp)
                            .background(c.neutralSubtle, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(ct.login, color = c.fg, fontSize = 13.sp,
                            modifier = Modifier.weight(1f))
                        Text("${ct.contributions} 次提交", color = c.fgMuted, fontSize = 11.sp)
                    }
                    Divider(color = c.borderMuted, thickness = 0.5.dp)
                }
            }
        }
    }
}

/* ---------------- 用户动态 ---------------- */

@Composable
fun EventsList(login: String, onOpenRepo: (String, String) -> Unit, onOpenUser: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var list by remember { mutableStateOf<List<GitHubApi.EventInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(login) {
        loading = true; err = null
        try {
            list = withContext(Dispatchers.IO) { GitHubApi.events(ctx, login) }
            loading = false
        } catch (e: Throwable) {
                /* 协程取消必须原样抛出：吞掉它会破坏结构化并发，
                   而且 CancellationException 的 message 恒为 null */
               if (e is kotlinx.coroutines.CancellationException) throw e
               err = errText(e)
            loading = false
        }
    }

    when {
        loading -> SkeletonList(4)
        err != null -> CenteredText(err!!)
        list.isEmpty() -> CenteredText("还没有动态")
        else -> LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Box(Modifier.size(30.dp)
                        .background(c.neutralSubtle, androidx.compose.foundation.shape.CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(e.actor, color = c.fg, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { onOpenUser(e.actor) })
                            Spacer(Modifier.width(5.dp))
                            Text(e.title, color = c.fgMuted, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        }
                        if (e.repoName.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(e.repoName, color = c.accent, fontSize = 11.sp,
                                modifier = Modifier.clickable {
                                    val p = e.repoName.split('/')
                                    if (p.size == 2) onOpenRepo(p[0], p[1])
                                })
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(GitHubApi.timeAgo(e.createdAt), color = c.fgSubtle, fontSize = 11.sp)
                    }
                }
                Divider(color = c.borderMuted, thickness = 0.5.dp)
            }
        }
    }
}

/** 用户小行：粉丝 / 关注 / 组织列表共用 */
@Composable
fun UserRow(u: GitHubApi.UserInfo, onClick: () -> Unit) {
    val c = LocalHubColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp)
            .background(c.neutralSubtle, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(u.login, color = c.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (u.name.isNotBlank() && u.name != u.login) {
                Text(u.name, color = c.fgMuted, fontSize = 11.sp)
            }
        }
        HubIcon("chevron-right", Modifier.size(14.dp), c.fgSubtle)
    }
    Divider(color = c.borderMuted, thickness = 0.5.dp)
}
