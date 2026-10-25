package com.hubmobile.app.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.app.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 首页 / 搜索 / 用户页 / 设置（令牌）。
 *
 * 全部用 Compose 重写，对应网页版的 page-home.js、page-user.js、
 * onboarding.js 的主干部分。WebView 不再参与渲染。
 */

/* ---------------- 通用小件 ---------------- */

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), color = HubColors.Accent)
    }
}

@Composable
fun CenteredText(msg: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(msg, color = HubColors.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp))
            if (action != null) { Spacer(Modifier.height(10.dp)); action() }
        }
    }
}

@Composable
fun Avatar(bitmap: Bitmap?, size: Int = 32) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(), contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape)
        )
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(HubColors.SurfaceHi)
        )
    }
}

/** 仓库列表项：首页 / 搜索 / 用户页共用 */
@Composable
fun RepoRow(
    r: RepoInfo,
    avatar: Bitmap?,
    onClick: () -> Unit,
    onOwner: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onOwner != null) {
                Avatar(avatar, 20)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                r.fullName, color = HubColors.Accent, fontSize = 13.sp,
                modifier = if (onOwner != null) Modifier.clickable(onClick = onOwner)
                else Modifier,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (r.private) {
                Spacer(Modifier.width(6.dp))
                Surface(color = HubColors.SurfaceHi, shape = RoundedCornerShape(8.dp)) {
                    Text("Private", Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 9.sp, color = HubColors.Muted)
                }
            }
        }
        if (r.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(r.description, color = HubColors.Muted, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (r.language.isNotBlank()) {
                Text(r.language, color = HubColors.Muted, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text("★ ${compact(r.stars)}", color = HubColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text("⑂ ${compact(r.forks)}", color = HubColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text(GitHubApi.shortDate(r.updatedAt), color = HubColors.Muted, fontSize = 11.sp)
        }
    }
    Divider(color = HubColors.Border, thickness = 0.5.dp)
}

/* ---------------- 首页 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedToken: () -> Unit
) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var mine by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var starred by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var me by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; err = null
        kotlinx.coroutines.MainScope().launch {
            try {
                me = withContext(Dispatchers.IO) { GitHubApi.me(ctx) }
                val login = me?.login
                if (login.isNullOrBlank()) {
                    err = "未登录"
                    loading = false
                    return@launch
                }
                mine = withContext(Dispatchers.IO) { GitHubApi.myRepos(ctx) }
                starred = withContext(Dispatchers.IO) { GitHubApi.starred(ctx, login) }
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

    Column(Modifier.fillMaxSize()) {
        when {
            loading -> CenteredLoading()
            err == "未登录" -> CenteredText("还没有设置访问令牌 —— 设置后能看到你的仓库和 Star") {
                Button(onClick = onNeedToken,
                    colors = ButtonDefaults.buttonColors(containerColor = HubColors.Accent)) {
                    Text("去设置令牌", color = HubColors.Canvas)
                }
            }
            err != null -> CenteredText(err!!) { TextButton(onClick = { load() }) { Text("重试") } }
            else -> {
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = HubColors.Surface,
                    contentColor = HubColors.Accent,
                    indicator = { pos ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(pos), color = HubColors.Accent
                        )
                    }
                ) {
                    Tab(tab == 0, { tab = 0 }) { Text("我的仓库", fontSize = 13.sp) }
                    Tab(tab == 1, { tab = 1 }) { Text("Star", fontSize = 13.sp) }
                }
                val list = if (tab == 0) mine else starred
                if (list.isEmpty()) {
                    CenteredText(if (tab == 0) "这个账号下没有仓库" else "还没有 Star 任何仓库")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(list) { r ->
                            RepoRow(r, null, { onOpenRepo(r.ownerLogin, r.name) },
                                { onOpenUser(r.ownerLogin) })
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 搜索 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenRepo: (String, String) -> Unit,
    onOpenUser: (String) -> Unit
) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GitHubApi.SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun search() {
        if (q.isBlank()) return
        loading = true; err = null
        kotlinx.coroutines.MainScope().launch {
            try {
                result = withContext(Dispatchers.IO) { GitHubApi.searchRepos(ctx, q) }
                loading = false
            } catch (e: Throwable) {
                err = when (e) {
                    is ApiError -> if (e.code == 403) "被限流了，稍后再试" else "HTTP ${e.code}"
                    else -> e.message
                }
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it },
            placeholder = { Text("搜索仓库，回车确认", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HubColors.Text,
                unfocusedTextColor = HubColors.Text,
                cursorColor = HubColors.Accent,
                focusedBorderColor = HubColors.Accent,
                unfocusedBorderColor = HubColors.Border
            )
        )
        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!) { TextButton(onClick = { search() }) { Text("重试") } }
            result == null -> CenteredText("输入关键词开始搜索")
            else -> {
                val res = result!!
                Text("共 ${res.total} 个结果", color = HubColors.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(res.items) { r ->
                        RepoRow(r, null, { onOpenRepo(r.ownerLogin, r.name) },
                            { onOpenUser(r.ownerLogin) })
                    }
                }
            }
        }
    }
}

/* ---------------- 用户页 ---------------- */

@Composable
fun UserScreen(login: String, onOpenRepo: (String, String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var u by remember { mutableStateOf<GitHubApi.UserInfo?>(null) }
    var repos by remember { mutableStateOf<List<RepoInfo>>(emptyList()) }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }
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
            err = when (e) { is ApiError -> if (e.code == 404) "用户不存在" else "HTTP ${e.code}"
                else -> e.message }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = HubColors.Accent, fontSize = 14.sp) }
        }
        when {
            loading -> CenteredLoading()
            err != null -> CenteredText(err!!)
            else -> {
                val info = u!!
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Avatar(avatar, 56)
                            Spacer(Modifier.height(10.dp))
                            Text(info.name.ifBlank { info.login }, color = HubColors.Text,
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            if (info.name.isNotBlank()) {
                                Text("@${info.login}", color = HubColors.Muted, fontSize = 13.sp)
                            }
                            if (info.bio.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(info.bio, color = HubColors.Muted, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row {
                                Text("${info.publicRepos} 仓库", color = HubColors.Muted, fontSize = 12.sp)
                                Spacer(Modifier.width(14.dp))
                                Text("${info.followers} 粉丝", color = HubColors.Muted, fontSize = 12.sp)
                                Spacer(Modifier.width(14.dp))
                                Text("关注 ${info.following}", color = HubColors.Muted, fontSize = 12.sp)
                            }
                        }
                        Divider(color = HubColors.Border, thickness = 0.5.dp)
                    }
                    items(repos) { r ->
                        RepoRow(r, avatar, { onOpenRepo(r.ownerLogin, r.name) })
                    }
                }
            }
        }
    }
}

/* ---------------- 设置 / 令牌 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(SecurePrefs.get(ctx, GitHubApi.TOKEN_KEY, "")) }
    var tip by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("访问令牌", color = HubColors.Text, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "令牌存在本机 Keystore 加密区里，不会上传。\n" +
                "需要 repo / read:user 权限；不填只能看公开仓库，且限流 60 次/小时。",
            color = HubColors.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("ghp_ 或 github_pat_ 开头", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HubColors.Text,
                unfocusedTextColor = HubColors.Text,
                cursorColor = HubColors.Accent,
                focusedBorderColor = HubColors.Accent,
                unfocusedBorderColor = HubColors.Border
            )
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(
                onClick = {
                    SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, token)
                    tip = "已保存"
                    onSaved()
                },
                colors = ButtonDefaults.buttonColors(containerColor = HubColors.Accent)
            ) { Text("保存", color = HubColors.Canvas) }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = {
                SecurePrefs.put(ctx, GitHubApi.TOKEN_KEY, "")
                token = ""
                tip = "已清除"
                onSaved()
            }) { Text("清除", color = HubColors.Danger) }
        }
        if (tip != null) {
            Spacer(Modifier.height(8.dp))
            Text(tip!!, color = HubColors.Success, fontSize = 12.sp)
        }
    }
}

/** 1234 → 1.2k */
internal fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1000 -> String.format("%.1fk", n / 1000.0)
    else -> n.toString()
}
