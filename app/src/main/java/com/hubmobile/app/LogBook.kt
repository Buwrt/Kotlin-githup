package com.hubmobile.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误日志：把「用着用着出的毛病」记成一份谁都看得懂的小本子。
 *
 * 它和崩溃自记（CrashLog）的分工 ——
 *   CrashLog 只管「进程崩了没有」，记的是完整 Java 堆栈；
 *   LogBook 管「功能不好使了没有」——翻译引擎全挂、请求一直失败、
 *   页面脚本报错、关键操作，这些都不会让 App 崩，但都是用户真实碰到的问题。
 *
 * 为什么不用专业术语 ——
 *   这份日志是给普通用户看的，也是给帮忙排查的人 / AI 看的。
 *   写「翻译引擎全部不可用」比写「NoAvailableEngineException at
 *   TranslateService:2633」有用得多 —— 前者谁都能看懂，后者要人去翻代码。
 *
 * 存在哪 ——
 *   **平时只记在 App 内部**（应用私有目录），不碰公共下载目录。
 *
 *   以前是「一出错就往 Download/githup/错误日志/ 写」—— 结果用户没要求
 *   导出，下载目录里也天天冒出一个日志文件，还跟着下载历史一起被同步
 *   （历史跟着本地走，于是日志也混在里面）。
 *
 *   现在的规定是：
 *     · 出错了        → 自动记进 **App 内部**的小本子（用户看不见，不污染下载目录）
 *     · 想拿去用      → 自己去「关于 githup → 下载错误日志 → 保存到本地」
 *                       手动导出，这时才写进 Download/githup/错误日志/
 *
 *   所以「自动」和「手动」是分开的：内部随时记，公共目录只有手动才有。
 *
 * 按天清零 ——
 *   文件名带日期。第二天第一次写日志时，老文件原样留在那儿（用户想看还能看），
 *   但新的一天从空白开始 —— 「今天出过什么问题」一打开就是干净的。
 *   同时只保留最近 7 天，更早的自动删掉。
 *
 * 写失败怎么办 ——
 *   Android 10+ 的公共目录受分区存储限制。所以这里备了三级退路：
 *   公共 Download 目录 → 应用外部目录 → 应用内部目录。哪级能写用哪级；
 *   真的一处都写不进也绝不抛异常（用户不该因为记日志失败而受影响）。
 *
 * 【Kotlin 迁移】
 *   Java 里的 `final class + 私有构造 + 全静态方法` 在 Kotlin 里就是 object
 *   单例：编译出来同样是一个类、同样一批静态方法，Java 那边写法不变。
 *   对外方法都带 @JvmStatic，保证 JsBridge / MainActivity / CrashLog 这些
 *   Java 调用方一行都不用改。
 */
internal object LogBook {

    /**
     * 公共目录里的子目录名（两个常量，用错一个文件就「消失」）——
     *
     *   File 直接写（Android 9 及以下）：父目录已经是 Download，
     *   这里只写 Download 下面的部分。
     *
     *   MediaStore（Android 10+）：RELATIVE_PATH 必须从存储根算起，
     *   且必须以 "Download/" 开头。传 "githup/错误日志" 会在 insert 时
     *   直接抛 IllegalArgumentException（Primary directory not allowed），
     *   而且写入失败被我们 catch 住了 —— 表现就是一切看着正常，
     *   文件却永远落不进公共目录，悄悄退到了私有目录。
     *   上一版「按路径找不到文件」就是这个原因。
     */
    private const val PUBLIC_SUBDIR = "githup/错误日志"
    /** MediaStore 的 RELATIVE_PATH：从存储根算起，必须以 "Download/" 开头 */
    private const val PUBLIC_RELATIVE = "Download/$PUBLIC_SUBDIR"
    /** 日志文件名前缀 */
    private const val FILE_PREFIX = "错误日志-"
    /** 保留最近几天 */
    private const val KEEP_DAYS = 7L
    /** 单个文件上限，超了另起一个（免一天写到几十兆） */
    private const val MAX_BYTES = 512L * 1024L

    @Volatile private var installed = false
    @Volatile private var sessionMarked = false
    /** 已经探测出可写的目录，缓存起来省得每次写都探一遍 */
    @Volatile private var resolvedDir: File? = null

    /* ==================================================================
     *  初始化
     * ================================================================== */

    /** App.onCreate 调一次：清掉过期日志。 */
    @JvmStatic
    fun install(ctx: Context?) {
        if (ctx == null || installed) return
        installed = true
        try { cleanOld(ctx) } catch (ignored: Throwable) { }
    }

    /**
     * 每个界面起来时调一次，保证热启动也有个起点。
     * （覆盖安装后系统常沿用旧进程，Application.onCreate 不再触发，
     *  不补这一条的话当天第一行日志会缺上下文。）
     */
    @JvmStatic
    fun markSession(ctx: Context?, where: String?) {
        if (ctx == null || sessionMarked) return
        sessionMarked = true
        note(ctx, "打开应用（" + nz(where, "界面") + "）")
    }

    /* ==================================================================
     *  写日志（对外就这三个方法）
     * ================================================================== */

    /**
     * 记一条错误：功能出问题了。
     *
     * @param what 大白话描述，如「翻译引擎全部不可用」
     * @param why  补充原因（可为空），如「试了 5 个引擎都没响应」
     */
    @JvmStatic
    fun error(ctx: Context?, what: String?, why: String?) {
        if (ctx == null || what == null) return
        write(ctx, "【出错了】" + what + (if (isBlank(why)) "" else "。" + why))
    }

    /**
     * 记一条普通记录：关键操作，用来还原「我当时在干什么」。
     *
     * @param what 如「开始翻译」/「退出登录」/「上传文件：xxx.zip」
     */
    @JvmStatic
    fun note(ctx: Context?, what: String?) {
        if (ctx == null || what == null) return
        write(ctx, "· " + what)
    }

    /**
     * 记崩溃：由 CrashLog 在最外层异常处理器里转交。
     * 这条路必须最省事、最不可能失败。
     *
     * @param brief 一句话摘要
     * @param stack 完整堆栈（可为空）
     */
    @JvmStatic
    fun crash(ctx: Context?, brief: String?, stack: String?) {
        if (ctx == null) return
        val sb = StringBuilder()
        sb.append("【闪退了】").append(nz(brief, "程序异常退出"))
        if (!stack.isNullOrEmpty()) {
            sb.append("\n    详细堆栈（给开发者看）：")
            for (l in stack.split("\n")) {
                if (l.trim().isEmpty()) continue
                sb.append("\n      ").append(l.trim())
            }
        }
        write(ctx, sb.toString())
    }

    /* ==================================================================
     *  实际落盘
     * ================================================================== */

    private fun write(ctx: Context, text: String) {
        val line = stamp() + "  " + text + "\n"

        /*
          ⚠️ 只写 App 内部，**不碰公共下载目录**。

          以前这里第一句就是 `if (appendToPublic(ctx, line)) return;` ——
          一出错就往 Download/githup/错误日志/ 写，于是用户没导出过，
          下载目录里也天天冒出一个日志文件；它还会被「历史跟着本地走」
          的同步逻辑扫进下载记录里。

          现在公共目录**只有用户手动点「保存到本地」时**才写（见 save()）。
          出错了自动记，但记在内部 —— 这是要求的「自动保存」，
          和「手动导出」是两件事。
        */
        try {
            val dir = logDir(ctx) ?: return
            if (!dir.exists() && !dir.mkdirs()) return

            var f = todayFile(dir)
            f = rotateIfNeeded(f)   // 写满了就换下一个文件（必须接住返回值）

            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(f, true)
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
            } finally {
                close(fos)
            }
        } catch (t: Throwable) {
            // 记日志失败绝不能影响 App 本身
            try { Log.w("githup-log", "写日志失败: $t") } catch (ignored: Throwable) { }
        }
    }

    /* ---------------- 公共目录（MediaStore）----------------
     *
     * Android 10 起，App 不能再用 File 直接往公共 Download 目录写东西，
     * 必须通过 MediaStore 登记一个条目，再往里写数据流。
     * 这也是唯一能让用户「用文件管理器按平常路径找到」的合法做法。
     *
     * 两段式（API 29+）：
     *   1. 插入一条记录，IS_PENDING=1（表示「还在写，先别给别人看」）
     *   2. 写数据流
     *   3. 把 IS_PENDING 改回 0（提交，这时才真正出现在文件管理器里）
     *
     * Android 9 及以下没有分区存储，直接 File 写就行。
     */

    /** 追加一行到公共目录的当天日志；成功返回 true */
    private fun appendToPublic(ctx: Context, line: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 29) appendToPublicLegacy(ctx, line)
            else appendToPublicModern(ctx, line)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * API 29+：把一行追加到公共目录里的当天日志。
     *
     * 关键点：MediaStore 每条记录就是「一个文件」，不能对同一条反复 insert
     * （那样文件管理器里会冒出一串同名文件）。所以是先按文件名查：
     *   找到今天那条 → 读出原内容 + 新行，用「wt」整份重写
     *   没找到        → 才新建一条
     * 无论哪条路，文件管理器里今天始终只有一个
     * 「错误日志-2026-10-01.txt」。
     */
    private fun appendToPublicModern(ctx: Context, line: String): Boolean {
        val cr = ctx.contentResolver ?: return false
        val parent = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = FILE_PREFIX + todayStr() + ".txt"

        var item = findPublicToday(ctx)

        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_RELATIVE)
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1)

        if (item == null) {
            item = cr.insert(parent, cv) ?: return false
        } else {
            // 已有：标回 pending，免得别人读到写一半的内容
            try { cr.update(item, cv, null, null) } catch (ignored: Throwable) { }
        }

        var os: OutputStream? = null
        try {
            // 先读旧内容（刚新建的记录读出来是空的，正好）
            val old = readPublic(ctx, item)
            os = cr.openOutputStream(item, "wt") ?: return false
            if (old != null && old.isNotEmpty()) os.write(old)
            os.write(line.toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (t: Throwable) {
            return false
        } finally {
            close(os)
        }

        // 提交：这一步之后文件才在文件管理器里可见
        val done = ContentValues()
        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
        try { cr.update(item, done, null, null) } catch (ignored: Throwable) { }

        return true
    }

    /** API ≤28：没有分区存储，直接 File 写 */
    private fun appendToPublicLegacy(ctx: Context, line: String): Boolean {
        return try {
            val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return false
            val dir = File(pub, PUBLIC_SUBDIR)
            if (!dir.exists() && !dir.mkdirs()) return false
            val f = File(dir, FILE_PREFIX + todayStr() + ".txt")
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(f, true)
                fos.write(line.toByteArray(Charsets.UTF_8))
                fos.flush()
            } finally {
                close(fos)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 读公共目录里某个条目已有的内容 */
    private fun readPublic(ctx: Context, item: Uri): ByteArray? {
        var input: InputStream? = null
        return try {
            input = ctx.contentResolver.openInputStream(item) ?: return null
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                bos.write(buf, 0, r)
            }
            bos.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            close(input)
        }
    }

    /**
     * 查公共目录里今天那个日志条目的 Uri。
     *
     * MediaStore 里同一个文件名会反复出现（每次 insert 都是一条新记录），
     * 所以按名字查最新的那条，用它来读/追加。
     */
    private fun findPublicToday(ctx: Context): Uri? {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?: return null
                val f = File(File(pub, PUBLIC_SUBDIR), FILE_PREFIX + todayStr() + ".txt")
                return if (f.exists()) Uri.fromFile(f) else null
            }
            val name = FILE_PREFIX + todayStr() + ".txt"
            val c = ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(name), null) ?: return null
            try {
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                }
            } finally {
                try { c.close() } catch (ignored: Throwable) { }
            }
        } catch (ignored: Throwable) { }
        return null
    }

    /**
     * 选一个能写的目录，三级退路。
     *
     * 【为什么不能只靠 File 直接写公共 Download】
     *   Android 10+ 分区存储禁止 App 直接 File 写公共目录。用户设备是
     *   Android 15，直接写必然失败 —— 之前那版会静默退到应用私有目录，
     *   用户按说的路径去找根本找不到。
     *   所以公共目录这条走 MediaStore（见 publicDownloadUri），
     *   这里只负责「探出私有退路」。
     *
     *  1) 公共 Download/githup/错误日志/ —— 走 MediaStore，用户最好找
     *  2) 应用外部目录 Android/data/<pkg>/files/错误日志/ —— 分区存储下一定可写
     *  3) 应用内部目录 files/错误日志/ —— 兜底，用户看不到但至少留住证据
     */
    private fun logDir(ctx: Context): File? {
        resolvedDir?.let { return it }

        val candidates = ArrayList<File>()
        try {
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) candidates.add(File(ext, "错误日志"))
        } catch (ignored: Throwable) { }
        try {
            candidates.add(File(ctx.filesDir, "错误日志"))
        } catch (ignored: Throwable) { }

        for (d in candidates) {
            try {
                if (!d.exists() && !d.mkdirs()) continue
                if (!d.isDirectory) continue
                // 真写一个探针文件确认 —— canWrite() 在分区存储下会骗人
                val probe = File(d, ".probe")
                var pout: FileOutputStream? = null
                try {
                    pout = FileOutputStream(probe, false)
                    pout.write('x'.code)
                    pout.flush()
                } finally {
                    close(pout)
                }
                probe.delete()
                resolvedDir = d
                return d
            } catch (ignored: Throwable) { }
        }
        return null
    }

    /** 当天的日志文件：错误日志-2026-10-01.txt */
    private fun todayFile(dir: File): File = File(dir, FILE_PREFIX + todayStr() + ".txt")

    /**
     * 一天写满上限后，另起一个带序号的文件继续写。
     * 返回「接下来该写哪个文件」—— 调用方必须接收返回值。
     */
    private fun rotateIfNeeded(f: File): File {
        try {
            if (!f.exists() || f.length() < MAX_BYTES) return f
            for (i in 2 until 100) {
                val alt = File(f.parentFile, FILE_PREFIX + todayStr() + "-$i.txt")
                if (!alt.exists()) {
                    var fos: FileOutputStream? = null
                    try { fos = FileOutputStream(alt, true) } finally { close(fos) }
                    return alt
                }
                if (alt.length() < MAX_BYTES) return alt
            }
        } catch (ignored: Throwable) { }
        return f
    }

    /** 删掉超过 KEEP_DAYS 天的日志文件 */
    private fun cleanOld(ctx: Context) {
        try {
            val dir = logDir(ctx) ?: return
            val all = dir.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600L * 1000L
            for (f in all) {
                try {
                    if (f.isFile && f.lastModified() < cutoff) f.delete()
                } catch (ignored: Throwable) { }
            }
        } catch (ignored: Throwable) { }
    }

    /* ==================================================================
     *  读取
     * ================================================================== */

    /**
     * 把今天的日志整个读出来；没有就返回空串。
     *
     * ⚠️ 只读 App 内部那本，**不读公共目录**。
     *
     * 以前优先读公共目录（当时以为那是"权威副本"）。现在日志平时根本
     * 不落公共目录，那里只可能有用户手动导出的 `错误日志导出-*.txt`，
     * 或者更老版本自动留下的 `错误日志-*.txt` —— 后者是**过去某天**的
     * 自动日志，读它会把陈年旧账混进今天的导出里。
     *
     * 所以这里只读内部小本子：导出的就是"今天记的"，干净。
     */
    @JvmStatic
    fun readToday(ctx: Context?): String {
        return try {
            val dir = logDir(ctx ?: return "") ?: return ""
            val sb = StringBuilder()
            for (f in todayFiles(dir)) {
                val s = readAll(f)
                if (s.isNotEmpty()) {
                    sb.append(s)
                    if (!s.endsWith("\n")) sb.append("\n")
                }
            }
            sb.toString()
        } catch (t: Throwable) {
            ""
        }
    }

    /** 今天所有日志文件（含超限续写的 -2、-3…），按序号从早到晚 */
    private fun todayFiles(dir: File): List<File> {
        val out = ArrayList<File>()
        try {
            val base = FILE_PREFIX + todayStr()
            val all = dir.listFiles()
            if (all != null) {
                for (f in all) {
                    val n = f.name
                    if (n == base + ".txt" || n.startsWith("$base-")) out.add(f)
                }
            }
            // 主文件（无序号）在最前，之后按 -2、-3… 排
            out.sortWith(Comparator { a, b -> Integer.compare(seqOf(a), seqOf(b)) })
        } catch (ignored: Throwable) { }
        return out
    }

    /** 从 错误日志-2026-10-01-2.txt 里取出序号 2；主文件算 1 */
    private fun seqOf(f: File): Int {
        try {
            var n = f.name
            val dot = n.lastIndexOf('.')
            if (dot > 0) n = n.substring(0, dot)
            val dash = n.lastIndexOf('-')
            if (dash > 10) {                       // 避开日期里的横线
                val tail = n.substring(dash + 1)
                if (tail.matches(Regex("\\d+"))) return tail.toInt()
            }
        } catch (ignored: Throwable) { }
        return 1
    }

    private fun readAll(f: File): String {
        var input: FileInputStream? = null
        return try {
            input = FileInputStream(f)
            val buf = ByteArray(8192)
            val sb = StringBuilder()
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                sb.append(String(buf, 0, r, Charsets.UTF_8))
            }
            sb.toString()
        } catch (t: Throwable) {
            ""
        } finally {
            close(input)
        }
    }

    /* ==================================================================
     *  导出（用户点按钮后，自己选「分享」还是「保存到本地」）
     * ================================================================== */

    /**
     * 把今天的日志拼成一份带抬头的可读文本。
     * 抬头补上「设备 / 版本 / 日期」—— 流水账只记「出了什么事」，
     * 发给别人时对方还需要知道「这是什么设备、什么版本」。
     */
    private fun buildExportText(ctx: Context): String {
        val today = readToday(ctx)
        val sb = StringBuilder()
        sb.append("githup 错误日志\n")
        sb.append("================\n")
        sb.append("这是今天（").append(todayStr()).append("）记下来的问题。\n")
        sb.append("只记今天，明天自动从头开始。\n\n")
        sb.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append("，Android ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("版本：").append(versionOf(ctx)).append("\n")
        sb.append("------------------------\n\n")

        if (today.trim().isEmpty()) {
            sb.append("今天没有记录到任何问题。\n")
        } else {
            sb.append(today)
            if (!today.endsWith("\n")) sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 「保存到本地」：把日志写成文件放进 Download/githup/错误日志/。
     * 写不进公共目录就退应用私有目录（至少把这份导出留住）。
     *
     * @return 成功落盘返回 true
     */
    @JvmStatic
    fun save(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            val content = buildExportText(ctx)
            if (writeExportToPublic(ctx, content)) return true

            val dir = logDir(ctx) ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val out = File(dir, "错误日志导出-" + todayStr() + ".txt")
            var fos: FileOutputStream? = null
            try {
                fos = FileOutputStream(out, false)
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
            } finally {
                close(fos)
            }
            cleanExports(dir)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 「分享」：准备一份可交给外部应用的副本，返回它的 content Uri。
     *
     * 为什么不直接把公共目录那份分享出去 ——
     *   Android 7 起 file:// 不能直接交给外部应用（FileUriExposedException），
     *   分享必须走 LogProvider（FileProvider），而它只暴露私有 exports/ 目录；
     *   另外 content uri 里带中文文件名，个别接收方处理不了，所以副本用纯
     *   ASCII 名 githup-errorlog-<日期>.txt。
     *
     * @return 分享用 Uri；写不出来返回 null。
     */
    @JvmStatic
    fun share(ctx: Context?): Uri? {
        if (ctx == null) return null
        return try {
            writeShareCopy(ctx, buildExportText(ctx))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 兼容旧调用：先保存、再返回分享 Uri（供「既存又分享」的场景）。
     */
    @JvmStatic
    fun export(ctx: Context?): Uri? {
        if (ctx == null) return null
        return try {
            save(ctx)
            share(ctx)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 把导出内容写进私有 exports/ 目录，返回 LogProvider 的分享 Uri。
     * 文件名用纯 ASCII：content uri 的 path 段里出现中文（未编码），
     * 微信、邮件这类接收方有的会直接拒收。
     */
    private fun writeShareCopy(ctx: Context, content: String): Uri? {
        var fos: FileOutputStream? = null
        return try {
            val dir = File(ctx.filesDir, "exports")
            if (!dir.exists() && !dir.mkdirs()) return null
            val name = "githup-errorlog-" + todayStr() + ".txt"
            val f = File(dir, name)
            fos = FileOutputStream(f, false)
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            LogProvider.uriFor(
                ctx.packageName + LogProvider.AUTHORITY_SUFFIX, name)
        } catch (t: Throwable) {
            null
        } finally {
            close(fos)
        }
    }

    /** 把导出文件写进公共目录（MediaStore） */
    private fun writeExportToPublic(ctx: Context, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 29) {
                val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?: return false
                val dir = File(pub, PUBLIC_SUBDIR)
                if (!dir.exists() && !dir.mkdirs()) return false
                val f = File(dir, "错误日志导出-" + todayStr() + ".txt")
                var fos: FileOutputStream? = null
                try {
                    fos = FileOutputStream(f, false)
                    fos.write(content.toByteArray(Charsets.UTF_8))
                    fos.flush()
                } finally {
                    close(fos)
                }
                return true
            }

            val cr = ctx.contentResolver ?: return false
            val parent = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val name = "错误日志导出-" + todayStr() + ".txt"

            /* 先删掉今天可能已存在的同名导出（重复点按钮时覆盖，
             * 而不是越攒越多）。 */
            try {
                cr.delete(parent,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(name))
            } catch (ignored: Throwable) { }

            val cv = ContentValues()
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_RELATIVE)
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1)

            val item = cr.insert(parent, cv) ?: return false

            var os: OutputStream? = null
            try {
                os = cr.openOutputStream(item, "wt") ?: return false
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } finally {
                close(os)
            }

            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            try { cr.update(item, done, null, null) } catch (ignored: Throwable) { }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 导出文件只留最近 3 份 */
    private fun cleanExports(dir: File) {
        try {
            val all = dir.listFiles() ?: return
            val exps = ArrayList<File>()
            for (f in all) {
                if (f.isFile && f.name.startsWith("错误日志导出-")) exps.add(f)
            }
            if (exps.size <= 3) return
            exps.sortWith(Comparator { a, b -> java.lang.Long.compare(b.lastModified(), a.lastModified()) })
            for (i in 3 until exps.size) {
                try { exps[i].delete() } catch (ignored: Throwable) { }
            }
        } catch (ignored: Throwable) { }
    }

    /* ==================================================================
     *  小工具
     * ================================================================== */

    private fun versionOf(ctx: Context): String {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            @Suppress("DEPRECATION")
            pi.versionName + " (" + pi.versionCode + ")"
        } catch (t: Throwable) {
            "?"
        }
    }

    private fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun stamp(): String =
        "[" + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "]"

    private fun nz(s: String?, def: String): String =
        if (s == null || s.isEmpty()) def else s

    private fun isBlank(s: String?): Boolean =
        s == null || s.trim().isEmpty()

    private fun close(c: Closeable?) {
        if (c == null) return
        try { c.close() } catch (ignored: Throwable) { }
    }
}
