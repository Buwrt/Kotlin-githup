package com.hubmobile.app.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 纯原生主界面。
 *
 * 这是 App 的新启动入口（AndroidManifest 里 MAIN/LAUNCHER 已移到本类），
 * 整棵 UI 树都是 Compose —— 不加载 WebView、不渲染 assets/web 里的任何页面。
 *
 * MainActivity（WebView 版）仍然存在但已不再是入口，可在"设置 → 打开网页版"
 * 里手动进入，作为回退通道。
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
        setContent {
            HubTheme {
                // 外链进来带仓库坐标时直接落到仓库页
                AppRoot(initialRepo = if (owner.isNotBlank()) owner to name else null)
            }
        }
    }
}

private enum class Tab(val label: String) { Home("首页"), Search("搜索"), Settings("设置") }

@Composable
private fun AppRoot(initialRepo: Pair<String, String>?) {
    var tab by remember { mutableStateOf(Tab.Home) }
    // 路由：null=当前 tab 页；Repo=仓库页；User=用户页
    val ctx = LocalContext.current
    var repo by remember { mutableStateOf(initialRepo) }
    var user by remember { mutableStateOf<String?>(null) }
    // 仓库内子页：文件查看 / Issues。用 Pair 存参数，避免再定义数据类
    var file by remember { mutableStateOf<Triple<String, String, String>?>(null) } // owner/branch/path
    var issues by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = HubColors.Surface) {
                Tab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && repo == null && user == null && file == null && issues == null,
                        onClick = { tab = t; repo = null; user = null; file = null; issues = null },
                        label = { Text(t.label, fontSize = 11.sp) },
                        icon = { TabIcon(t) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = HubColors.Accent,
                            selectedTextColor = HubColors.Accent,
                            indicatorColor = HubColors.SurfaceHi,
                            unselectedIconColor = HubColors.Muted,
                            unselectedTextColor = HubColors.Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                file != null -> {
                    val (o, br, p) = file!!
                    val n = repo?.second ?: return@Box
                    FileScreen(
                        owner = o, name = n, branch = br, path = p,
                        onBack = { file = null },
                        onOpenWeb = { openWeb(ctx, it) }
                    )
                }
                issues != null -> {
                    val (o, n) = issues!!
                    IssuesScreen(
                        owner = o, name = n,
                        onBack = { issues = null },
                        onOpenWeb = { openWeb(ctx, it) }
                    )
                }
                repo != null -> {
                    val (o, n) = repo!!
                    RepoScreen(
                        owner = o, name = n,
                        onBack = { repo = null },
                        // 文件查看现在是原生页 —— 这是本轮最关键的补齐，
                        // 在此之前点开文件会跳内置浏览器
                        onOpenFile = { path, branch -> file = Triple(o, branch, path) },
                        onOpenInWeb = { openWeb(ctx, "https://github.com/$o/$n") },
                        onOpenIssues = { issues = o to n }
                    )
                }
                user != null -> UserScreen(
                    login = user!!,
                    onOpenRepo = { o, n -> repo = o to n; file = null; issues = null },
                    onBack = { user = null }
                )
                tab == Tab.Home -> HomeScreen(
                    onOpenRepo = { o, n -> repo = o to n; file = null; issues = null },
                    onOpenUser = { user = it },
                    onNeedToken = { tab = Tab.Settings }
                )
                tab == Tab.Search -> SearchScreen(
                    onOpenRepo = { o, n -> repo = o to n; file = null; issues = null },
                    onOpenUser = { user = it }
                )
                tab == Tab.Settings -> SettingsTab()
            }
        }
    }
}

/** 底部图标：不引 material-icons-extended，用几何形状画，避免包体膨胀 */
@Composable
private fun TabIcon(t: Tab) {
    val c = HubColors.Accent
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        when (t) {
            Tab.Home -> {
                drawRoundRect(
                    color = c,
                    topLeft = Offset(w * 0.15f, h * 0.42f),
                    size = Size(w * 0.70f, h * 0.48f),
                    cornerRadius = CornerRadius(3f, 3f)
                )
                drawRoundRect(
                    color = c,
                    topLeft = Offset(w * 0.38f, h * 0.62f),
                    size = Size(w * 0.24f, h * 0.30f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
            Tab.Search -> {
                drawCircle(
                    color = c, radius = w * 0.22f,
                    center = Offset(w * 0.42f, h * 0.42f)
                )
                drawLine(
                    color = c,
                    start = Offset(w * 0.58f, h * 0.58f),
                    end = Offset(w * 0.82f, h * 0.82f),
                    strokeWidth = 3f
                )
            }
            Tab.Settings -> {
                drawCircle(
                    color = c, radius = w * 0.17f,
                    center = Offset(w * 0.5f, h * 0.5f)
                )
                drawCircle(
                    color = HubColors.Surface, radius = w * 0.08f,
                    center = Offset(w * 0.5f, h * 0.5f)
                )
            }
        }
    }
}

/** 设置页：令牌管理 + 一个回退到网页版的入口 */
@Composable
private fun SettingsTab() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        SettingsScreen(onSaved = {})
        Surface(color = Color.Transparent) {
            androidx.compose.material3.TextButton(
                onClick = {
                    ctx.startActivity(
                        Intent(ctx, com.hubmobile.app.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text("打开网页版（旧界面）", color = HubColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

/* 尚未原生化的两类内容，临时借用内置浏览器，不回到 WebView 主界面 */

private fun openWeb(ctx: Context, url: String) {
    val i = Intent(ctx, com.hubmobile.app.WebViewActivity::class.java)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_URL, url)
    i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_TITLE, "githup")
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
}
