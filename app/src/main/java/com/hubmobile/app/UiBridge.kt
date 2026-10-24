package com.hubmobile.app

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.content.Context

/**
 * 界面相关的杂项：震动、清缓存、安全区、状态栏配色、深浅色、版本号。
 *
 * 这些方法的共同点是「问系统一句话」，彼此没什么关系，但都跟页面外观/手感
 * 有关，放一块儿比混在下载引擎里好找。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class UiBridge(private val ctx: BridgeContext) {

    @JavascriptInterface
    fun haptic() {
        ctx.activity.runOnUiThread {
            try {
                val v = ctx.activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    ?: return@runOnUiThread
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(12)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    @JavascriptInterface
    fun clearCache() {
        ctx.activity.runOnUiThread {
            try {
                ctx.webView?.clearCache(true)
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 真实的安全区高度（像素），给前端当 --safe-t / --safe-b 用。
     *
     * ⚠️ 为什么必须从原生拿，不能只靠 CSS 的 env(safe-area-inset-top)：
     * Android WebView 对 safe-area-inset-* 的支持**不可靠** ——
     * 它只在特定条件下（viewport-fit=cover + 部分 WebView 版本）才返回非 0，
     * 而且返回的是「刘海高度」而不是「状态栏高度」，两者在大多数机型上并不相等。
     * 实测影响：本应用是 edge-to-edge（SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN），
     * WebView 铺满整个屏幕、含被状态栏盖住的那一条。前端如果拿不到状态栏高度，
     * 顶栏就会被状态栏压住一截，看起来「标题位置不对 / 上面空一大块」。
     *
     * 返回 [状态栏高度, 底部还需让出量, 左侧安全区, 右侧安全区, 导航栏模式]，
     * 单位都是设备像素，失败时退化成 [0,0,0,0,0]，前端会退回 CSS env()。
     */
    @JavascriptInterface
    fun safeInsets(): String {
        var top = 0
        var left = 0
        var right = 0
        var mode = 0
        try {
            val r = ctx.activity.resources
            val idTop = r.getIdentifier("status_bar_height", "dimen", "android")
            if (idTop > 0) top = r.getDimensionPixelSize(idTop)
            mode = navBarMode()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                /* statusBars 的 top 是准的；左右两块要把 displayCutout
                   一起算进来 —— 只算 systemBars 的话横屏刘海那一条会漏掉。 */
                val s = probeSystemBar(top)
                top = s[0]
                left = s[1]
                right = s[2]
            }
        } catch (ignored: Throwable) {
        }
        return "[" + top + "," + bottomInsetPx() + "," + left + "," + right + "," + mode + "]"
    }

    /** 探针三种结果：WebView 够不够得到屏幕最底边。 */
    private val reachUnknown = 0
    private val reachYes = 1
    private val reachNo = 2

    /**
     * 页面底部到底还要让出多少 —— 这是 --safe-b 唯一该拿的数。
     *
     * ⚠️ 旧实现踩了两个坑，而且都只在「一部分机器」上发作：
     *
     *   坑一：SDK < 30 那条分支直接读 navigation_bar_height 资源。
     *      那是 ROM 写死的上限值（常见 48dp）—— 跟这台机器现在到底画没画
     *      导航栏、画了多高，完全没有对应关系。
     *
     *   坑二：重复让位。系统已经替我们把导航栏那条留白留出来了 ——
     *      WebView 本身的矩形就不含它。再往前端塞一层 48dp，等于凭空多出一块。
     *
     * 这里改成量：拿 WebView 自己在屏幕坐标里的下沿去比屏幕真实底边。
     *  · 够不到 → 系统已经留白了，页面自己**不用再让**，返回 0；
     *  · 够得到 → 页面确实铺到了屏幕底，那要看系统条这会儿有没有画出来。
     */
    private fun bottomInsetPx(): Int {
        return try {
            var navPx = 0
            var navVisible = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val ins = ctx.activity.window.decorView.rootWindowInsets
                if (ins != null) {
                    navVisible = ins.isVisible(WindowInsets.Type.navigationBars())
                    navPx = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    return 0   // 连 insets 都没有，别猜
                }
            } else {
                val idBot = ctx.activity.resources
                    .getIdentifier("navigation_bar_height", "dimen", "android")
                if (idBot > 0) navPx = ctx.activity.resources.getDimensionPixelSize(idBot)
            }
            decideBottomInset(reachScreenBottom(), navPx, navVisible)
        } catch (ignored: Throwable) {
            0
        }
    }

    /**
     * 把「够不够得到 + 系统条多大 + 画没画」折成一个该让的数。
     * 抽成纯函数是为了能照着同一份判据写单测，没有别的意思。
     */
    internal fun decideBottomInset(reach: Int, navPx0: Int, navVisible: Boolean): Int {
        val navPx = if (navPx0 < 0) 0 else navPx0
        return when (reach) {
            reachNo -> 0                            // 系统已留白，页面不再让一次
            reachYes -> if (navVisible) navPx else 0 // 真铺到底：只在系统条露出来时才让
            else -> 0                               // 量不出来就别猜
        }
    }

    /**
     * WebView 的下沿能不能碰到屏幕最底边。
     *
     * 用 getRealMetrics 拿的是**含系统装饰区**的真实屏幕高，
     * getLocationOnScreen 拿的是 View 在屏幕坐标系里的位置 —— 两者同坐标系。
     *
     * 布局还没走完时宽高为 0，这时返回 UNKNOWN，绝不能当成「贴到底」。
     */
    private fun reachScreenBottom(): Int {
        val v: View = ctx.webView ?: ctx.activity.window.decorView
        if (v.width <= 0 || v.height <= 0) return reachUnknown
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val dm = DisplayMetrics()
        try {
            @Suppress("DEPRECATION")
            ctx.activity.windowManager.defaultDisplay.getRealMetrics(dm)
        } catch (ignored: Throwable) {
            return reachUnknown
        }
        if (dm.heightPixels <= 0) return reachUnknown
        /* 留 1px 容差：某些 ROM 因为舍入会让 View 差一个像素够不到 */
        return if ((loc[1] + v.height) >= dm.heightPixels - 1) reachYes else reachNo
    }

    private fun probeSystemBar(fallbackTop: Int): IntArray {
        val out = intArrayOf(fallbackTop, 0, 0)
        try {
            val ins = ctx.activity.window.decorView.rootWindowInsets ?: return out
            out[0] = ins.getInsets(WindowInsets.Type.statusBars()).top
            val side = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            out[1] = side.left
            out[2] = side.right
        } catch (ignored: Throwable) {
        }
        return out
    }

    /**
     * 当前导航栏形态：0=未知 / 1=三键 / 2=两键 / 3=手势 / 9=没有软导航栏。
     *
     * config_navBarInteractionMode 是 AOSP 与各主流 ROM 都会写的一条内部常量
     * （0=三键 1=两键 2=手势），比 WindowInsets 更早可用，也不受「这会儿
     * 临时隐藏了没有」影响。
     */
    private fun navBarMode(): Int {
        try {
            val r = ctx.activity.resources
            val id = r.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (id > 0) {
                return when (r.getInteger(id)) {
                    0 -> 1
                    1 -> 2
                    2 -> 3
                    else -> 0
                }
            }
        } catch (ignored: Throwable) {
        }
        try {
            if (ViewConfiguration.get(ctx.activity).hasPermanentMenuKey()) return 9
        } catch (ignored: Throwable) {
        }
        return 0
    }

    @JavascriptInterface
    fun setStatusBar(colorHex: String?) {
        ctx.activity.runOnUiThread {
            try {
                /* ⚠️ 传进来的实色会被**忽略**，状态栏/导航栏一律保持透明。
                 *
                 * 原因：状态栏是盖在 WebView 之上的一条系统绘制区。给它上实色，
                 * 就等于在页面顶部贴了一条不透明的色带 —— 顶栏的毛玻璃再怎么调，
                 * 上面那一条永远是死白/死黑，看起来就是「标题被顶下去、上方空一块」。
                 *
                 * 参数保留不删：前端还在按主题传 #ffffff / #010409，只是不再采用。
                 * 这里仍然按颜色算一次明暗，用来决定状态栏图标是深色还是浅色。 */
                val light = isLightColor(Color.parseColor(colorHex))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ctx.activity.window.statusBarColor = Color.TRANSPARENT
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val decor = ctx.activity.window.decorView
                    var flags = decor.systemUiVisibility
                    flags = if (light) {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
                    /* 保持 edge-to-edge：内容继续延伸到状态栏底下，
                       否则透明状态栏会让顶栏整体下移，又变成「上面空一块」。 */
                    flags = flags or (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                    decor.systemUiVisibility = flags
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.activity.window.navigationBarColor = Color.TRANSPARENT
                    val decor = ctx.activity.window.decorView
                    var flags = decor.systemUiVisibility
                    flags = if (light) {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    } else {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    }
                    decor.systemUiVisibility = flags
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val lum = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0
        return lum > 0.6
    }

    /**
     * 系统当前是否处于深色模式。
     *
     * 前端「跟随系统」不能只靠 CSS 的 prefers-color-scheme —— 在 Android
     * WebView 里这个查询受 force-dark / algorithmic-darkening 影响，部分机型
     * 会一直返回 light 或一直返回 dark。Configuration.uiMode 是系统给的权威值。
     */
    @JavascriptInterface
    fun systemDark(): Boolean {
        return try {
            val mode = ctx.activity.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        } catch (ignored: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun version(): String {
        return try {
            ctx.activity.packageManager.getPackageInfo(ctx.activity.packageName, 0).versionName
                    ?: "1.0.0"
        } catch (ignored: Exception) {
            "1.0.0"
        }
    }
}
