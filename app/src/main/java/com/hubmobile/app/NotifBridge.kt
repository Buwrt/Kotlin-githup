package com.hubmobile.app

import android.webkit.JavascriptInterface

/**
 * GitHub 通知的后台轮询开关。
 *
 * GitHub 不会把通知推给第三方 App，只能 App 自己定时去问 —— 所以这里
 * 有开关、有「立刻查一次」、也有「上次查是什么时候」，都是给设置页用的。
 *
 * 【从 JsBridge 拆出】逻辑一字未改，只是换了个文件、换了 Kotlin 写法。
 */
internal class NotifBridge(private val ctx: BridgeContext) {

    /**
     * 后台通知提醒是否开启（默认开）。
     *
     * 读不到（比如 SharedPreferences 坏了）时返回 true —— 宁可多提醒一次，
     * 也别让用户以为开着、其实早停了。
     */
    @JavascriptInterface
    fun notifWatchEnabled(): Boolean {
        return try {
            NotifWatchReceiver.isEnabled(ctx.activity)
        } catch (ignored: Throwable) {
            true
        }
    }

    @JavascriptInterface
    fun setNotifWatch(on: Boolean) {
        try {
            NotifWatchReceiver.setEnabled(ctx.activity, on)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 立刻检查一次 GitHub 通知，不等待下一轮。
     *
     * 给「通知页下拉刷新」用：让用户能当场验证后台提醒到底有没有在工作，
     * 而不是干等 15 分钟才知道。这不是开关，只是一次手动触发。
     */
    @JavascriptInterface
    fun checkNotificationsNow() {
        try {
            NotifWatchReceiver.checkNow(ctx.activity)
        } catch (ignored: Throwable) {
        }
    }

    /** 上次后台检查的时间戳（0 = 从没检查过），给设置页显示。 */
    @JavascriptInterface
    fun notifWatchLastCheck(): Long {
        return try {
            NotifWatchReceiver.lastCheck(ctx.activity)
        } catch (ignored: Throwable) {
            0L
        }
    }
}
