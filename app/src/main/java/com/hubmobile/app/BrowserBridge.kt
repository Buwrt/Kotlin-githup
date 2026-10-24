package com.hubmobile.app

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 打开链接：应用内浏览器 / 交给系统。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class BrowserBridge(private val ctx: BridgeContext) {

    /** 在应用内置浏览器中打开链接（共享 Cookie，可复用网页登录态）。 */
    @JavascriptInterface
    fun openInApp(url: String?, title: String?) {
        if (url.isNullOrEmpty()) return
        ctx.activity.runOnUiThread {
            try {
                val i = Intent(ctx.activity, WebViewActivity::class.java)
                i.putExtra(WebViewActivity.EXTRA_URL, url)
                i.putExtra(WebViewActivity.EXTRA_TITLE, title)
                ctx.activity.startActivity(i)
            } catch (ignored: Exception) {
                Toast.makeText(ctx.activity, "无法打开页面", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 用系统浏览器 / 对应的 App 打开一条外链。
     *
     * CATEGORY_BROWSABLE 只给 http/https 加，不能再无条件加：
     * 它是给「浏览器可安全打开的网页」用的过滤条件，加上它之后系统只会匹配
     * 声明了自己能处理 browsable 的 Activity。自定义 scheme（mqqapi://、
     * alipays://、weixin:// 之类）只有对应 App 的 Activity 能接，而这些
     * Activity 基本都不声明 browsable —— 于是匹配结果为空，startActivity 抛
     * ActivityNotFoundException，被 catch 成一句「无法打开链接」。
     * 「一键加群」点了没反应，根子就在这里。
     *
     * 自定义 scheme 走不带 category 的匹配；万一还是没人接（比如对应 App 没装），
     * 退化成用浏览器打开原链接，绝不留下「点了没反应」。
     */
    @JavascriptInterface
    fun openExternal(url: String?) {
        ctx.activity.runOnUiThread {
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(ctx.activity, "链接为空", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                if (isWebUrl(url)) i.addCategory(Intent.CATEGORY_BROWSABLE)
                ctx.activity.startActivity(i)
            } catch (ignored: Exception) {
                // 没人接这条 scheme（多半是对应 App 没装）：退回浏览器打开
                try {
                    if (!isWebUrl(url)) {
                        val web = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        web.addCategory(Intent.CATEGORY_BROWSABLE)
                        ctx.activity.startActivity(web)
                        return@runOnUiThread
                    }
                } catch (ignored2: Exception) {
                }
                Toast.makeText(ctx.activity, "无法打开链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 是不是 http/https 链接（只有这类才该带 CATEGORY_BROWSABLE） */
    private fun isWebUrl(url: String): Boolean {
        return try {
            val s = Uri.parse(url).scheme
            s != null && (s.equals("http", true) || s.equals("https", true))
        } catch (ignored: Exception) {
            false
        }
    }
}
