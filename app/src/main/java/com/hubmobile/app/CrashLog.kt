package com.hubmobile.app

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自记：让「闪一下就退回桌面」不再是个死局。
 *
 * 为什么需要它 ——
 *   真机上出的问题，用户手上没有 logcat，也接不上电脑。
 *   现象只剩「点图标闪一下就没了」。没有堆栈，排查全靠猜。
 *
 *   这里在最外层装一个未捕获异常处理器：进程无论因为什么崩，
 *   先把堆栈写进 App 私有目录的一个小文件，再放行给系统默认处理器
 *   （该崩还是崩，只是崩得有据可查）。
 *
 * 存在哪 ——
 *   /data/data/<包名>/files/last_crash.txt
 *   覆盖式写「最近一次」，不做轮转：排查时只关心最后一次。
 *   文件里的内容包含：时间、包名、版本、Android 版本、机型，
 *   以及完整的异常堆栈。
 *
 * 怎么用 ——
 *   App 启动时调 CrashLog.install()，并把上次的记录读出来
 *   （CrashLog.readLast），挂到通知栏 / 通过 JsBridge 给前端看。
 *
 * Kotlin 迁移说明 ——
 *   原来那个 Thread.UncaughtExceptionHandler 匿名类，Kotlin 里直接写成
 *   lambda（对 Java 接口的 SAM 转换），少一层嵌套。逻辑一字未动。
 */
class CrashLog private constructor() {

    companion object {

        private const val FILE_NAME = "last_crash.txt"

        @Volatile
        private var installed = false

        /** 只装一次：装在 Application.onCreate 的最前面 */
        @JvmStatic
        fun install(ctx: Context) {
            if (installed) return
            installed = true

            val prev = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    write(ctx, t, e)
                } catch (ignored: Throwable) {
                    // 记日志本身再出事就只能放弃了，不能再往外抛
                }
                prev?.uncaughtException(t, e)
            }
        }

        private fun write(ctx: Context, t: Thread?, e: Throwable?) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("时间   : " + now())
            pw.println("包名   : " + safe(ctx.packageName))
            pw.println("版本   : " + versionOf(ctx))
            pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            pw.println("机型   : " + Build.MANUFACTURER + " " + Build.MODEL)
            pw.println("线程   : " + (t?.name ?: "?"))
            pw.println("---- 堆栈 ----")
            e?.printStackTrace(pw)
            pw.flush()
            val text = sw.toString()

            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(File(ctx.filesDir, FILE_NAME), false)
                fos.write(text.toByteArray(charset("UTF-8")))
                fos.flush()
            } catch (ignored: Throwable) {
                // 落盘失败（磁盘满 / 无权限）就放弃，不能让记日志本身把进程再搞崩一次
            } finally {
                close(fos)
            }

            /* 转交给错误日志：在当天小本子里留一条大白话记录 + 完整堆栈。
             * 崩溃全文仍以本文件为准；这里再记一份，是为了让用户下载的
             * 那个「错误日志」能独立看懂，不必再去找 last_crash.txt。 */
            try {
                LogBook.crash(ctx, crashBrief(e), text)
            } catch (ignored: Throwable) { }
        }

        /** 从异常里提炼一句人话摘要，给普通用户看 */
        private fun crashBrief(e: Throwable?): String {
            if (e == null) return "程序异常退出"
            val cls = e.javaClass.simpleName
            var msg = e.message
            if (!msg.isNullOrEmpty()) {
                if (msg.length > 80) msg = msg.substring(0, 80) + "…"
                return "$cls：$msg"
            }
            return cls
        }

        /** 读上次崩溃记录；没有就返回 null */
        @JvmStatic
        fun readLast(ctx: Context): String? {
            return try {
                val f = File(ctx.filesDir, FILE_NAME)
                if (!f.exists() || f.length() == 0L) return null
                val s = f.readText(charset("UTF-8"))
                if (s.isEmpty()) null else s
            } catch (t: Throwable) {
                null
            }
        }

        /** 用户看过之后清掉，免得下次启动又弹一遍 */
        @JvmStatic
        fun clear(ctx: Context) {
            try {
                val f = File(ctx.filesDir, FILE_NAME)
                if (f.exists()) f.delete()
            } catch (ignored: Throwable) { }
        }

        private fun now(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        private fun safe(s: String?): String = s ?: ""

        private fun versionOf(ctx: Context): String {
            return try {
                @Suppress("DEPRECATION")
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "${pi.versionName} (${pi.versionCode})"
            } catch (t: Throwable) {
                "?"
            }
        }

        private fun close(c: Closeable?) {
            if (c == null) return
            try { c.close() } catch (ignored: Throwable) { }
        }
    }
}
