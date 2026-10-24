package com.hubmobile.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 应用内浏览器。
 * 用于在 App 内部打开 GitHub 页面（如「创建访问令牌」），
 * 与主界面共享 Cookie，用户登录态可复用。
 */
class WebViewActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    private var webView: WebView? = null
    /** 当前地址：渲染进程崩溃后重建要用它把页面拉回来 */
    private var currentUrl: String? = null
    private var bar: ProgressBar? = null
    private var titleView: TextView? = null
    private var urlView: TextView? = null
    private var wrap: FrameLayout? = null
    // 视频全屏（同 MainActivity：不接回调，点全屏按钮就没反应）
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        var title = intent.getStringExtra(EXTRA_TITLE)
        if (title.isNullOrEmpty()) title = "浏览器"
        // 判空后 url 在此往下就是非空 String（Kotlin 的智能转换）
        if (url.isNullOrEmpty()) { finish(); return }

        // ---- 顶部工具条 ----
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val bar1 = LinearLayout(this)
        bar1.orientation = LinearLayout.HORIZONTAL
        bar1.gravity = Gravity.CENTER_VERTICAL
        bar1.setBackgroundColor(0xFF1F2328.toInt())
        val pad = dp(12)
        bar1.setPadding(pad, dp(10), pad, dp(10))

        val close = TextView(this)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 18f
        close.setPadding(dp(8), dp(4), dp(16), dp(4))
        close.setOnClickListener { finish() }
        bar1.addView(close)

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titles.layoutParams = tp

        titleView = TextView(this)
        titleView!!.text = title
        titleView!!.setTextColor(Color.WHITE)
        titleView!!.textSize = 14f
        titleView!!.setSingleLine(true)
        titleView!!.ellipsize = TextUtils.TruncateAt.END
        titles.addView(titleView)

        urlView = TextView(this)
        urlView!!.setTextColor(0xFFB0B7BF.toInt())
        urlView!!.textSize = 10f
        urlView!!.setSingleLine(true)
        urlView!!.ellipsize = TextUtils.TruncateAt.END
        urlView!!.text = url
        titles.addView(urlView)

        bar1.addView(titles)

        val external = TextView(this)
        external.text = "↗"
        external.setTextColor(Color.WHITE)
        external.textSize = 16f
        external.setPadding(dp(16), dp(4), dp(4), dp(4))
        external.setOnClickListener {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url))
                startActivity(i)
            } catch (ignored: Exception) { }
        }
        bar1.addView(external)

        root.addView(bar1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar!!.max = 100
        bar!!.visibility = View.GONE
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        // ---- WebView ----
        /* 整套配置收在 makeWebView() 里：渲染进程被回收之后要按同一套规格
         * 再做一个顶上去，写两遍必然某一边漏一样。 */
        webView = makeWebView()

        wrap = FrameLayout(this)
        wrap!!.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(wrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        currentUrl = url
        webView?.loadUrl(url)
    }

    /** 配好一个完整的 WebView（首次进入和崩溃后重建都走这一条路） */
    private fun makeWebView(): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        /* 同 MainActivity：关掉 WebView 的算法深色化。
           这里的页面是我们自己排版的（README / issue 等），深色由 CSS 自己管，
           让 WebView 再反色一次只会把配色弄乱。 */
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.isAlgorithmicDarkeningAllowed = false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                s.forceDark = WebSettings.FORCE_DARK_OFF
            }
        } catch (ignored: Throwable) {
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                bar ?: return
                bar!!.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                bar!!.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                if (titleView != null && !TextUtils.isEmpty(t)) titleView!!.text = t
            }

            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = v
                customCb = cb
                wv.visibility = View.GONE
                wrap?.addView(v, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                // 站内链接继续在内置浏览器打开
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, u: String): Boolean =
                !(u.startsWith("http://") || u.startsWith("https://"))

            /**
             * 渲染进程没了（为什么一定要接这个回调，见 MainActivity 同名方法
             * 里的说明）。这里按同一套规格换一个新的 WebView 重新打开当前
             * 地址；实在起不来就直接收掉，不留一张不会动的图。
             */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
                onRendererGone(view)

            override fun onPageFinished(view: WebView, u: String) {
                urlView?.text = u
                // 记下来：崩溃重建时要回到当时正在看的那一页，而不是最初的地址
                if (!TextUtils.isEmpty(u)) currentUrl = u
            }
        }

        return wv
    }

    private fun onRendererGone(dead: WebView): Boolean {
        try {
            val fresh = makeWebView()
            val w = wrap ?: return true
            w.removeView(dead)
            w.addView(fresh, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            webView = fresh
            val cu = currentUrl
            if (!TextUtils.isEmpty(cu)) fresh.loadUrl(cu!!)
            try { dead.destroy() } catch (ignored: Throwable) { }
        } catch (t: Throwable) {
            finish()
        }
        return true
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {           // 视频全屏中：先退出全屏
            exitFullscreen()
            return
        }
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        customView = null
        (v.parent as? ViewGroup)?.removeView(v)
        customCb?.onCustomViewHidden()
        customCb = null
        webView?.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
