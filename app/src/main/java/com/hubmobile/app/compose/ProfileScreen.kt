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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
    /*
     * 之前这里只有一句「令牌无效或网络不可用」——因为 me() 把 401/403/
     * 断网/解析失败 全 catch 成 null，四种情况没法区分。
     * 现在用 meChecked() 拿真实状态码，分开报。
     */
    var err by remember { mutableStateOf<String?>(null) }
    val saved = remember { SecurePrefs.get(ctx, GitHubApi.TOKEN_KEY, "") }

    // 网页版 .login-wrap padding:24px 20px；title 22/600；sub 13；card radius 8
    Column(
        Modifier.fillMaxSize().background(c.canvas)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // .login-logo：居中，图标本身着色 var(--fg)
        HubIcon("mark-github", Modifier.size(64.dp), c.fg)
        Spacer(Modifier.height(14.dp))
        // .login-title font-size:22px; font-weight:600
        Text("githup", color = c.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        // .login-sub font-size:13px; margin:6px 0 26px
        Text("使用 GitHub 账号登录，畅享完整的仓库与协作功能",
            color = c.fgMuted, fontSize = 13.sp)
        Spacer(Modifier.height(26.dp))

        // .login-card：background var(--bg); border 1px solid var(--border); radius 8; padding 16
        Column(
            Modifier.fillMaxWidth()
                .background(c.bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .border(1.dp, c.border, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            // .field > label font-size:13px; font-weight:600; margin-bottom:6px
            Text("个人访问令牌（Personal Access Token）", color = c.fg, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            /*
             * .tk-wrap：输入框内嵌眼睛按钮。Compose 的 OutlinedTextField 没有内嵌槽位，
             * 用 trailingIcon 实现同样效果；网页版图标是眼睛，这里用文字「显示/隐藏」，
             * 语义一致。
             */
            OutlinedTextField(
                value = tk, onValueChange = { tk = it.trim(); err = null },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp, color = c.fg),
                visualTransformation = if (show) VisualTransformation.None
                else PasswordVisualTransformation(),
                placeholder = { Text("ghp_xxxxxxxxxxxx 或 github_pat_xxxx", fontSize = 13.sp) },
                trailingIcon = {
                    TextButton(onClick = { show = !show }) {
                        Text(if (show) "隐藏" else "显示", fontSize = 13.sp,
                            color = if (show) c.accent else c.fgMuted)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.bg, unfocusedContainerColor = c.bg,
                    focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                    cursorColor = c.accent, focusedBorderColor = c.accentEmphasis,
                    unfocusedBorderColor = c.border
                )
            )
            // .field .hint font-size:12px; color var(--fg-muted); margin-top:6px
            Spacer(Modifier.height(6.dp))
            Text("令牌仅保存在本机加密存储中，不会上传任何服务器。",
                color = c.fgMuted, fontSize = 12.sp)

            Spacer(Modifier.height(14.dp))
            // .btn.primary.block.lg：min-height 44px; font-size 15px; padding 10/16
            Button(
                onClick = {
                    if (tk.isBlank() || busy) return@Button
                    busy = true; err = null
                    MainScope().launch {
                        val r = withContext(Dispatchers.IO) {
                            // 先存再验：验过了下次直接免输
                            SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, tk)
                            GitHubApi.meChecked(ctx)
                        }
                        busy = false
                        when {
                            r.user != null -> { err = null; onDone() }
                            r.code == 401 -> err = "令牌无效或已过期，请重新创建"
                            r.code == 403 -> err = "已被限流（无令牌时每小时 60 次），稍后再试"
                            r.code == -1 -> err = "网络不可用，请检查连接"
                            r.code == -2 -> err = "返回数据解析失败，稍后再试"
                            else -> err = "登录失败（HTTP ${r.code}）"
                        }
                    }
                },
                enabled = tk.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.primary)
            ) {
                Text(if (saved.isNotBlank()) "使用上次的令牌登录" else "登录",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            // 有存档时才出现「忘记此令牌」，网页版是动态插在 create 按钮上方
            if (saved.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, "")
                        tk = ""; err = null
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.bgHi)
                ) { Text("忘记此令牌", color = c.danger, fontSize = 14.sp) }
            }

            if (err != null) {
                Spacer(Modifier.height(10.dp))
                Text(err!!, color = c.danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { openWebStatic(ctx,
                    "https://github.com/settings/tokens/new?" +
                        "scopes=repo,read:user,workflow&description=githup") },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
            ) {
                Text("在 GitHub 官网创建 Token", fontSize = 14.sp)
            }
        }

        // .login-alt：text-align center; margin-top 16px; 两个按钮中间一个「·」
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                openWebStatic(ctx, "https://docs.github.com/" +
                    "zh/authentication/keeping-your-account-and-data-secure/" +
                    "managing-your-personal-access-tokens")
            }) { Text("如何获取令牌？", fontSize = 13.sp, color = c.accent) }
            Text(" · ", color = c.fgSubtle, fontSize = 13.sp)
            TextButton(onClick = onSkip) {
                Text("先随便看看", fontSize = 13.sp, color = c.accent)
            }
        }
    }
}

@Composable
fun ProfileScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenWeb: (String) -> Unit = {},
    onLogin: () -> Unit = {},
    onTotp: () -> Unit = {},
    onNewRepo: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onEditProfile: () -> Unit = {},
    /** 打开用户页并落在指定 tab：repos / followers / following / stars */
    onOpenUserTab: (String, String) -> Unit = { l, _ -> onOpenUser(l) }
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
                            StatCell(GitHubApi.num(u.publicRepos), "仓库") {
                                onOpenUserTab(u.login, "repos")
                            }
                            StatCell(GitHubApi.num(u.followers), "粉丝") {
                                onOpenUserTab(u.login, "followers")
                            }
                            StatCell(GitHubApi.num(u.following), "关注") {
                                onOpenUserTab(u.login, "following")
                            }
                            StatCell(starCount?.let { GitHubApi.num(it) } ?: "—", "Star") {
                                onOpenUserTab(u.login, "stars")
                            }
                            StatCell(GitHubApi.num(u.publicGists), "Gist") {
                                onOpenWeb("https://gist.github.com/${u.login}")
                            }
                        }
                    }
                }
                // 编辑资料 / 更换头像 / 新建仓库
                item {
                    SetRowItem("person", "编辑资料", if (u.name.isBlank()) "未设置" else "") {
                        onEditProfile()
                    }
                    SetRowItem("person", "更换头像", "需网页端") {
                        onOpenWeb("https://github.com/settings/profile")
                    }
                    SetRowItem("plus", "新建仓库", "") { onNewRepo() }
                }
                item { SectionTitle("") }
                item {
                    SetRowItem("shield-check", "两步验证器", "") { onTotp() }
                    SetRowItem("repo", "我的仓库", "") { onOpenUserTab(u.login, "repos") }
                    SetRowItem("star", "我的 Star", "") { onOpenUserTab(u.login, "stars") }
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
                else items(repos.take(20)) { r ->
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

/* ---------------- 编辑资料：对齐 page-user.js 的 editProfile ---------------- */

private val SOCIAL_PROVIDERS = listOf(
    "twitter" to "X / Twitter", "mastodon" to "Mastodon",
    "linkedin" to "LinkedIn", "zhihu" to "知乎", "bilibili" to "哔哩哔哩"
)

@Composable
fun EditProfileScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHubColors.current
    var u by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var name by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var blog by remember { mutableStateOf("") }
    var hireable by remember { mutableStateOf(false) }
    var social by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val info = try {
            withContext(Dispatchers.IO) { GitHubApi.me(ctx) }
        } catch (e: Throwable) { null }
        if (info != null) {
            u = info
            name = info.name; bio = info.bio
            company = info.company; location = info.location; blog = info.blog
            hireable = info.hireable
            social = info.social
        }
        loaded = true
    }

    if (!loaded) { SkeletonList(3); return }

    LazyColumn(Modifier.fillMaxSize().background(c.canvas),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        // 头像区：展示 + 去网页端修改（GitHub 没开放头像上传接口）
        item {
            Row(Modifier.fillMaxWidth().background(c.bg).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(null, 56)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("头像", color = c.fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("GitHub 未开放头像上传接口，需在网页端修改。",
                        color = c.fgSubtle, fontSize = 10.sp)
                }
                OutlinedButton(onClick = {
                    openWebStatic(ctx, "https://github.com/settings/profile")
                }, modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("去修改", fontSize = 11.sp)
                }
            }
        }
        item { FormField("昵称", name, { name = it }, "显示名称") }
        item { FormArea("简介", bio, { bio = it }, "一句话介绍自己") }
        item { FormField("公司", company, { company = it }, "@company") }
        item { FormField("地区", location, { location = it }, "城市") }
        item { FormField("博客", blog, { blog = it }, "https://") }

        item { SectionTitle("社交账号") }
        items(SOCIAL_PROVIDERS) { (k, label) ->
            FormField(label, social[k] ?: "", { v ->
                social = social.toMutableMap().apply { put(k, v) }
            }, "")
        }

        item { SectionTitle("其他") }
        item {
            CheckRow("愿意接受工作机会（显示「可雇佣」标记）", hireable) { hireable = it }
        }
        item {
            Text("头像与用户名暂不支持在应用内修改，GitHub 未开放对应接口。",
                color = c.fgSubtle, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        item {
            if (err != null) {
                Text(err!!, color = c.danger, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(14.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
                Spacer(Modifier.width(10.dp))
                Button(onClick = {
                    if (busy) return@Button
                    busy = true; err = null
                    MainScope().launch {
                        val ok = try {
                            withContext(Dispatchers.IO) {
                                GitHubApi.updateProfile(ctx, name, bio, blog, company,
                                    location, hireable, social)
                            }
                        } catch (e: Throwable) { false }
                        busy = false
                        if (ok) onSaved() else err = "保存失败（令牌需要 user 权限）"
                    }
                }, enabled = !busy, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary)) {
                    Text(if (busy) "保存中…" else "保存", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun FormField(label: String, value: String, on: (String) -> Unit, hint: String) {
    val c = LocalHubColors.current
    Column(Modifier.fillMaxWidth().background(c.bg)
        .padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = c.fgMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = on, singleLine = true,
            placeholder = { Text(hint, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border))
    }
}

@Composable
private fun FormArea(label: String, value: String, on: (String) -> Unit, hint: String) {
    val c = LocalHubColors.current
    Column(Modifier.fillMaxWidth().background(c.bg)
        .padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, color = c.fgMuted, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = on,
            placeholder = { Text(hint, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().height(90.dp), maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.fg, unfocusedTextColor = c.fg,
                cursorColor = c.accent, focusedBorderColor = c.accent,
                unfocusedBorderColor = c.border))
    }
}

@Composable
private fun CheckRow(text: String, on: Boolean, onCh: (Boolean) -> Unit) {
    val c = LocalHubColors.current
    Row(Modifier.fillMaxWidth().background(c.bg).clickable { onCh(!on) }
        .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(checked = on, onCheckedChange = onCh)
        Spacer(Modifier.width(10.dp))
        Text(text, color = c.fg, fontSize = 12.sp)
    }
}
