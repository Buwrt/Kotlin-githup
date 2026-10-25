package com.hubmobile.app.compose

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.app.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 登录页 / 我的页 —— 1:1 对齐 githup 网页版 P.login 与 P.profile。
 *
 * 之前原生版「我的页」是随手拼的，缺了 stat-row（仓库/粉丝/关注/Star/Gist
 * 五个可点计数）、编辑资料、两步验证器入口，结构也跟网页版完全不同。
 * 这里按 page-user.js 的 P.profile.render 逐块对齐。
 */

/* ---------------- 登录页：对齐 P.login ---------------- */

@Composable
fun LoginScreen(
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var tk by remember { mutableStateOf(SecurePrefs.get(ctx, GitHubApi.TOKEN_KEY, "")) }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val saved = remember { SecurePrefs.get(ctx, GitHubApi.TOKEN_KEY, "") }

    Column(
        Modifier.fillMaxSize().background(c.canvas).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        HubIcon("mark-github", Modifier.size(64.dp), c.fg)
        Spacer(Modifier.height(10.dp))
        Text("githup", color = c.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("使用 GitHub 账号登录，畅享完整的仓库与协作功能",
            color = c.fgMuted, fontSize = 12.sp)

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().background(c.bg,
            androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text("个人访问令牌（Personal Access Token）", color = c.fgMuted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = tk, onValueChange = { tk = it.trim(); err = null },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None
                else PasswordVisualTransformation(),
                placeholder = { Text("ghp_xxxxxxxxxxxx 或 github_pat_xxxx", fontSize = 12.sp) },
                trailingIcon = {
                    TextButton(onClick = { show = !show }) {
                        Text(if (show) "隐藏" else "显示", fontSize = 11.sp, color = c.accent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                    cursorColor = c.accent, focusedBorderColor = c.accent,
                    unfocusedBorderColor = c.border
                )
            )
            Spacer(Modifier.height(6.dp))
            Text("令牌仅保存在本机加密存储中，不会上传任何服务器。",
                color = c.fgSubtle, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (tk.isBlank() || busy) return@Button
                    busy = true; err = null
                    MainScope().launch {
                        SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, tk)
                        val ok = try {
                            withContext(Dispatchers.IO) { GitHubApi.me(ctx) } != null
                        } catch (e: Throwable) { null } != false
                        busy = false
                        if (ok) onDone() else { err = "令牌无效或网络不可用" }
                    }
                },
                enabled = tk.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary)
            ) {
                Text(if (saved.isNotBlank()) "使用上次的令牌登录" else "登录", color = Color.White)
            }
            if (saved.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, "")
                    tk = ""
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("忘记此令牌", color = c.danger, fontSize = 12.sp)
                }
            }
            if (err != null) {
                Spacer(Modifier.height(6.dp))
                Text(err!!, color = c.danger, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { openWebStatic(ctx,
                    "https://github.com/settings/tokens/new?" +
                        "scopes=repo,read:user,workflow&description=githup") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("在 GitHub 官网创建 Token", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = {
                openWebStatic(ctx, "https://docs.github.com/" +
                    "zh/authentication/keeping-your-account-and-data-secure/" +
                    "managing-your-personal-access-tokens")
            }) { Text("如何获取令牌？", fontSize = 11.sp, color = c.accent) }
            TextButton(onClick = onSkip) {
                Text("先随便看看", fontSize = 11.sp, color = c.fgMuted)
            }
        }
    }
}

/* ---------------- 我的页：对齐 P.profile ---------------- */

@Composable
fun ProfileScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenWeb: (String) -> Unit = {},
    onLogin: () -> Unit = {},
    onTotp: () -> Unit = {},
    onNewRepo: () -> Unit = {},
    onDownloads: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var me by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var avatar by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var starCount by remember { mutableStateOf<Int?>(null) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; err = null
        MainScope().launch {
            try {
                val u = withContext(Dispatchers.IO) { GitHubApi.me(ctx) }
                me = u
                if (u != null) {
                    avatar = withContext(Dispatchers.IO) {
                        GitHubApi.loadBitmap(ctx, u.avatarUrl)
                    }
                    repos = withContext(Dispatchers.IO) { GitHubApi.myRepos(ctx) }
                    starCount = try {
                        withContext(Dispatchers.IO) { GitHubApi.starred(ctx, u.login) }.size
                    } catch (e: Throwable) { null }
                }
                loading = false
            } catch (e: Throwable) {
                err = if (e is ApiError) {
                    if (e.code == 401) "未登录" else "HTTP ${e.code}"
                } else e.message
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    when {
        loading -> SkeletonList(3)
        // 未登录：也要能进两步验证器（它不依赖 GitHub），与网页版一致
        me == null -> {
            LazyColumn(Modifier.fillMaxSize().background(c.canvas),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(12.dp)) {
                        HubCard {
                            Column(Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("登录后查看你的仓库、Star 与动态",
                                    color = c.fgMuted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = onLogin, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = c.primary)) {
                                    Text("登录 GitHub", color = Color.White)
                                }
                            }
                        }
                    }
                }
                item { SetRowItem("shield-check", "两步验证器", "") { onTotp() } }
                item { SetRowItem("gear", "应用设置", "") { onSettings() } }
            }
        }
        else -> {
            val u = me!!
            LazyColumn(Modifier.fillMaxSize().background(c.canvas),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                // profile-head：头像 64 + 名字 + @login + bio
                item {
                    Column(Modifier.fillMaxWidth().background(c.bg).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(avatar, 64)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(u.name.ifBlank { u.login }, color = c.fg,
                                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Text("@${u.login}", color = c.fgMuted, fontSize = 12.sp)
                            }
                        }
                        if (u.bio.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(u.bio, color = c.fgMuted, fontSize = 12.sp)
                        }
                        // stat-row：五个可点计数，对齐网页版
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            StatCell(GitHubApi.num(u.publicRepos), "仓库") { onOpenUser(u.login) }
                            StatCell(GitHubApi.num(u.followers), "粉丝") { onOpenUser(u.login) }
                            StatCell(GitHubApi.num(u.following), "关注") { onOpenUser(u.login) }
                            StatCell(starCount?.let { GitHubApi.num(it) } ?: "—",
                                "Star") { onOpenUser(u.login) }
                            StatCell("—", "Gist") {
                                onOpenWeb("https://gist.github.com/${u.login}")
                            }
                        }
                    }
                }
                // 编辑资料 / 更换头像 / 新建仓库
                item {
                    SetRowItem("person", "编辑资料", if (u.name.isBlank()) "未设置" else "") {
                        onOpenWeb("https://github.com/settings/profile")
                    }
                    SetRowItem("person", "更换头像", "需网页端") {
                        onOpenWeb("https://github.com/settings/profile")
                    }
                    SetRowItem("plus", "新建仓库", "") { onNewRepo() }
                }
                item { SectionTitle("") }
                item {
                    SetRowItem("shield-check", "两步验证器", "") { onTotp() }
                    SetRowItem("repo", "我的仓库", "") { onOpenUser(u.login) }
                    SetRowItem("star", "我的 Star", "") { onOpenUser(u.login) }
                    SetRowItem("issue", "我的议题", "") {
                        onOpenWeb("https://github.com/issues")
                    }
                    SetRowItem("pr", "我的拉取请求", "") {
                        onOpenWeb("https://github.com/pulls")
                    }
                    SetRowItem("code-square", "我的 Gist", "") {
                        onOpenWeb("https://gist.github.com/${u.login}")
                    }
                    SetRowItem("organization", "我的组织", "") {
                        onOpenWeb("https://github.com/settings/organizations")
                    }
                    SetRowItem("bell", "通知", "") {
                        onOpenWeb("https://github.com/notifications")
                    }
                }
                item { SectionTitle("") }
                item {
                    SetRowItem("download", "下载管理", "") { onDownloads() }
                    SetRowItem("gear", "设置", "") { onSettings() }
                    SetRowItem("graph", "API 配额", "") { onSettings() }
                }
                item { SectionTitle("我的仓库", icon = "repo") }
                if (repos.isEmpty()) item { CenteredText("还没有仓库") }
                else items(items = repos.take(20), key = { it.fullName }) { r ->
                    Box(Modifier.background(c.bg)) {
                        RepoRow(r, { onOpenRepo(r.ownerLogin, r.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(num: String, label: String, onClick: () -> Unit) {
    val c = LocalHubColors.current
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(num, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = c.fgMuted, fontSize = 11.sp)
    }
}

/** 设置行：图标 + 标题 + 右侧值/箭头 */
@Composable
fun SetRowItem(
    icon: String,
    title: String,
    value: String = "",
    onClick: () -> Unit
) {
    val c = LocalHubColors.current
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
