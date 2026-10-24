package com.hubmobile.app

import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface

/**
 * 错误日志的对外接口：前端上报 → 保存 → 分享。
 *
 * 「记什么」和「记在哪」都在 LogBook 里，这里只负责把前端的调用接过来、
 * 以及把结果回给前端（保存成功还是没成功）。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。原来那段 `new Runnable(){...}` 的
 * 匿名内部类在 Kotlin 里就是传给 background 的一个 lambda，少两层嵌套。
 */
internal class LogBridge(private val ctx: BridgeContext) {

    /**
     * 前端上报一条错误（功能出问题了）。
     *
     * 这是「翻译引擎全部不可用」这类业务错误的主要入口 ——
     * 前端在出错的地方调一次，这里就落一条大白话记录。
     *
     * @param what 大白话描述，如「翻译引擎全部不可用」
     * @param why  补充原因（可为空）
     */
    @JavascriptInterface
    fun logError(what: String?, why: String?) {
        try {
            LogBook.error(ctx.activity, what ?: "", why ?: "")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 前端上报一条关键操作（不是错误，用来还原现场）。
     *
     * @param what 如「开始翻译本页」/「上传文件」
     */
    @JavascriptInterface
    fun logNote(what: String?) {
        try {
            LogBook.note(ctx.activity, what ?: "")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 「保存到本地」：把今天的错误日志写进 Download/githup/错误日志/。
     * 写完回前端一个结果（ok / 失败），由前端决定提示什么。
     *
     * 保存要读写公共目录，必须放到后台线程 —— 在主线程上做 I/O 会卡住页面。
     */
    @JavascriptInterface
    fun saveLog(cbId: String?) {
        ctx.background {
            val ok = try {
                LogBook.save(ctx.activity)
            } catch (ignored: Throwable) {
                false
            }
            if (!cbId.isNullOrEmpty()) {
                ctx.runJs(
                    "window.Native&&Native.onLogSaved&&Native.onLogSaved('"
                        + cbId + "'," + ok + ")"
                )
            }
        }
    }

    /**
     * 「分享」：把日志准备成一份可交给外部应用的副本，弹出系统分享面板。
     *
     * 不想发直接关掉即可（这份副本是临时的，不影响已保存到 Download 的那份）。
     */
    @JavascriptInterface
    fun shareLog() {
        ctx.background {
            val share: Uri? = try {
                LogBook.share(ctx.activity)
            } catch (ignored: Throwable) {
                null
            }
            if (share == null) return@background   // 写不出副本：没有可分享的东西

            try {
                val send = Intent(Intent.ACTION_SEND)
                send.type = "text/plain"
                send.putExtra(Intent.EXTRA_SUBJECT, "githup 错误日志")
                send.putExtra(Intent.EXTRA_STREAM, share)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = Intent.createChooser(send, "分享错误日志")
                chooser.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // 起界面必须在 UI 线程
                ctx.activity.runOnUiThread {
                    try {
                        ctx.activity.startActivity(chooser)
                    } catch (ignored: Throwable) {
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 兼容旧调用：既保存又分享（当前前端已不用，留作后备）。 */
    @JavascriptInterface
    fun exportLog() {
        saveLog("")
        shareLog()
    }
}
