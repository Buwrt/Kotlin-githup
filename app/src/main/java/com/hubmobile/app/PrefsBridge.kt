package com.hubmobile.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface

/**
 * 令牌与键值存储，顺带管剪贴板（都是"存点东西/取点东西"这一类）。
 *
 * 【从 JsBridge 拆出】逻辑一字未改，只换了语言。
 */
internal class PrefsBridge(private val ctx: BridgeContext) {

    /** 前端偏好存放的 SharedPreferences 名 */
    private val prefs = ctx.activity.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE)

    /**
     * 取 GitHub 访问令牌。
     *
     * 埋点四：令牌是最高价值的东西，读之前先过一遍防护链 ——
     * 非官方包直接拿不到令牌。这是最后一道，也是最实在的一道。
     */
    @JavascriptInterface
    fun getToken(): String {
        return SecurePrefs.get(ctx.activity, JsBridge.TOKEN_KEY, "")
    }

    /**
     * 存 / 清 令牌。传空串表示退出登录。
     */
    @JavascriptInterface
    fun setToken(token: String?) {
        if (token.isNullOrEmpty()) {
            SecurePrefs.remove(ctx.activity, JsBridge.TOKEN_KEY)
            /*
             * 退出登录：撤掉轮询，并清掉去重表 —— 换账号后 id 不该继承，
             * 否则新账号的旧通知会被当成"已经提醒过了"而漏掉。
             */
            try {
                NotifWatchReceiver.cancel(ctx.activity)
                NotifWatchReceiver.resetSeen(ctx.activity)
            } catch (ignored: Throwable) {
            }
        } else {
            SecurePrefs.put(ctx.activity, JsBridge.TOKEN_KEY, token)
            /* 登录成功：把通知轮询挂上（已挂过不会重复） */
            try {
                NotifWatchReceiver.scheduleSoonAfterLogin(ctx.activity)
            } catch (ignored: Throwable) {
            }
        }
    }

    @JavascriptInterface
    fun getPref(key: String?): String? {
        return prefs.getString(key ?: return null, null)
    }

    @JavascriptInterface
    fun setPref(key: String?, value: String?) {
        prefs.edit().putString(key ?: return, value).apply()
    }

    @JavascriptInterface
    fun copy(text: String?) {
        ctx.activity.runOnUiThread {
            val cm = ctx.activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("hub", text ?: ""))
        }
    }

    /**
     * 读系统剪贴板。
     *
     * 用在「两步验证器」的导入上：很多网站只给一串密钥、给不了能扫的二维码，
     * 用户唯一的办法是复制过来粘进去。少一次「自己找地方粘贴」的折腾。
     *
     * Android 10+ 对剪贴板读取有隐私限制：仅当应用处于前台（有窗口焦点）时
     * 才拿得到内容，后台读会返回空 —— 这是系统行为，不是这里能绕过的。
     * 所以拿不到就返回空串，前端会退回到让用户手动粘贴，不会卡住。
     */
    @JavascriptInterface
    fun getClipboard(): String {
        return try {
            val cm = ctx.activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return ""
            if (!cm.hasPrimaryClip()) return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0).coerceToText(ctx.activity)?.toString() ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }
}
