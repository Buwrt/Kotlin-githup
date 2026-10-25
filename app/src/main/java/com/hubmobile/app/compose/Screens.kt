package com.hubmobile.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.app.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 页面 —— 1:1 对齐网页版结构。
 *
 * 对照的是 assets/web/js/page-home.js 的 P.feed.render、P.explore、
 * 以及 repoRow()。布局顺序、文案、格式化（num / timeAgo / 语言色点）
 * 全部照抄前端，不再自己发挥。
 *
 * 配色走 LocalHubColors（由 HubTheme 提供，浅色深色两套）。
 */

private val C get() = HubColors   // 深色常量兜底
private const val CARD_R = 12
private const val PAGE_PAD = 12

/* ---------------- 通用件 ---------------- */

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp),
            color = LocalHubColors.current.accent)
    }
}

@Composable
fun CenteredText(msg: String, action: (@Composable () -> Unit)? = null) {
    val c = LocalHubColors.current
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HubIcon("alert", Modifier.size(36.dp), c.fgSubtle)
            Spacer(Modifier.height(8.dp))
            Text(msg, color = c.fgMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
            if (action != null) { Spacer(Modifier.height(10.dp)); action() }
        }
    }
}

@Composable
fun Avatar(bitmap: android.graphics.Bitmap?, size: Int = 32) {
    val c = LocalHubColors.current
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape).background(c.neutralSubtle))
    }
}

/** 卡片：网页版 .card —— 白/深底 + 圆角 + 细边 */
@Composable
fun HubCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalHubColors.current
    Column(modifier.fillMaxWidth()
        .background(c.bg, RoundedCornerShape(CARD_R.dp))
        .border(1.dp, c.borderMuted, RoundedCornerShape(CARD_R.dp))) {
        content()
    }
}

/** 分区标题：网页版 .section-title —— 图标 + 文字 + 右侧操作 */
@Composable
fun SectionTitle(text: String, icon: String? = null, actions: (@Composable () -> Unit)? = null) {
    val c = LocalHubColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { HubIcon(icon, Modifier.size(14.dp), c.fgMuted); Spacer(Modifier.width(6.dp)) }
        Text(text, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (actions != null) actions()
    }
}

/** chip：网页版 .chip —— 标签、筛选按钮 */
@Composable
fun HubChip(text: String, active: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalHubColors.current
    val bg = if (active) c.accentSubtle else Color.Transparent
    val fg = if (active) c.accent else c.fgMuted
    Box(
        Modifier.padding(end = 6.dp, bottom = 6.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, if (active) c.accent else c.border, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = fg, fontSize = 12.sp)
    }
}

/** 分段控件：网页版 UI.seg —— 探索页的 今日/本周/本月 */
@Composable
fun HubSeg(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    val c = LocalHubColors.current
    Row(Modifier.fillMaxWidth()
        .background(c.bgHi, RoundedCornerShape(8.dp))
        .padding(3.dp)) {
        options.forEach { (key, label) ->
            val sel = key == current
            Box(
                Modifier.weight(1f)
                    .background(if (sel) c.bg else Color.Transparent,
                        RoundedCornerShape(6.dp))
                    .clickable { onPick(key) }
                    .padding(vertical = 7.dp),
                Alignment.Center
            ) {
                Text(label, color = if (sel) c.fg else c.fgMuted, fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 骨架屏：网页版 UI.skeleton */
@Composable
fun SkeletonList(n: Int = 3) {
    val c = LocalHubColors.current
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        repeat(n) {
            Box(Modifier.fillMaxWidth().height(14.dp)
                .background(c.neutralSubtle, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(11.dp)
                .background(c.neutralSubtle, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ---------------- 仓库行：1:1 对齐 repoRow() ---------------- */

/**
 * 对齐 page-home.js 的 repoRow()：
 *   · 标题行：full_name + 「私有」chip（私有仓库）
 *   · 描述行：只在有值时渲染（网页版是 `r.description ? ... : ''`）
 *   · meta 行：语言色点 + 语言 / star 数 / fork 数 / timeAgo
 */
@Composable
fun RepoRow(
    r: RepoInfo,
    onClick: () -> Unit,
    avatar: android.graphics.Bitmap? = null,
    onOwner: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null
) {
    val c = LocalHubColors.current
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onOwner != null) {
                Avatar(avatar, 20)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                r.fullName, color = c.accent, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (r.private) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.background(c.neutralSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)) {
                    Text("私有", color = c.fgMuted, fontSize = 10.sp)
                }
            }
        }
        if (r.description.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(r.description, color = c.fgMuted, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.language.isNotBlank()) {
                Box(Modifier.size(8.dp).clip(CircleShape)
                    .background(Color(GitHubApi.langColor(r.language))))
                Spacer(Modifier.width(5.dp))
                Text(r.language, color = c.fgMuted, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
            }
            HubIcon("star", Modifier.size(12.dp), c.fgMuted)
            Spacer(Modifier.width(3.dp))
            Text(GitHubApi.num(r.stars), color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            HubIcon("fork", Modifier.size(12.dp), c.fgMuted)
            Spacer(Modifier.width(3.dp))
            Text(GitHubApi.num(r.forks), color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
            Text(GitHubApi.timeAgo(r.updatedAt), color = c.fgMuted, fontSize = 11.sp)
        }
        if (extra != null) { Spacer(Modifier.height(6.dp)); extra() }
    }
    Divider(color = c.borderMuted, thickness = 0.5.dp)
}

/** 仓库列表（带 lazy 滚动） */
@Composable
fun RepoList(items: List<RepoInfo>, onOpen: (RepoInfo) -> Unit) {
    val c = LocalHubColors.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items) { r ->
            RepoRow(r, { onOpen(r) })
        }
    }
}

/* ---------------- 首页：对齐 P.feed.render ---------------- */

@Composable
fun HomeScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedToken: () -> Unit,
    onNewRepo: () -> Unit,
    onQuick: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var me by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var avatar by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; err = null
        MainScope().launch {
            try {
                val u = withContext(Dispatchers.IO) { GitHubApi.me(ctx) }
                if (u == null) { err = "未登录"; loading = false; return@launch }
                me = u
                avatar = withContext(Dispatchers.IO) { GitHubApi.loadBitmap(ctx, u.avatarUrl) }
                // /user/repos?sort=updated&per_page=30，再过滤掉 fork
                // （网页版：过滤后若为空则退回原列表）
                val all = withContext(Dispatchers.IO) { GitHubApi.myRepos(ctx) }
                val noFork = all.filter { !it.fork }
                repos = (if (noFork.isNotEmpty()) noFork else all).take(30)
                loading = false
            } catch (e: Throwable) {
                err = when (e) {
                    is ApiError -> if (e.code == 401) "令牌无效或已过期" else "HTTP ${e.code}"
                    else -> e.message
                }
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    when {
        loading -> { SkeletonList(3); CenteredLoading() }
        err == "未登录" -> CenteredText("登录后可查看动态、通知，并参与 Issue / PR 协作") {
            Button(onClick = onNeedToken,
                colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
                Text("立即登录", color = Color.White)
            }
        }
        err != null -> CenteredText(err!!) { TextButton(onClick = { load() }) { Text("重试") } }
        else -> {
            val u = me!!
            LazyColumn(
                Modifier.fillMaxSize().background(c.canvas),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // 1) 用户信息卡 + 快捷宫格
                item {
                    Column(Modifier.padding(PAGE_PAD.dp)) {
                        HubCard {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(avatar, 40)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.name.ifBlank { u.login }, color = c.fg,
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    Text("@${u.login}", color = c.fgMuted, fontSize = 12.sp)
                                }
                                HubIcon("chevron-right", Modifier.size(16.dp), c.fgSubtle)
                            }
                            QuickGrid(u.login, onQuick)
                        }
                    }
                }
                // 2) 「我的仓库」分区标题 + 新建/全部
                item {
                    SectionTitle("我的仓库", icon = "repo") {
                        OutlinedButton(onClick = onNewRepo,
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            HubIcon("plus", Modifier.size(13.dp), c.fgMuted)
                            Spacer(Modifier.width(3.dp))
                            Text("新建", fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { onOpenUser(u.login) },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("全部", fontSize = 11.sp)
                        }
                    }
                }
                // 3) 仓库列表
                if (repos.isEmpty()) {
                    item { CenteredText("还没有仓库，点击「新建」创建第一个仓库") }
                } else {
                    items(repos) { r ->
                        Box(Modifier.background(c.bg)) {
                            RepoRow(r, { onOpenRepo(r.ownerLogin, r.name) })
                        }
                    }
                }
            }
        }
    }
}

/** 快捷宫格：对齐 page-home.js 的 quickGrid() —— 3 列 × 2 行 */
@Composable
private fun QuickGrid(login: String, onQuick: (String) -> Unit) {
    val c = LocalHubColors.current
    // 对齐 page-home.js 的 quickGrid()：键名用 path，这里简化成标记
    val items = listOf(
        "repo" to "我的仓库" to "repos",
        "star" to "我的 Star" to "stars",
        "issue" to "我的议题" to "issues",
        "pr" to "我的 PR" to "pulls",
        "code-square" to "我的 Gist" to "gists",
        "organization" to "组织" to "orgs"
    )
    Column {
        Divider(color = c.borderMuted, thickness = 0.5.dp)
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (pair, key) ->
                    val (icon, label) = pair
                    Box(
                        Modifier.weight(1f)
                            .clickable { onQuick(key) }
                            .padding(vertical = 14.dp),
                        Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HubIcon(icon, Modifier.size(20.dp), c.fgMuted)
                            Spacer(Modifier.height(6.dp))
                            Text(label, color = c.fgMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 探索：对齐 P.explore ---------------- */

@Composable
fun ExploreScreen(onOpenRepo: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var range by remember { mutableStateOf("daily") }
    var lang by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(range, lang) {
        loading = true; err = null
        try {
            list = withContext(Dispatchers.IO) { GitHubApi.trending(ctx, range, lang) }
            loading = false
        } catch (e: Throwable) {
            err = when (e) {
                is ApiError -> if (e.code == 403) "被限流了，稍后再试" else "HTTP ${e.code}"
                else -> e.message
            }
            loading = false
        }
    }

    val langs = listOf("", "TypeScript", "JavaScript", "Python", "Go", "Rust",
        "Java", "Kotlin", "Swift", "C++", "Vue", "HTML")

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            HubSeg(listOf("daily" to "今日", "weekly" to "本周", "monthly" to "本月"),
                range) { range = it }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            langs.forEach { l ->
                HubChip(if (l.isEmpty()) "全部语言" else l, l == lang) { lang = l }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            loading -> SkeletonList(4)
            err != null -> CenteredText(err!!)
            list.isEmpty() -> CenteredText("暂无数据，请稍后重试")
            else -> RepoList(list) { onOpenRepo(it.ownerLogin, it.name) }
        }
    }
}

/* ---------------- 通知 ---------------- */

@Composable
fun NotificationsScreen(onOpen: (String) -> Unit, onMarkAllRead: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var list by remember { mutableStateOf<List<GitHubApi.NotifInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true; err = null
        try {
            list = withContext(Dispatchers.IO) { GitHubApi.notifications(ctx) }
            loading = false
        } catch (e: Throwable) {
            err = when (e) {
                is ApiError -> if (e.code == 401) "需要登录才能查看通知" else "HTTP ${e.code}"
                else -> e.message
            }
            loading = false
        }
    }

    val unread = list.count { it.unread }
    Column(Modifier.fillMaxSize().background(c.canvas)) {
        if (unread > 0 && onMarkAllRead != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("$unread 条未读", color = c.fgMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onMarkAllRead,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("全部标为已读", fontSize = 11.sp)
                }
            }
        }
    when {
        loading -> SkeletonList(4)
        err != null -> CenteredText(err!!)
        list.isEmpty() -> CenteredText("没有新通知")
        else -> LazyColumn(Modifier.fillMaxSize().background(c.canvas),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list) { n -> NotifRow(n) { onOpen(GitHubApi.notifWebUrl(n)) } }
        }
    }
    }
}

@Composable
private fun NotifRow(n: GitHubApi.NotifInfo, onClick: () -> Unit) {
    val c = LocalHubColors.current
    Row(
        Modifier.fillMaxWidth().background(if (n.unread) c.bg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        HubIcon(
            when (n.type) {
                "PullRequest" -> "pr"; "Issue" -> "issue"
                "Release" -> "zap"; else -> "bell"
            },
            Modifier.size(16.dp).padding(top = 2.dp),
            if (n.unread) c.accent else c.fgSubtle
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, color = c.fg, fontSize = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (n.unread) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.height(4.dp))
            Text(n.repoFullName, color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.height(3.dp))
            Row {
                Text(GitHubApi.timeAgo(n.updatedAt), color = c.fgSubtle, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(n.reason, color = c.fgSubtle, fontSize = 11.sp)
            }
        }
        if (n.unread) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        }
    }
    Divider(color = c.borderMuted, thickness = 0.5.dp)
}

/* ---------------- 搜索 ---------------- */

@Composable
fun SearchScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenWeb: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var repos by remember { mutableStateOf<GitHubApi.SearchResult?>(null) }
    var users by remember { mutableStateOf<GitHubApi.SearchUsersResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    /*
     * 网页版搜索支持：仓库 / 用户组织，以及直接粘贴一条 GitHub 链接
     * （https://github.com/owner/repo、.../issues/123 等）自动识别跳转。
     * 这里照做：输入以 http 开头时先走链接识别。
     */
    fun isUrl(s: String): Boolean =
        s.startsWith("http://") || s.startsWith("https://")

    fun run() {
        val text = q.trim()
        if (text.isBlank()) return
        if (isUrl(text)) { onOpenWeb(text); return }
        loading = true; err = null
        MainScope().launch {
            try {
                if (tab == 0) repos = withContext(Dispatchers.IO) {
                    GitHubApi.searchRepos(ctx, text)
                }
                else users = withContext(Dispatchers.IO) {
                    GitHubApi.searchUsers(ctx, text)
                }
                loading = false
            } catch (e: Throwable) {
                err = if (e is ApiError) {
                    if (e.code == 403) "被限流了，稍后再试" else "HTTP ${e.code}"
                } else e.message
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.canvas)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("搜索仓库 / 用户，或粘贴 GitHub 链接", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border
            )
        )
        Row(Modifier.padding(horizontal = 12.dp)) {
            HubChip("仓库", tab == 0) { tab = 0 }
            HubChip("用户 / 组织", tab == 1) { tab = 1 }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { run() },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text("搜索", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            loading -> SkeletonList(4)
            err != null -> CenteredText(err!!) { TextButton(onClick = { run() }) { Text("重试") } }
            tab == 0 && repos == null -> CenteredText("输入关键词搜索仓库")
            tab == 1 && users == null -> CenteredText("输入关键词搜索用户")
            tab == 0 -> {
                val res = repos!!
                Text("共 ${res.total} 个结果", color = c.fgMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                RepoList(res.items) { onOpenRepo(it.ownerLogin, it.name) }
            }
            else -> {
                val res = users!!
                Text("共 ${res.total} 个结果", color = c.fgMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                LazyColumn(Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(res.items) { u -> UserRow(u) { onOpenUser(u.login) } }
                }
            }
        }
    }
}


@Composable
fun UserScreen(
    login: String,
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenWeb: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    /*
     * tab 对齐 page-user.js 顶部那排：
     *   概览 / 仓库 / Star / Gist / 动态 / 粉丝 / 关注 / 组织
     */
    val tabs = listOf("概览", "仓库", "Star", "Gist", "动态", "粉丝", "关注", "组织")
    var tab by remember { mutableStateOf(0) }
    var u by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var avatar by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var starred by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var gists by remember { mutableStateOf<List<GitHubApi.GistInfo>>(emptyList()) }
    var people by remember { mutableStateOf<List<GitHubApi.UserInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(login) {
        loading = true; err = null
        try {
            val info = withContext(Dispatchers.IO) { GitHubApi.user(ctx, login) }
            u = info
            avatar = withContext(Dispatchers.IO) { GitHubApi.loadBitmap(ctx, info.avatarUrl) }
            repos = withContext(Dispatchers.IO) { GitHubApi.userRepos(ctx, login) }
            loading = false
        } catch (e: Throwable) {
            err = if (e is ApiError) {
                if (e.code == 404) "用户不存在" else "HTTP ${e.code}"
            } else e.message
            loading = false
        }
    }

    // 切到需要额外请求的 tab 时才去取，避免首屏一次打 8 个接口
    LaunchedEffect(login, tab) {
        val info = u ?: return@LaunchedEffect
        try {
            when (tab) {
                2 -> if (starred.isEmpty()) starred = withContext(Dispatchers.IO) {
                    GitHubApi.starred(ctx, info.login)
                }
                3 -> if (gists.isEmpty()) gists = withContext(Dispatchers.IO) {
                    GitHubApi.gists(ctx, info.login)
                }
                5 -> { people = emptyList()
                    people = withContext(Dispatchers.IO) { GitHubApi.followers(ctx, info.login) } }
                6 -> { people = emptyList()
                    people = withContext(Dispatchers.IO) { GitHubApi.following(ctx, info.login) } }
                7 -> { people = emptyList()
                    people = withContext(Dispatchers.IO) { GitHubApi.orgs(ctx, info.login) } }
            }
        } catch (e: Throwable) { /* 子 tab 失败不整页报错 */ }
    }

    when {
        loading -> SkeletonList(3)
        err != null -> CenteredText(err!!)
        else -> {
            val info = u!!
            LazyColumn(Modifier.fillMaxSize().background(c.canvas),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(12.dp)) {
                        HubCard {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(avatar, 56)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(info.name.ifBlank { info.login }, color = c.fg,
                                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text("@${info.login}", color = c.fgMuted, fontSize = 12.sp)
                                    }
                                }
                                if (info.bio.isNotBlank()) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(info.bio, color = c.fgMuted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.height(12.dp))
                                Row {
                                    Text("${GitHubApi.num(info.publicRepos)} 仓库",
                                        color = c.fgMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(16.dp))
                                    Text("${GitHubApi.num(info.followers)} 粉丝",
                                        color = c.fgMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(16.dp))
                                    Text("关注 ${GitHubApi.num(info.following)}",
                                        color = c.fgMuted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)) {
                        tabs.forEachIndexed { i, t -> HubChip(t, tab == i) { tab = i } }
                    }
                }
                when (tab) {
                    0, 4 -> item { EventsList(info.login, onOpenRepo, onOpenUser) }
                    1 -> if (repos.isEmpty()) item { CenteredText("还没有公开仓库") }
                    else items(repos) { r ->
                        Box(Modifier.background(c.bg)) {
                            RepoRow(r, { onOpenRepo(r.ownerLogin, r.name) })
                        }
                    }
                    2 -> if (starred.isEmpty()) item { CenteredText("还没有 Star") }
                    else items(starred) { r ->
                        Box(Modifier.background(c.bg)) {
                            RepoRow(r, { onOpenRepo(r.ownerLogin, r.name) })
                        }
                    }
                    3 -> if (gists.isEmpty()) item { CenteredText("还没有 Gist") }
                    else items(gists) { g ->
                        Column(Modifier.fillMaxWidth().background(c.bg)
                            .clickable { onOpenWeb("https://gist.github.com/${g.id}") }
                            .padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(g.description.ifBlank { g.files.firstOrNull() ?: "未命名" },
                                color = c.fg, fontSize = 13.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Row {
                                Text(g.files.firstOrNull() ?: "", color = c.fgMuted,
                                    fontSize = 11.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(GitHubApi.timeAgo(g.updatedAt), color = c.fgSubtle,
                                    fontSize = 11.sp)
                            }
                        }
                        Divider(color = c.borderMuted, thickness = 0.5.dp)
                    }
                    else -> if (people.isEmpty()) item {
                        CenteredText(if (tab == 5) "还没有粉丝"
                        else if (tab == 6) "还没有关注任何人" else "还没有加入组织")
                    } else items(people) { pu ->
                        Box(Modifier.background(c.bg)) { UserRow(pu) { onOpenUser(pu.login) } }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onOpenWeb: (String) -> Unit = {},
    onLogout: () -> Unit = {},
    onRestart: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var token by remember { mutableStateOf(SecurePrefs.get(ctx, GitHubApi.TOKEN_KEY, "")) }
    var tip by remember { mutableStateOf<String?>(null) }
    var theme by remember { mutableStateOf(Prefs.theme(ctx)) }
    var font by remember { mutableStateOf(Prefs.codeFont(ctx)) }
    var start by remember { mutableStateOf(Prefs.startTab(ctx)) }
    var quota by remember { mutableStateOf<GitHubApi.RateLimit?>(null) }
    var showTheme by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(false) }

    // 进设置时顺手拉一次配额
    LaunchedEffect(Unit) {
        quota = try {
            withContext(Dispatchers.IO) { GitHubApi.rateLimit(ctx) }
        } catch (e: Throwable) { null }
    }

    /** 设置行：图标 + 标题 + 当前值 + 箭头 */
    @Composable
    fun SetRow(icon: String, title: String, value: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().background(c.bg).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            HubIcon(icon, Modifier.size(16.dp), c.fgMuted)
            Spacer(Modifier.width(12.dp))
            Text(title, color = c.fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) {
                Text(value, color = c.fgSubtle, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
            }
            HubIcon("chevron-right", Modifier.size(14.dp), c.fgSubtle)
        }
        Divider(color = c.borderMuted, thickness = 0.5.dp)
    }

    LazyColumn(Modifier.fillMaxSize().background(c.canvas),
        contentPadding = PaddingValues(bottom = 24.dp)) {

        item {
            Column(Modifier.padding(12.dp)) {
                HubCard {
                    Column(Modifier.padding(14.dp)) {
                        Text("访问令牌", color = c.fg, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("令牌存在本机 Keystore 加密区里，不会上传。",
                            color = c.fgMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = token, onValueChange = { token = it.trim() },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("ghp_ 或 github_pat_ 开头", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                                cursorColor = c.accent, focusedBorderColor = c.accent,
                                unfocusedBorderColor = c.border
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Button(onClick = {
                                SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, token)
                                tip = "已保存"
                            }, colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
                                Text("保存", color = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                onOpenWeb("https://github.com/settings/tokens/new?" +
                                    "scopes=repo,read:user,workflow&description=githup")
                            }) { Text("去创建", color = c.accent, fontSize = 12.sp) }
                        }
                        if (tip != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(tip!!, color = c.success, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        item { SectionTitle("外观") }
        item {
            val text = when (theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }
            SetRow("paintbrush", "主题外观", text) { showTheme = true }
            SetRow("typography", "代码字号", "${font}px") { showFont = true }
            SetRow("home", "启动页", when (start) {
                "notifications" -> "通知"; "explore" -> "探索"; "profile" -> "我的"; else -> "首页"
            }) { showStart = true }
        }

        item { SectionTitle("账号") }
        item {
            val q = quota
            SetRow("graph", "API 配额",
                if (q != null) "剩余 ${q.remaining} / ${q.limit}" else "读取中") {
                MainScope().launch {
                    quota = try {
                        withContext(Dispatchers.IO) { GitHubApi.rateLimit(ctx) }
                    } catch (e: Throwable) { null }
                }
            }
            SetRow("book", "两步验证器", "") {
                onOpenWeb("https://github.com/settings/security")
            }
            SetRow("logout", "退出登录", "") {
                SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, "")
                token = ""
                onLogout()
            }
        }

        item { SectionTitle("关于") }
        item {
            SetRow("sync", "检查更新", "") {
                onOpenWeb("https://github.com/Buwrt/githup/releases/latest")
            }
            SetRow("alert", "清除缓存", "") {
                // WebView 缓存 + 应用缓存目录，尽力清
                try { ctx.cacheDir.deleteRecursively() } catch (e: Throwable) {}
                tip = "缓存已清除"
            }
            SetRow("mark-github", "源码仓库", "") {
                onOpenWeb("https://github.com/Buwrt/Kotlin-githup")
            }
        }
    }

    // ---- 三个选择弹层 ----
    if (showTheme) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTheme = false },
            title = { Text("主题外观", fontSize = 15.sp) },
            text = {
                Column {
                    listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            theme = k; Prefs.setTheme(ctx, k); showTheme = false; onRestart()
                        }.padding(vertical = 12.dp)) {
                            Text(label, color = c.fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            if (theme == k) Text("✓", color = c.accent, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
    if (showFont) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFont = false },
            title = { Text("代码字号", fontSize = 15.sp) },
            text = {
                Column {
                    listOf(12, 13, 15, 17).forEach { v ->
                        Row(Modifier.fillMaxWidth().clickable {
                            font = v; Prefs.setCodeFont(ctx, v); showFont = false
                        }.padding(vertical = 12.dp)) {
                            Text("${v}px", color = c.fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            if (font == v) Text("✓", color = c.accent, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
    if (showStart) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStart = false },
            title = { Text("启动页", fontSize = 15.sp) },
            text = {
                Column {
                    listOf("home" to "首页", "notifications" to "通知",
                        "explore" to "探索", "profile" to "我的").forEach { (k, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            start = k; Prefs.setStartTab(ctx, k); showStart = false
                        }.padding(vertical = 12.dp)) {
                            Text(label, color = c.fg, fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            if (start == k) Text("✓", color = c.accent, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}


/** 保留给早期页面的兼容函数 */
internal fun compact(n: Int): String = GitHubApi.num(n)
