package com.hubmobile.app.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.app.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 纯原生主界面 —— 对齐网页版 index.html 的骨架：
 *   #appbar  顶栏（返回 + 标题 + 右侧操作）
 *   #view    主内容区
 *   #tabbar  底部 5 个 tab：首页 / 通知 / 探索 / 搜索 / 我的
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
        var theme by mutableStateOf(Prefs.theme(this))
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
    var tab by remember {
        mutableStateOf(Tab.values().firstOrNull { it.key == startKey } ?: Tab.Home)
    }
    var repo by remember { mutableStateOf(initialRepo) }
    var user by remember { mutableStateOf<String?>(null) }
    var userTab by remember { mutableStateOf("overview") }
    /*
     * 当前登录名。宫格跳转要用。
     *
     * ⚠️ 原来是同步调 meLogin(ctx) —— 那是**主线程直接发网络请求**。
     * 虽然有 try-catch 兜着不会崩，但主线程网络在部分 ROM 上会被
     * StrictMode 直接杀进程，而且会卡住 UI 几百毫秒。
     * 改成启动时异步取一次缓存在这里。
     */
    var myLogin by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var settings by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf(false) }
    var totp by remember { mutableStateOf(false) }
    var downloads by remember { mutableStateOf(false) }
    var newRepo by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf(false) }
    var newRelease by remember { mutableStateOf<Pair<String, String>?>(null) }
    var detail by remember { mutableStateOf<Triple<String, String, Int>?>(null) } // owner/name/number
    var detailIsPr by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(0) }
    var update by remember { mutableStateOf<GitHubApi.UpdateInfo?>(null) }
    var updateTip by remember { mutableStateOf<String?>(null) }
    /*
     * 上次崩溃：启动就读出来，有就直接弹。
     *
     * 「界面转着圈就没了」这种问题，没有堆栈就只能靠猜 —— 猜错一次
     * 就是白等一轮构建。所以只要存在崩溃记录，下次打开就主动摊开，
     * 让它能被看到、能被复制出来。
     * 用户点「知道了」即清除，不会重复打扰。
     */
    var crash by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        crash = try { com.hubmobile.app.CrashLog.readLast(ctx) } catch (e: Throwable) { null }
        myLogin = try {
            withContext(Dispatchers.IO) { GitHubApi.me(ctx)?.login ?: "" }
        } catch (e: Throwable) { "" }
        unread = try {
            withContext(Dispatchers.IO) {
                GitHubApi.notifications(ctx).count { it.unread }
            }
        } catch (e: Throwable) { 0 }
        /*
         * 静默更新：打开就查一次。已是最新版**完全不吭声**（对齐 README 的原则）；
         * 只有真有更新才把结果挂出来让下面弹窗。
         */
        val info = try {
            withContext(Dispatchers.IO) { GitHubApi.checkUpdate(ctx) }
        } catch (e: Throwable) { null }
        if (info != null) {
            val cur = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (e: Throwable) { "" }
            val cmp = GitHubApi.compareVersion(cur, info.version)
            // -1 无法比较 / 0 无更新 → 静默；1 可选；2 强制
            if (cmp >= 1) update = info
        }
    }

    val inSub = repo != null || user != null || file != null || settings ||
        login || totp || downloads || newRepo || newRelease != null ||
        detail != null || editProfile

    Scaffold(
        topBar = {
            /*
             * 顶栏不用 material3 的 TopAppBar —— 它内部那层 Surface 自带
             * tonal elevation，在 header 与 canvas 同色（都是 #010409）时
             * 会在顶栏下缘压出一条模糊的亮带，看着像多出来的一条线。
             *
             * 这里改成按网页版 #appbar 自绘：
             *   背景 var(--header-bg)，底部 border-bottom: 1px solid var(--border)
             * 网页版本来就有这条 1px 线，照抄即可 —— 干净、且和网页版一致。
             */
            Column(Modifier.background(c.header)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (inSub) {
                        TextButton(onClick = {
                            when {
                                detail != null -> detail = null
                                editProfile -> editProfile = false
                                file != null -> file = null
                                newRelease != null -> newRelease = null
                                newRepo -> newRepo = false
                                downloads -> downloads = false
                                totp -> totp = false
                                login -> login = false
                                settings -> settings = false
                                repo != null -> repo = null
                                user != null -> { user = null; userTab = "overview" }
                            }
                        }) {
                            Text("‹", fontSize = 26.sp, fontWeight = FontWeight.Light,
                                color = c.accent)
                        }
                    } else {
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(
                        when {
                            settings -> "设置"
                            login -> "登录"
                            totp -> "两步验证器"
                            downloads -> "下载管理"
                            newRepo -> "新建仓库"
                            editProfile -> "编辑资料"
                            newRelease != null -> "创建 Release"
                            detail != null -> "#${detail!!.third}"
                            repo != null -> repo!!.let { "${it.first}/${it.second}" }
                            user != null -> user!!
                            else -> "githup"
                        },
                        // .appbar-title: 16px / 600 / 左对齐 / 单行省略
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = c.fg, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                }
                // border-bottom: 1px solid var(--border)
                Divider(color = c.border, thickness = 1.dp)
            }
        },
        bottomBar = {
            if (!inSub) {
                Row(
                    Modifier.fillMaxWidth().background(c.tabbar).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
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
                                    Box(
                                        Modifier.size(14.dp)
                                            .background(c.danger,
                                                androidx.compose.foundation.shape.CircleShape)
                                            .align(Alignment.TopEnd),
                                        Alignment.Center
                                    ) {
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
        // 平板 / 大屏：宽屏下内容限宽居中 900px（对齐网页版 1.1.3 的适配）
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad).background(c.canvas)) {
            val wide = maxWidth >= 700.dp
            Box(
                Modifier
                    .then(if (wide) Modifier.width(900.dp) else Modifier.fillMaxWidth())
                    .fillMaxSize()
                    .align(if (wide) Alignment.TopCenter else Alignment.TopStart)
            ) {
                when {
                    settings -> SettingsScreen(
                        onOpenWeb = { openWeb(ctx, it) },
                        onLogout = { settings = false; tab = Tab.Home },
                        onThemeChange = { v -> onThemeChange(v) }
                    )
                    login -> LoginScreen(
                        onDone = { login = false; tab = Tab.Home },
                        onSkip = { login = false }
                    )
                    totp -> TotpScreen(onBack = { totp = false })
                    downloads -> DownloadsScreen(onBack = { downloads = false })
                    editProfile -> EditProfileScreen(
                        onBack = { editProfile = false },
                        onSaved = { editProfile = false; tab = Tab.Profile }
                    )
                    newRepo -> NewRepoScreen(
                        onBack = { newRepo = false },
                        onCreated = { full ->
                            newRepo = false
                            val p = full.split('/')
                            if (p.size == 2) repo = p[0] to p[1]
                        }
                    )
                    newRelease != null -> {
                        val (o, n) = newRelease!!
                        NewReleaseScreen(o, n, onBack = { newRelease = null })
                    }
                    detail != null -> {
                        val (o, n, num) = detail!!
                        IssueDetailScreen(
                            owner = o, name = n, number = num, isPr = detailIsPr,
                            onBack = { detail = null },
                            onOpenUser = { user = it }
                        )
                    }
                    file != null -> {
                        val (o, br, p) = file!!
                        FileScreen(
                            owner = o, name = repo?.second ?: "", branch = br, path = p,
                            onBack = { file = null },
                            onOpenWeb = { openWeb(ctx, it) }
                        )
                    }
                    repo != null -> {
                        val (o, n) = repo!!
                        RepoScreen(
                            owner = o, name = n,
                            onBack = { repo = null },
                            onOpenFile = { path, branch -> file = Triple(o, branch, path) },
                            onOpenInWeb = { openWeb(ctx, "https://github.com/$o/$n") },
                            onOpenWeb = { openWeb(ctx, it) },
                            onOpenOwner = { user = o },
                            onOpenDetail = { num, isPr ->
                                detailIsPr = isPr
                                detail = Triple(o, n, num)
                            }
                        )
                    }
                    user != null -> UserScreen(
                        login = user!!,
                        onOpenRepo = { o, n -> repo = o to n },
                        onOpenUser = { user = it },
                        onOpenWeb = { openWeb(ctx, it) },
                        initialTab = userTab
                    )
                    tab == Tab.Home -> HomeScreen(
                        onOpenRepo = { o, n -> repo = o to n },
                        onOpenUser = { user = it },
                        onNeedToken = { login = true },
                        onNewRepo = { newRepo = true },
                        onQuick = { key ->
                            val lg = myLogin
                            when (key) {
                                "repos", "stars" -> if (lg.isNotBlank()) {
                                    userTab = key; user = lg
                                }
                                "issues" -> openWeb(ctx, "https://github.com/issues")
                                "pulls" -> openWeb(ctx, "https://github.com/pulls")
                                "gists" -> openWeb(ctx, "https://gist.github.com/$lg")
                                "orgs" -> openWeb(ctx,
                                    "https://github.com/settings/organizations")
                            }
                        }
                    )
                    tab == Tab.Notifications -> NotificationsScreen(
                        onOpen = { openWeb(ctx, it) },
                        onMarkAllRead = {
                            MainScope().launch {
                                val ok = withContext(Dispatchers.IO) {
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
                        onOpenWeb = { openWeb(ctx, it) },
                        onLogin = { login = true },
                        onTotp = { totp = true },
                        onNewRepo = { newRepo = true },
                        onDownloads = { downloads = true },
                        onEditProfile = { editProfile = true },
                        onOpenUserTab = { l, t -> userTab = t; user = l }
                    )
                }
            }
        }

        /*
         * 崩溃记录弹窗。
         * 它是 Error 级信息的唯一出口 —— 复现一次崩溃的成本远高于看一眼这段。
         */
        if (crash != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { },
                title = { Text("上次崩溃（可复制反馈）", fontSize = 15.sp) },
                text = {
                    Text(
                        crash!!, color = c.fgMuted, fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", crash))
                        updateTip = "已复制到剪贴板"
                    }) { Text("复制", color = c.accent) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        try { com.hubmobile.app.CrashLog.clear(ctx) } catch (e: Throwable) {}
                        crash = null
                    }) { Text("知道了") }
                }
            )
        }

        // 更新弹窗：只在真有更新时出现（已是最新版完全静默）
        if (update != null) {
            val info = update!!
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { /* 强制更新不可关闭 */ },
                title = { Text("有新版本 ${info.version}", fontSize = 15.sp) },
                text = {
                    Column {
                        if (info.notes.isNotBlank()) {
                            Text(info.notes.take(300), color = c.fgMuted, fontSize = 12.sp,
                                maxLines = 8)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 走系统 DownloadManager（与网页版 installApk 前的下载一致），
                        // 下完由系统安装器接管
                        try {
                            val req = android.app.DownloadManager
                                .Request(android.net.Uri.parse(info.apkUrl))
                                .setTitle("githup ${info.version}")
                                .setMimeType("application/vnd.android.package-archive")
                                .setNotificationVisibility(
                                    android.app.DownloadManager.Request
                                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS,
                                    "githup/githup-${info.version}.apk")
                            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE)
                                    as android.app.DownloadManager
                            dm.enqueue(req)
                            updateTip = "已开始下载，完成后点通知安装"
                        } catch (e: Throwable) {
                            updateTip = "下载失败，请到 Releases 手动下载"
                        }
                        update = null
                    }) { Text("立即更新", color = c.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { update = null }) { Text("稍后") }
                }
            )
        }
        if (updateTip != null) {
            Box(Modifier.fillMaxSize().padding(bottom = 40.dp), Alignment.BottomCenter) {
                Box(Modifier.background(c.bgHi,
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(updateTip!!, color = c.fg, fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 尚未原生化的内容（Issue 详情的网页版入口等）暂借内置浏览器。
 * 注意不是回到 WebView 主界面，只是单开一个网页。
 */
private fun openWeb(ctx: Context, url: String) {
    val i = Intent(ctx, com.hubmobile.app.WebViewActivity::class.java)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_URL, url)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_TITLE, "githup")
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
}

