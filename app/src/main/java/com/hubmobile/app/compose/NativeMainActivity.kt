package com.hubmobile.app.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withContext

/*
 * 纯原生主界面 —— 对齐网页版 index.html 的骨架：
 *   #appbar  顶栏（返回 + 标题 + 右侧操作）
 *   #view    主内容区
 *   #tabbar  底部 5 个 tab：首页 / 通知 / 探索 / 搜索 / 我的
 *
 * 之前做成 3 个 tab（首页/搜索/设置）是随手发挥的，与网页版不一致，
 * 这里按 index.html 里的 5 个按钮对齐（图标名也照抄 data-icon）。
 */

class NativeMainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OWNER = "owner"
        const val EXTRA_NAME = "name"

        fun openRepo(ctx: Context, owner: String, name: String) {
            val i = Intent(ctx, NativeMainActivity::class.java)
            i.putExtra(EXTRA_OWNER, owner)
            i.putExtra(EXTRA_NAME, name)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = intent.getStringExtra(EXTRA_OWNER) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        // 主题：auto / light / dark，对齐网页版设置项
        var theme by mutableStateOf(Prefs.theme(this))
        // 启动页：home / notifications / explore / profile
        val startKey = Prefs.startTab(this)
        setContent {
            val dark = when (theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            HubTheme(darkTheme = dark) {
                AppRoot(
                    initialRepo = if (owner.isNotBlank()) owner to name else null,
                    startKey = startKey,
                    onThemeChange = { v -> theme = v }
                )
            }
        }
    }
}

/** 与 index.html 的 #tabbar 一致：5 个 tab + 图标名 */
private enum class Tab(val key: String, val label: String, val icon: String) {
    Home("home", "首页", "home"),
    Notifications("notifications", "通知", "bell"),
    Explore("explore", "探索", "telescope"),
    Search("search", "搜索", "search"),
    Profile("profile", "我的", "person")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    initialRepo: Pair<String, String>?,
    startKey: String = "home",
    onThemeChange: (String) -> Unit = {}
) {
    val c = LocalHubColors.current
    val ctx = LocalContext.current
    // 当前 tab；启动页由设置决定，外链进来带仓库坐标时先落到仓库页
    var tab by remember {
        mutableStateOf(Tab.values().firstOrNull { it.key == startKey } ?: Tab.Home)
    }
    var repo by remember { mutableStateOf(initialRepo) }
    var user by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<Triple<String, String, String>?>(null) } // owner/branch/path
    var issues by remember { mutableStateOf<Pair<String, String>?>(null) }
    var settings by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        unread = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                GitHubApi.notifications(ctx).count { it.unread }
            }
        } catch (e: Throwable) { 0 }
    }

    // 子页（仓库/用户/文件/Issues/设置）时，底栏不高亮、顶栏显示返回
    val inSub = repo != null || user != null || file != null || issues != null || settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            settings -> "设置"
                            repo != null -> repo!!.let { "${it.first}/${it.second}" }
                            user != null -> user!!
                            else -> "githup"
                        },
                        fontSize = 16.sp, color = c.fg, maxLines = 1
                    )
                },
                navigationIcon = {
                    if (inSub) {
                        TextButton(onClick = {
                            when {
                                file != null -> file = null
                                issues != null -> issues = null
                                settings -> settings = false
                                repo != null -> repo = null
                                user != null -> user = null
                            }
                        }) {
                            Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Light,
                                color = c.accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.header,
                    titleContentColor = c.fg
                )
            )
        },
        bottomBar = {
            if (!inSub) {
                Row(
                    Modifier.fillMaxWidth().background(c.tabbar)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    Tab.values().forEach { t ->
                        val sel = tab == t
                        Column(
                            Modifier.clickable { tab = t }
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box {
                                HubIcon(t.icon, Modifier.size(22.dp),
                                    if (sel) c.accent else c.fgSubtle)
                                if (t == Tab.Notifications && unread > 0) {
                                    Box(Modifier.size(14.dp)
                                        .background(c.danger, androidx.compose.foundation.shape.CircleShape)
                                        .align(Alignment.TopEnd), Alignment.Center) {
                                        Text(if (unread > 99) "99+" else unread.toString(),
                                            color = Color.White, fontSize = 8.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.size(2.dp))
                            Text(t.label, fontSize = 10.sp,
                                color = if (sel) c.accent else c.fgSubtle)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).background(c.canvas)) {
            when {
                settings -> SettingsScreen(
                    onOpenWeb = { openWeb(ctx, it) },
                    onLogout = { settings = false; tab = Tab.Home },
                    onRestart = { }
                )
                file != null -> {
                    val (o, br, p) = file!!
                    FileScreen(
                        owner = o, name = repo?.second ?: "",
                        branch = br, path = p,
                        onBack = { file = null },
                        onOpenWeb = { openWeb(ctx, it) }
                    )
                }
                issues != null -> {
                    val (o, n) = issues!!
                    IssuesScreen(owner = o, name = n, onBack = { issues = null },
                            onOpenWeb = { openWeb(ctx, it) })
                }
                repo != null -> {
                    val (o, n) = repo!!
                    RepoScreen(
                        owner = o, name = n,
                        onBack = { repo = null },
                        onOpenFile = { path, branch -> file = Triple(o, branch, path) },
                        onOpenInWeb = { openWeb(ctx, "https://github.com/$o/$n") },
                        onOpenWeb = { openWeb(ctx, it) }
                    )
                }
                user != null -> UserScreen(
                    login = user!!,
                    onOpenRepo = { o, n -> repo = o to n },
                    onOpenUser = { user = it },
                    onOpenWeb = { openWeb(ctx, it) }
                )
                tab == Tab.Home -> HomeScreen(
                    onOpenRepo = { o, n -> repo = o to n },
                    onOpenUser = { user = it },
                    onNeedToken = { settings = true },
                    onNewRepo = { openWeb(ctx, "https://github.com/new") },
                    // 快捷宫格：我的仓库/Star 走原生用户页，其余暂借内置浏览器
                    onQuick = { key ->
                        val login = meLogin(ctx)
                        when (key) {
                            "repos", "stars" -> if (login.isNotBlank()) user = login
                            "issues" -> openWeb(ctx, "https://github.com/issues")
                            "pulls" -> openWeb(ctx, "https://github.com/pulls")
                            "gists" -> openWeb(ctx,
                                "https://gist.github.com/${login.ifBlank { "" }}")
                            "orgs" -> openWeb(ctx, "https://github.com/settings/organizations")
                        }
                    }
                )
                tab == Tab.Notifications -> NotificationsScreen(
                    onOpen = { openWeb(ctx, it) },
                    onMarkAllRead = {
                        kotlinx.coroutines.MainScope().launch {
                            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                GitHubApi.markAllRead(ctx)
                            }
                            if (ok) unread = 0
                        }
                    }
                )
                tab == Tab.Explore -> ExploreScreen(
                    onOpenRepo = { o, n -> repo = o to n }
                )
                tab == Tab.Search -> SearchScreen(
                    onOpenRepo = { o, n -> repo = o to n },
                    onOpenUser = { user = it },
                    onOpenWeb = { openWeb(ctx, it) }
                )
                tab == Tab.Profile -> ProfileScreen(
                    onOpenRepo = { o, n -> repo = o to n },
                    onOpenUser = { user = it },
                    onSettings = { settings = true },
                    onOpenWeb = { openWeb(ctx, it) }
                )
            }
        }
    }
}

/**
 * 尚未原生化的内容（Issue 详情、新建仓库等）暂借内置浏览器。
 * 注意这里不是回到 WebView 主界面，只是单开一个网页。
 */
private fun openWeb(ctx: Context, url: String) {
    val i = Intent(ctx, com.hubmobile.app.WebViewActivity::class.java)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_URL, url)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_TITLE, "githup")
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
}

/** 当前登录名（宫格跳转要用）。取不到返回空串。 */
private fun meLogin(ctx: Context): String {
    return try {
        GitHubApi.me(ctx)?.login ?: ""
    } catch (e: Throwable) { "" }
}
