package com.hubmobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubmobile.ui.components.GithupFab
import com.hubmobile.ui.components.GithupTabBar
import com.hubmobile.ui.components.ToastHost
import com.hubmobile.ui.components.GithupTopBar
import com.hubmobile.ui.components.NavStyle
import com.hubmobile.ui.components.Octicons
import com.hubmobile.ui.components.TabSpec
import com.hubmobile.ui.screens.ExploreScreen
import com.hubmobile.ui.screens.HomeScreen
import com.hubmobile.ui.screens.NotificationsScreen
import com.hubmobile.ui.screens.ProfileScreen
import com.hubmobile.ui.screens.RepoScreen
import com.hubmobile.ui.screens.Sample
import com.hubmobile.ui.screens.SearchScreen
import com.hubmobile.ui.theme.Githup
import com.hubmobile.ui.theme.GithupDimens
import com.hubmobile.ui.store.LocalStore
import com.hubmobile.ui.theme.GithupTheme
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.weight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 githup 原生层一致：edge-to-edge，状态栏/导航栏透明
        enableEdgeToEdge()
        LocalStore.init(this)
        setContent {
            var dark by remember { mutableStateOf(LocalStore.dark) }
            var glassNav by remember { mutableStateOf(LocalStore.glassNav) }
            GithupTheme(darkTheme = dark) {
                GithupApp(
                    darkTheme = dark,
                    onToggleTheme = { dark = !dark; LocalStore.dark = dark },
                    navStyle = if (glassNav) NavStyle.Glass else NavStyle.Classic,
                    onToggleNav = { glassNav = !glassNav; LocalStore.glassNav = glassNav }
                )
            }
        }
    }
}

/**
 * 应用骨架 —— 对应 index.html 的 #app：
 *   #appbar（52dp 顶栏） + #view（可滚动内容） + .fab + #tabbar（悬浮胶囊底栏）
 *
 * 底栏是**脱离文档流**的悬浮胶囊，所以内容区必须自己让出
 * 「胶囊总高 + 抬升 + 离底距离」，否则列表最后一项会被压在胶囊下面。
 */
@Composable
fun GithupApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    navStyle: NavStyle = NavStyle.Glass,
    onToggleNav: () -> Unit = {}
) {
    val T = Githup
    var tab by remember { mutableIntStateOf(0) }
    var splash by remember { mutableStateOf(true) }
    var detailRepo by remember { mutableStateOf<com.hubmobile.ui.screens.Repo?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        delay(900)
        splash = false
    }

    val tabs = listOf(
        TabSpec("home", "首页", Octicons.Home, Octicons.HomeFill),
        TabSpec("notifications", "通知", Octicons.Bell, Octicons.BellFill),
        TabSpec("explore", "探索", Octicons.Telescope),
        TabSpec("search", "搜索", Octicons.Search),
        TabSpec("profile", "我的", Octicons.Person, Octicons.PersonFill)
    )
    val titles = listOf("githup", "通知", "探索", "搜索", "我的")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(T.canvas)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            GithupTopBar(
                title = if (detailRepo != null) detailRepo!!.name else titles[tab],
                showBack = detailRepo != null,
                onBack = { detailRepo = null },
                actions = {
                    IconButton(onClick = onToggleTheme, modifier = Modifier.size(GithupDimens.iconBtn)) {
                        Icon(
                            if (darkTheme) Octicons.Eye else Octicons.EyeClosed,
                            "切换主题", tint = T.fgMuted
                        )
                    }
                    IconButton(onClick = onToggleNav, modifier = Modifier.size(GithupDimens.iconBtn)) {
                        Icon(Octicons.ThreeBars, "切换底栏样式", tint = T.fgMuted)
                    }
                }
            )

            // 内容区：底部让出胶囊占的空间，避免最后一项被压住
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = GithupDimens.tabbarHeight + GithupDimens.tabbarLift + 8.dp)
            ) {
                when {
                    detailRepo != null -> RepoScreen(
                        repo = detailRepo!!,
                        onStar = { toast = if (it.starred) "已收藏 ${it.fullName}" else "已取消收藏 ${it.fullName}" }
                    )
                    else -> when (tab) {
                        0 -> HomeScreen(
                            onRepo = { detailRepo = it },
                            onStar = { toast = if (it.starred) "已收藏 ${it.fullName}" else "已取消收藏" }
                        )
                        1 -> NotificationsScreen()
                        2 -> ExploreScreen(onRepo = { detailRepo = it })
                        3 -> SearchScreen(onRepo = { detailRepo = it })
                        else -> ProfileScreen(
                            darkTheme = darkTheme,
                            onToggleTheme = onToggleTheme,
                            onToggleNav = onToggleNav
                        )
                    }
                }
            }
        }

        // FAB：贴着底栏上沿
        GithupFab(
            icon = Octicons.Plus,
            contentDescription = "新建",
            onClick = { },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(
                    end = 16.dp,
                    bottom = GithupDimens.tabbarHeight + GithupDimens.tabbarLift + GithupDimens.fabGap
                )
        )

        // 悬浮胶囊底栏
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
        ) {
            GithupTabBar(
                tabs = tabs,
                selectedIndex = tab,
                onSelect = { tab = it; detailRepo = null },
                badge = Sample.notifications.count { it.unread },
                style = navStyle
            )
        }

        // 轻提示：底部居中、两秒半自动消失，不拦点击
        ToastHost(message = toast, onDone = { toast = null })

        if (splash) SplashScreen()
    }
}

/** #splash —— Logo 呼吸 + 标题，900ms 后撤掉 */
@Composable
private fun SplashScreen() {
    val T = Githup
    val inf = rememberInfiniteTransition(label = "splash")
    val pulse by inf.animateFloat(
        initialValue = 1f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(T.bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Octicons.MarkGithub, null, tint = T.fg,
                modifier = Modifier
                    .size(72.dp)
                    .scale(pulse)
            )
            Text("githup", color = T.fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("GitHub 客户端", color = T.fgMuted, fontSize = 13.sp)
        }
    }
}
