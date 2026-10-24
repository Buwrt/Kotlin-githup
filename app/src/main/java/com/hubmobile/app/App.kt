package com.hubmobile.app

import android.app.Application

/**
 * 进程入口。
 *
 * 未加固版本没有防护链，Application 这一层就只剩两件事：
 * 装崩溃自记、装错误日志。
 *
 * 为什么非要这个类 ——
 *   CrashLog / LogBook 都必须在「任何可能出错的代码之前」准备好，
 *   而 Application.onCreate 是进程里最早能碰到的用户代码。
 *   装在这里，后面不管哪个界面、哪个服务出事，都能先落一份记录。
 *   真机上用户拿不到 logcat，「闪一下就没了」如果没有这份记录，
 *   就只能靠猜。
 *
 * Kotlin 迁移说明 ——
 *   静态成员搬进 companion object，并逐个打上 @JvmStatic / @JvmField：
 *   MainActivity、JsBridge 这些还是 Java 的类，写法是 `App.sForeground = true`
 *   这种「类名直接点静态字段」。没有这两个注解，Java 侧就得写成
 *   `App.Companion.getSForeground()`，等于逼着把调用方一起改，
 *   那就不叫平滑迁移了。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        /* 第一件事：装崩溃自记。越早越好。 */
        try { CrashLog.install(this) } catch (ignored: Throwable) { }

        /* 第二件事：装错误日志（清掉过期文件）。 */
        try { LogBook.install(this) } catch (ignored: Throwable) { }

        /* 顺手把常用域名的 DNS 解析掉。
         *
         * 首屏那几个请求都要先解析 api.github.com，运营商 DNS 动辄上百毫秒，
         * 四个域名串起来够呛。这里在后台线程提前解析并进系统缓存，
         * 等用户点开页面时基本都是白捡的。
         * 预热失败无所谓，真正的请求该解析还是会解析。
         *
         * 与 githup（加固版）的差异：那边的 check() 跑防护链，本库没有防护链，
         * 所以只保留了这一项。 */
        try { Http.warmUp() } catch (ignored: Throwable) { }
    }

    companion object {

        /** 当前进程实例，供其它类读取 */
        private var sInstance: App? = null

        @JvmStatic
        fun app(): App? = sInstance

        /* ---- 防护链状态（本库无防护链，恒为「通过」）----
         *
         * 留着这三个字段和 passed()，纯粹是为了让 LogBook 这类共用代码
         * 在两个库里写法完全一致 —— 不加的话，同一份 LogBook.java
         * 在私有库就编译不过。 */
        @JvmField var sBrokenRing = 0
        @JvmField var sBrokenDetail = ""
        @JvmField var sBrokenCode = ""

        /** 未加固库没有防护链，恒为「通过」 */
        @JvmStatic
        fun passed(): Boolean = true

        /**
         * App 当前是否在前台。
         *
         * 两步验证器的「后台常驻动态码」只在后台出现：用户正看着屏幕时
         * 页面上就有码，通知栏再挂一条纯属打扰。所以前台一律撤掉、后台才挂出来。
         *
         * 放在 Application 上而不是某个 Activity：后台常驻服务、JsBridge
         * 都要读它，这两者都不该依赖某个具体的界面实例。
         */
        @JvmField var sForeground = false
    }

    init {
        sInstance = this
    }
}
