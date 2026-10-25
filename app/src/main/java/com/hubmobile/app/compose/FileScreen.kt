package com.hubmobile.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 文件查看页（原生）。
 *
 * 这是路线 B 里最关键的补齐 —— 在此之前点开单个文件会跳内置浏览器，
 * 那是 App 里唯一还会让人看到网页的地方。现在 Markdown 用自写的渲染器画，
 * 普通文本用等宽字体 + 横向滚动展示。
 *
 * 仍走网页的情况只剩两种：
 *   1. 二进制文件（图片/zip/apk 等）—— 原生不做预览
 *   2. 大文件（contents API 上限 1MB）—— 接口会拒，提示去浏览器看
 */

private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "ini", "cfg",
    "properties", "gradle", "kt", "java", "py", "js", "ts", "jsx", "tsx", "html",
    "css", "scss", "sh", "bash", "c", "h", "cpp", "hpp", "go", "rs", "rb", "php",
    "sql", "csv", "log", "gitignore", "gitattributes", "lock", "pro", "bat", "ps1"
)

private fun isTextFile(name: String): Boolean {
    val n = name.substringAfterLast('.').lowercase()
    if (n.isBlank() || n == name.lowercase()) return true   // 无扩展名按文本处理
    return n in TEXT_EXT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileScreen(
    owner: String,
    name: String,
    branch: String,
    path: String,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit
) {
    val ctx = LocalContext.current
    var fc by remember { mutableStateOf<GitHubApi.FileContent?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name, path, branch) {
        loading = true; err = null
        try {
            fc = withContext(Dispatchers.IO) {
                GitHubApi.fileContent(ctx, owner, name, path, branch)
            }
            loading = false
        } catch (e: Throwable) {
            err = when (e) {
                is ApiError -> when (e.code) {
                    403 -> "文件太大或触发限流 —— contents 接口上限 1MB"
                    404 -> "文件不存在（可能分支不对）"
                    else -> "HTTP ${e.code}"
                }
                else -> e.message
            }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(path.substringAfterLast('/'), maxLines = 1, fontSize = 14.sp,
                        color = LocalHubColors.current.fg)
                    Text(branch, fontSize = 10.sp, color = LocalHubColors.current.fgMuted)
                }
            },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Light,
                        color = LocalHubColors.current.accent)
                }
            },
            actions = {
                TextButton(onClick = {
                    onOpenWeb("https://github.com/$owner/$name/blob/$branch/${path.trim('/')}")
                }) { Text("浏览器", fontSize = 12.sp) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalHubColors.current.bg)
        )
        Divider(color = LocalHubColors.current.border, thickness = 0.5.dp)

        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!) {
                TextButton(onClick = {
                    onOpenWeb("https://github.com/$owner/$name/blob/$branch/${path.trim('/')}")
                }) { Text("用浏览器打开", color = LocalHubColors.current.accent) }
            }
            else -> {
                val f = fc!!
                when {
                    f.isBinary || f.text == null -> CenteredText(
                        "这个文件无法在 App 内预览（二进制文件，${GitHubApi.humanSize(f.size)}）"
                    ) {
                        TextButton(onClick = {
                            onOpenWeb("https://github.com/$owner/$name/blob/$branch/${path.trim('/')}")
                        }) { Text("用浏览器打开", color = LocalHubColors.current.accent) }
                    }
                    path.substringAfterLast('.').lowercase() in setOf("md", "markdown") ->
                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MarkdownText(
                                f.text!!,
                                modifier = Modifier.padding(16.dp),
                                onLink = { onOpenWeb(it) }
                            )
                        }
                    else -> PlainTextView(f.text!!)
                }
            }
        }
    }
}

/** 普通文本文件：等宽 + 行号，横向可滚 */
@Composable
private fun PlainTextView(text: String) {
    val lines = remember(text) { text.split('\n') }
    val h = rememberScrollState()
    val v = rememberScrollState()
    Box(
        Modifier.fillMaxSize()
            .verticalScroll(v)
            .horizontalScroll(h)
            .padding(10.dp)
    ) {
        Column {
            val gutter = lines.size.toString().length
            lines.forEachIndexed { idx, ln ->
                Row {
                    Text(
                        String.format("%${gutter}d", idx + 1),
                        color = LocalHubColors.current.border, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width((gutter * 7 + 10).dp)
                    )
                    Text(
                        ln.ifEmpty { " " },
                        color = LocalHubColors.current.fg, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/* ---------------- Issues ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(
    owner: String,
    name: String,
    onBack: () -> Unit,
    onOpenWeb: (String) -> Unit
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<List<GitHubApi.IssueInfo>>(emptyList()) }
    var closed by remember { mutableStateOf<List<GitHubApi.IssueInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, name) {
        loading = true; err = null
        try {
            open = withContext(Dispatchers.IO) { GitHubApi.issues(ctx, owner, name, "open") }
            closed = withContext(Dispatchers.IO) { GitHubApi.issues(ctx, owner, name, "closed") }
            loading = false
        } catch (e: Throwable) {
            err = when (e) { is ApiError -> "HTTP ${e.code}"; else -> e.message }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Issues", fontSize = 15.sp, color = LocalHubColors.current.fg) },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Light,
                        color = LocalHubColors.current.accent)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalHubColors.current.bg)
        )
        TabRow(selectedTabIndex = tab, containerColor = LocalHubColors.current.bg,
            contentColor = LocalHubColors.current.accent) {
            Tab(tab == 0, { tab = 0 }) { Text("开启 ${open.size}", fontSize = 12.sp) }
            Tab(tab == 1, { tab = 1 }) { Text("已关闭 ${closed.size}", fontSize = 12.sp) }
        }
        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!)
            else -> {
                val list = if (tab == 0) open else closed
                if (list.isEmpty()) CenteredText(if (tab == 0) "没有开启的 issue" else "没有已关闭的 issue")
                else {
                    val v = rememberScrollState()
                    Column(Modifier.fillMaxSize().verticalScroll(v)) {
                        list.forEach { it0 ->
                            IssueRow(it0) {
                                onOpenWeb("https://github.com/$owner/$name/issues/${it0.number}")
                            }
                            Divider(color = LocalHubColors.current.border, thickness = 0.5.dp)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueRow(i: GitHubApi.IssueInfo, onClick: () -> Unit) {
    val opened = "open" == i.state
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                if (opened) "●" else "✓",
                color = if (opened) LocalHubColors.current.success else LocalHubColors.current.done,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp)
            )
            Text(
                i.title, color = LocalHubColors.current.fg, fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#${i.number}", color = LocalHubColors.current.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(i.user, color = LocalHubColors.current.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(GitHubApi.ago(i.createdAt), color = LocalHubColors.current.fgMuted, fontSize = 11.sp)
            if (i.comments > 0) {
                Spacer(Modifier.width(8.dp))
                Text("${i.comments} 条评论", color = LocalHubColors.current.fgMuted, fontSize = 11.sp)
            }
        }
        if (i.labels.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row {
                i.labels.take(3).forEach { l ->
                    Box(
                        Modifier.padding(end = 5.dp)
                            .background(LocalHubColors.current.accent.copy(alpha = 0.15f),
                                androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(l, color = LocalHubColors.current.accent, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
