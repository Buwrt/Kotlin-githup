package com.hubmobile.app.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.MainScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 路线 B 的第一个原生页面：仓库详情。
 *
 * 为什么先选它：
 *   1. 它是网页版最重的一块（page-repo.js 300KB），也是最容易被吐槽
 *      「列表卡顿」的地方 —— 换成 LazyColumn 后收益最直观。
 *   2. 它只读（渲染 + 浏览），不涉及写操作（发 issue / 传文件），
 *      出错的代价低，适合当试验田。
 *   3. 它把「网络 + 图片 + 列表 + 状态机」四件事都走了一遍，
 *      这套骨架跑通后，其余页面照抄结构即可。
 *
 * tab 结构对齐 page-repo.js 的 TABS：
 *   代码 / 议题 / 拉取请求 / Actions / 发布 / 更多
 * 「更多」里是 提交 / 分支 / 贡献者。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onOpenInWeb: () -> Unit,
    onOpenWeb: (String) -> Unit,
    onOpenDetail: (Int, Boolean) -> Unit = { n, isPr ->
        onOpenWeb("https://github.com/$owner/$name/" +
            (if (isPr) "pull" else "issues") + "/$n")
    },
    onOpenOwner: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<RepoInfo?>(null) }
    var entries by remember { mutableStateOf<List<ContentEntry>>(emptyList()) }
    var branches by remember { mutableStateOf<List<String>>(emptyList()) }
    var branch by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    // 对齐 page-repo.js 的 TABS
    var tab by remember { mutableStateOf(0) }

    // 加载目录内容：切换分支 / 点进子目录都走这里
    val loadDir: suspend (String) -> Unit = { p ->
        loading = true
        err = null
        try {
            val es = withContext(Dispatchers.IO) {
                GitHubApi.contents(ctx, owner, name, p, branch)
            }
            entries = es
            path = p
            loading = false
        } catch (e: Throwable) {
            err = e.message ?: "加载失败"
            loading = false
        }
    }

    LaunchedEffect(owner, name) {
        loading = true
        err = null
        try {
            val r = withContext(Dispatchers.IO) { GitHubApi.repo(ctx, owner, name) }
            repo = r
            branch = r.defaultBranch
            avatar = withContext(Dispatchers.IO) { GitHubApi.loadBitmap(ctx, r.ownerAvatarUrl) }
            branches = withContext(Dispatchers.IO) {
                runCatching { GitHubApi.branches(ctx, owner, name) }.getOrDefault(emptyList())
            }
            withContext(Dispatchers.IO) {
                GitHubApi.contents(ctx, owner, name, "", r.defaultBranch)
            }.let { entries = it }
            loading = false
        } catch (e: Throwable) {
            err = when (e) {
                is ApiError -> when (e.code) {
                    404 -> "仓库不存在，或你没有访问权限（私有仓库需要令牌）"
                    403 -> "被 GitHub 限流了 —— 登录令牌可把额度从 60 次/小时提到 5000"
                    else -> "请求失败（HTTP ${e.code}）"
                }
                else -> e.message ?: "加载失败"
            }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "$owner/$name",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // 不引 material-icons-extended，返回箭头用字符渲染
                        Text("‹", fontSize = 28.sp, fontWeight = FontWeight.Light)
                    }
                },
                actions = {
                    TextButton(onClick = onOpenInWeb) { Text("网页版") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalHubColors.current.bg,
                    titleContentColor = LocalHubColors.current.fg
                )
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                // 首次加载且还没有仓库信息
                loading && repo == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = LocalHubColors.current.accent)
                }
                err != null && repo == null -> ErrorBox(err!!) {
                    loading = true; err = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { GitHubApi.repo(ctx, owner, name) } }
                            .onSuccess { repo = it }
                            .onFailure { err = it.message }
                        loading = false
                    }
                }
                repo != null -> {
                    val r = repo!!
                    // 对齐 page-repo.js 的 TABS：代码/议题/拉取请求/Actions/发布/更多
                    val tabs = listOf("代码", "议题", "拉取请求", "Actions", "发布", "更多")
                    Column(Modifier.fillMaxSize()) {
                        androidx.compose.material3.TabRow(
                            selectedTabIndex = tab,
                            containerColor = LocalHubColors.current.bg,
                            contentColor = LocalHubColors.current.accent
                        ) {
                            tabs.forEachIndexed { i, t ->
                                androidx.compose.material3.Tab(tab == i, { tab = i }) {
                                    Text(t, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                        when (tab) {
                            0 -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item { RepoHeader(r, avatar, onOpenOwner) }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        BranchBox(branch, branches) { b ->
                                            branch = b
                                            scope.launch { loadDir("") }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        OutlinedButton(onClick = onOpenInWeb) {
                                            Text("网页版", fontSize = 12.sp)
                                        }
                                    }
                                }
                                item {
                                    PathBar(path) { target ->
                                        scope.launch { loadDir(target) }
                                    }
                                }
                                if (loading) {
                                    item {
                                        Box(Modifier.fillMaxWidth().padding(24.dp),
                                            Alignment.Center) {
                                            CircularProgressIndicator(
                                                Modifier.size(20.dp),
                                                color = LocalHubColors.current.accent
                                            )
                                        }
                                    }
                                } else {
                                    items(entries) { e ->
                                        EntryRow(e) {
                                            if (e.isDir) scope.launch { loadDir(e.path) }
                                            else onOpenFile(e.path, branch)
                                        }
                                        Divider(color = LocalHubColors.current.borderMuted,
                                            thickness = 0.5.dp)
                                    }
                                }
                            }
                            1 -> IssuesScreen(owner = owner, name = name,
                                onBack = { tab = 0 }, onOpenWeb = onOpenWeb,
                                onOpenDetail = { n -> onOpenDetail(n, false) })
                            2 -> PullsScreen(owner, name, onOpenWeb) { n ->
                                onOpenDetail(n, true)
                            }
                            3 -> ActionsScreen(owner, name, onOpenWeb)
                            4 -> ReleasesScreen(owner, name, onOpenWeb)
                            else -> MoreScreen(owner, name, onOpenWeb)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoHeader(
    r: RepoInfo,
    avatar: android.graphics.Bitmap?,
    onOpenOwner: () -> Unit
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var starred by remember { mutableStateOf<Boolean?>(null) }
    var watching by remember { mutableStateOf<Boolean?>(null) }
    var stars by remember { mutableStateOf(r.stars) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 进页面时查一次 Star / 关注 状态（没令牌就留空，按钮仍可点）
    LaunchedEffect(r.fullName) {
        starred = try {
            withContext(Dispatchers.IO) { GitHubApi.isStarred(ctx, r.ownerLogin, r.name) }
        } catch (e: Throwable) { null }
        watching = try {
            withContext(Dispatchers.IO) { GitHubApi.isWatching(ctx, r.ownerLogin, r.name) }
        } catch (e: Throwable) { null }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        /* repo-name：owner 可点、name 加粗，后跟「私有」「已归档」chip */
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubIcon(if (r.fork) "fork" else "repo", Modifier.size(16.dp), c.fgMuted)
            Spacer(Modifier.width(6.dp))
            Text(r.ownerLogin, color = c.accent, fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onOpenOwner))
            Text(" / ", color = c.fgSubtle, fontSize = 15.sp)
            Text(r.name, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (r.private) RepoChip("私有")
            if (r.archived) RepoChip("已归档")
        }

        if (r.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(r.description, color = c.fgMuted, fontSize = 13.sp)
        }

        if (r.topics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row {
                r.topics.take(8).forEach { t ->
                    Box(Modifier.padding(end = 5.dp, bottom = 5.dp)
                        .background(c.accentSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text(t, color = c.accent, fontSize = 11.sp)
                    }
                }
            }
        }

        if (r.homepage.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { openWebStatic(ctx, r.homepage) }) {
                HubIcon("link", Modifier.size(14.dp), c.fgMuted)
                Spacer(Modifier.width(6.dp))
                Text(r.homepage.removePrefix("https://").removePrefix("http://"),
                    color = c.accent, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }

        /* repo-stats：star / fork / 关注 / 语言色点 / 许可证 / 默认分支 / 更新于 */
        Spacer(Modifier.height(11.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())) {
            HeadStat("star", GitHubApi.num(stars), "star")
            HeadStat("fork", GitHubApi.num(r.forks), "fork")
            HeadStat("eye", GitHubApi.num(r.watchers), "关注")
            if (r.language.isNotBlank()) {
                Spacer(Modifier.width(14.dp))
                Box(Modifier.size(8.dp).background(
                    androidx.compose.ui.graphics.Color(GitHubApi.langColor(r.language).toULong()),
                    androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(r.language, color = c.fgMuted, fontSize = 12.sp)
            }
            if (r.license.isNotBlank() && r.license != "NOASSERTION") {
                Spacer(Modifier.width(14.dp))
                HubIcon("law", Modifier.size(13.dp), c.fgMuted)
                Spacer(Modifier.width(4.dp))
                Text(r.license, color = c.fgMuted, fontSize = 12.sp)
            }
            if (r.defaultBranch.isNotBlank()) {
                Spacer(Modifier.width(14.dp))
                HubIcon("fork", Modifier.size(13.dp), c.fgMuted)
                Spacer(Modifier.width(4.dp))
                Text(r.defaultBranch, color = c.fgMuted, fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            if (r.updatedAt.isNotBlank()) {
                Spacer(Modifier.width(14.dp))
                HubIcon("history", Modifier.size(13.dp), c.fgMuted)
                Spacer(Modifier.width(4.dp))
                Text("更新于 ${GitHubApi.timeAgo(r.updatedAt)}", color = c.fgMuted,
                    fontSize = 12.sp)
            }
        }

        /* repo-actions：Star / 关注 / Fork —— 网页版这三个按钮就在这里 */
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepoActionButton(
                icon = if (starred == true) "star" else "star",
                text = if (starred == true) "已 Star" else "Star",
                active = starred == true,
                modifier = Modifier.weight(1f)
            ) {
                MainScope().launch {
                    val now = starred == true
                    val ok = withContext(Dispatchers.IO) {
                        if (now) GitHubApi.unstar(ctx, r.ownerLogin, r.name)
                        else GitHubApi.star(ctx, r.ownerLogin, r.name)
                    }
                    if (ok) {
                        starred = !now
                        stars = if (now) (stars - 1).coerceAtLeast(0) else stars + 1
                        toast = if (now) "已取消 Star" else "已 Star"
                    } else toast = "需要登录才能 Star"
                }
            }
            RepoActionButton(
                icon = "eye",
                text = if (watching == true) "已关注" else "关注",
                active = watching == true,
                modifier = Modifier.weight(1f)
            ) {
                MainScope().launch {
                    val now = watching == true
                    val ok = withContext(Dispatchers.IO) {
                        GitHubApi.setWatching(ctx, r.ownerLogin, r.name, !now)
                    }
                    if (ok) {
                        watching = !now
                        toast = if (now) "已取消关注" else "已关注"
                    } else toast = "需要登录才能关注"
                }
            }
            RepoActionButton(
                icon = "fork", text = "Fork", active = false,
                modifier = Modifier.weight(1f)
            ) {
                MainScope().launch {
                    val full = withContext(Dispatchers.IO) {
                        GitHubApi.forkRepo(ctx, r.ownerLogin, r.name)
                    }
                    toast = if (full != null) "已 Fork 到 $full" else "需要登录才能 Fork"
                }
            }
        }

        /* 克隆地址区：HTTPS / SSH 切换 + 复制（对齐 cloneWidget） */
        Spacer(Modifier.height(12.dp))
        CloneBox(r)

        if (toast != null) {
            Spacer(Modifier.height(8.dp))
            Text(toast!!, color = c.fgMuted, fontSize = 11.sp)
        }
    }
    Divider(color = c.borderMuted, thickness = 0.5.dp)
}

/** 私有 / 已归档 这类小标签 */
@Composable
private fun RepoChip(text: String) {
    val c = LocalHubColors.current
    Box(Modifier.padding(start = 6.dp)
        .background(c.bgHi, RoundedCornerShape(10.dp))
        .padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, color = c.fgMuted, fontSize = 10.sp)
    }
}

@Composable
private fun HeadStat(icon: String, value: String, label: String) {
    val c = LocalHubColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        HubIcon(icon, Modifier.size(14.dp), c.fgMuted)
        Spacer(Modifier.width(4.dp))
        Text(value, color = c.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(3.dp))
        Text(label, color = c.fgMuted, fontSize = 12.sp)
    }
    Spacer(Modifier.width(14.dp))
}

@Composable
private fun RepoActionButton(
    icon: String, text: String, active: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val c = LocalHubColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        shape = RoundedCornerShape(6.dp)
    ) {
        HubIcon(icon, Modifier.size(14.dp), if (active) c.attention else c.fgMuted)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, maxLines = 1,
            color = if (active) c.attention else c.fg)
    }
}

/** 克隆地址：HTTPS / SSH 切换 + 复制（对齐 page-repo.js 的 cloneWidget） */
@Composable
private fun CloneBox(r: RepoInfo) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var kind by remember { mutableStateOf("https") }
    val https = r.cloneUrl.ifBlank { "https://github.com/${r.fullName}.git" }
    val ssh = r.sshUrl.ifBlank { "git@github.com:${r.fullName}.git" }
    val url = if (kind == "ssh") ssh else https

    Column {
        Row {
            Box(Modifier.background(
                if (kind == "https") c.accentSubtle else c.bgHi,
                RoundedCornerShape(6.dp))
                .clickable { kind = "https" }
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("HTTPS", color = if (kind == "https") c.accent else c.fgMuted,
                    fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.background(
                if (kind == "ssh") c.accentSubtle else c.bgHi,
                RoundedCornerShape(6.dp))
                .clickable { kind = "ssh" }
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("SSH", color = if (kind == "ssh") c.accent else c.fgMuted,
                    fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                url, color = c.fg, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.weight(1f)
                    .background(c.bgHi, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("clone", url))
                },
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) { Text("复制", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(5.dp))
        Text("点击复制按钮即可复制地址。", color = c.fgSubtle, fontSize = 10.sp)
    }
}

@Composable
private fun BranchBox(current: String, all: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("分支：$current", fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            all.take(50).forEach { b ->
                DropdownMenuItem(
                    text = { Text(b, fontSize = 13.sp) },
                    onClick = { open = false; onPick(b) }
                )
            }
        }
    }
}

/** 面包屑：已经在子目录里时，点任意一级回到那一层。 */
@Composable
private fun PathBar(path: String, onGo: (String) -> Unit) {
    if (path.isEmpty()) return
    val parts = path.split('/')
    var acc = ""
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("根目录", color = LocalHubColors.current.accent, fontSize = 12.sp,
            modifier = Modifier.clickable { onGo("") })
        parts.forEachIndexed { i, p ->
            acc = if (acc.isEmpty()) p else "$acc/$p"
            val target = acc
            Text(" / ", color = LocalHubColors.current.fgMuted, fontSize = 12.sp)
            Text(p, color = if (i == parts.size - 1) LocalHubColors.current.fgMuted else LocalHubColors.current.accent,
                fontSize = 12.sp, modifier = Modifier.clickable { onGo(target) })
        }
    }
}

@Composable
private fun EntryRow(e: ContentEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EntryIcon(e.isDir)
        Spacer(Modifier.width(12.dp))
        Text(
            e.name,
            color = LocalHubColors.current.fg,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!e.isDir && e.size > 0) {
            Text(GitHubApi.humanSize(e.size), color = LocalHubColors.current.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (e.isDir) Text("›", color = LocalHubColors.current.fgMuted, fontSize = 18.sp)
    }
}

/**
 * 文件 / 文件夹图标。
 * 不用 material-icons-extended（那个包会把 APK 撑大好几 MB），
 * 这里直接画两个最简形状。
 */
@Composable
private fun EntryIcon(isDir: Boolean) {
    val c = if (isDir) LocalHubColors.current.accent else LocalHubColors.current.fgMuted
    Canvas(Modifier.size(20.dp)) {
        val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        if (isDir) {
            drawRoundRect(
                color = c,
                topLeft = Offset(0f, size.height * 0.16f),
                size = Size(size.width * 0.42f, size.height * 0.20f),
                cornerRadius = r
            )
            drawRoundRect(
                color = c,
                topLeft = Offset(0f, size.height * 0.32f),
                size = Size(size.width, size.height * 0.62f),
                cornerRadius = r
            )
        } else {
            drawRoundRect(
                color = c,
                topLeft = Offset(size.width * 0.18f, size.height * 0.08f),
                size = Size(size.width * 0.64f, size.height * 0.84f),
                cornerRadius = r
            )
        }
    }
}

@Composable
private fun ErrorBox(msg: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(msg, color = LocalHubColors.current.fgMuted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试", color = LocalHubColors.current.accent) }
        }
    }
}

// compact() 定义在 Screens.kt，本文件直接用
