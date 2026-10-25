package com.hubmobile.app.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/*
 * 路线 B 的宿主 Activity。
 *
 * 为什么另起一个 Activity 而不是直接改 MainActivity：
 *   MainActivity 是普通 android.app.Activity，里面挂着一整套 WebView
 *   生命周期、JsBridge 注入、下载回调、权限回调，动它风险太大。
 *   Compose 需要 ComponentActivity（androidx），所以这边新开一个入口，
 *   两边互不干扰：
 *     · 已迁移的页面 → 本 Activity（Compose 原生渲染）
 *     · 其他一切     → 仍然走 MainActivity / WebViewActivity（网页）
 *
 * 这样路线 B 可以「一个页面一个页面地上」，任何一页出问题都只是
 * 那一页回退到网页，不会整个 App 起不来。
 */

class ComposeHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OWNER = "owner"
        const val EXTRA_NAME = "name"

        /** 从别处跳到原生仓库页 */
        fun open(ctx: Context, owner: String, name: String) {
            val i = Intent(ctx, ComposeHostActivity::class.java)
            i.putExtra(EXTRA_OWNER, owner)
            i.putExtra(EXTRA_NAME, name)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = intent.getStringExtra(EXTRA_OWNER) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""

        // 没给仓库坐标就别硬撑，直接回网页版 —— 网页版什么页面都能开
        if (owner.isBlank() || name.isBlank()) {
            openInWeb("https://github.com", finishAfter = true)
            return
        }

        setContent {
            HubTheme {
                RepoScreen(
                    owner = owner,
                    name = name,
                    onBack = { finish() },
                    /*
                     * 单个文件暂时还是交给网页渲染：涉及 Markdown 渲染、代码高亮、
                     * 图片与二进制预览，那部分还没迁。
                     * branch 由页面自己带出来（它已经知道当前分支），
                     * 这里不能再去查一次 —— 回调发生在主线程，同步请求会卡死界面。
                     */
                    onOpenFile = { path, branch ->
                        openInWeb(
                            "https://github.com/$owner/$name/blob/$branch/$path",
                            finishAfter = false
                        )
                    },
                    onOpenInWeb = {
                        openInWeb("https://github.com/$owner/$name", finishAfter = false)
                    },
                    onOpenWeb = { openInWeb(it, finishAfter = false) }
                )
            }
        }
    }

    /** 未原生化的内容：交回网页版。 */
    private fun openInWeb(url: String, finishAfter: Boolean) {
        val i = Intent(this, com.hubmobile.app.WebViewActivity::class.java)
        i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_URL, url)
        i.putExtra(com.hubmobile.app.WebViewActivity.EXTRA_TITLE, "githup")
        startActivity(i)
        if (finishAfter) finish()
    }
}
