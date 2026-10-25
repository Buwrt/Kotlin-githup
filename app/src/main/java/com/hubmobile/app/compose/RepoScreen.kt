package com.hubmobile.app.compose

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
 * 没做完的部分（故意留的）：
 *   README 渲染、代码高亮、Issues / PR / Actions 三个子页 ——
 *   这些要等 Markdown 渲染方案定下来再动，先不夹带进来。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onOpenFile: (String, String) -> Unit,
    onOpenInWeb: () -> Unit,
    onOpenIssues: () -> Unit
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
                    containerColor = HubColors.Surface,
                    titleContentColor = HubColors.Text
                )
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                // 首次加载且还没有仓库信息
                loading && repo == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = HubColors.Accent)
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        item { RepoHeader(r, avatar) }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BranchBox(branch, branches) { b ->
                                    branch = b
                                    scope.launch { loadDir("") }
                                }
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(onClick = onOpenIssues) {
                                    Text("Issues", fontSize = 12.sp)
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
                                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        color = HubColors.Accent
                                    )
                                }
                            }
                        } else {
                            items(entries) { e ->
                                EntryRow(e) {
                                    if (e.isDir) scope.launch { loadDir(e.path) }
                                    else onOpenFile(e.path, branch)
                                }
                                Divider(color = HubColors.Border, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoHeader(r: RepoInfo, avatar: Bitmap?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (avatar != null) {
                androidx.compose.foundation.Image(
                    bitmap = avatar.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                r.fullName,
                color = HubColors.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            if (r.private) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = HubColors.SurfaceHi,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "Private",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = HubColors.Muted
                    )
                }
            }
        }

        if (r.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(r.description, color = HubColors.Muted, fontSize = 13.sp)
        }

        if (r.topics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.topics.take(6).forEach { t ->
                    Surface(
                        color = HubColors.Accent.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            t, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            fontSize = 11.sp, color = HubColors.Accent
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatCell(compact(r.stars), "Stars")
            StatCell(compact(r.forks), "Forks")
            StatCell(compact(r.watchers), "Watch")
            StatCell(compact(r.openIssues), "Issues")
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.language.isNotBlank()) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(color = HubColors.Accent, radius = size.minDimension / 2f)
                }
                Spacer(Modifier.width(6.dp))
                Text(r.language, color = HubColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
            }
            if (r.license.isNotBlank() && r.license != "NOASSERTION") {
                Text(r.license, color = HubColors.Muted, fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
            }
            Text("更新于 ${GitHubApi.shortDate(r.updatedAt)}", color = HubColors.Muted, fontSize = 12.sp)
        }
    }
    Divider(color = HubColors.Border, thickness = 0.5.dp)
}

@Composable
private fun StatCell(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, color = HubColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = HubColors.Muted, fontSize = 12.sp)
        Spacer(Modifier.width(16.dp))
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
        Text("根目录", color = HubColors.Accent, fontSize = 12.sp,
            modifier = Modifier.clickable { onGo("") })
        parts.forEachIndexed { i, p ->
            acc = if (acc.isEmpty()) p else "$acc/$p"
            val target = acc
            Text(" / ", color = HubColors.Muted, fontSize = 12.sp)
            Text(p, color = if (i == parts.size - 1) HubColors.Muted else HubColors.Accent,
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
            color = HubColors.Text,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!e.isDir && e.size > 0) {
            Text(GitHubApi.humanSize(e.size), color = HubColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (e.isDir) Text("›", color = HubColors.Muted, fontSize = 18.sp)
    }
}

/**
 * 文件 / 文件夹图标。
 * 不用 material-icons-extended（那个包会把 APK 撑大好几 MB），
 * 这里直接画两个最简形状。
 */
@Composable
private fun EntryIcon(isDir: Boolean) {
    val c = if (isDir) HubColors.Accent else HubColors.Muted
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
            Text(msg, color = HubColors.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试", color = HubColors.Accent) }
        }
    }
}

// compact() 定义在 Screens.kt，本文件直接用
