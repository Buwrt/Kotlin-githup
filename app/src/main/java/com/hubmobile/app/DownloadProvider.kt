package com.hubmobile.app

import android.app.DownloadManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 给外部应用（系统安装器、文件查看器）以及本应用自己，提供已下载文件的读权限。
 *
 * 为什么需要：Android 7.0 起禁止把 file:// 直接抛给外部应用，必须走 content://。
 * 下载的 APK 躺在 Download/githup/ 里 —— 之前直接拿 DownloadManager 给的
 * file:// 去拉安装器，新系统上会被静默拦掉，表现就是「下载完成了却装不了」。
 *
 * 与 ApkProvider 同样的思路：不引 androidx 的 FileProvider，几十行自足。
 * 只暴露下载目录里的文件，路径只取 URI 最后一段且禁止分隔符与「..」，
 * 不存在路径穿越风险。
 *
 * ============================================================
 * 2026-09-19 重要修复
 * ============================================================
 * 以前这里**自己拼路径**找文件：
 *
 *     Environment.getExternalStoragePublicDirectory(DOWNLOADS) + "/githup/" + name
 *
 * 这条路径在 Android 10 之前没问题，因为那时 App 有公共目录的直读权限。
 * 但从 Android 10（API 29）引入**分区存储**之后，App 用这条路径**看不到**
 * DownloadManager 写进去的文件 —— 目录还叫那个名字，看到的却是应用沙箱里的
 * 影子目录，里面是空的。
 *
 * 后果是连锁的，而且全都指向同一句报错：
 *
 *   下载完成 → installFinished() → verifySha() 读文件
 *     → openFile() → !f.exists() → throw FileNotFoundException
 *     → 用户看到「已阻止安装：校验失败：FileNotFoundException」
 *
 * **文件其实完好无损**（用户看到的 743.4 KB 就是完整包），只是读它的路走错了。
 * 「下载管理」里点打开、删除，走的也是同一个错路径，所以同样失灵。
 *
 * 现在改成向 DownloadManager 要文件位置 —— 它是下载的发起方，
 * DownloadManager.getUriForDownloadedFile(long) 拿到的地址
 * 在任何 Android 版本上都是权威、可读的。给不出时才退回老的公共目录路径，
 * 让 Android 9 及以下（以及外部直接传文件名进来的调用方）继续能用。
 *
 * 【Kotlin 迁移】
 *   ContentProvider 必须 public 且带无参构造（系统在 manifest 里按类名反射
 *   实例化），所以这里是 class 而不是 object；静态那一半搬进 companion object，
 *   常量用 const val、方法加 @JvmStatic，Java 侧的调用写法一字不改。
 */
class DownloadProvider : ContentProvider() {

    companion object {
        /** 与 AndroidManifest 中 authorities 的后缀保持一致 */
        const val AUTHORITY_SUFFIX = ".downloads"

        @JvmStatic
        fun uriFor(pkg: String, fileName: String): Uri = uriFor(pkg, fileName, "")

        /**
         * @param sub Download/githup/ 下的子目录（如 "release"）；空串 = 根目录。
         *
         * 它作为 query 参数带在 URI 上，这样既不影响 path 段（path 不能含 "/"，
         * 否则会被当成路径穿越拦掉），又能把子目录信息传给 openFile()。
         */
        @JvmStatic
        fun uriFor(pkg: String, fileName: String, sub: String?): Uri {
            val base = "content://" + pkg + AUTHORITY_SUFFIX + "/" + Uri.encode(fileName)
            if (sub == null || sub.isEmpty()) return Uri.parse(base)
            return Uri.parse(base + "?sub=" + Uri.encode(sub))
        }

        /**
         * 文件在公共下载目录里的**名义**位置。
         *
         * 只作为兜底使用：Android 10+ 分区存储下 App 直接访问这个路径拿不到文件，
         * 优先用 resolve(Context, String)。
         */
        @JvmStatic
        fun fileFor(fileName: String): File = fileFor(fileName, "")

        /** 带子目录的名义路径，如 Download/githup/release/xxx.apk */
        @JvmStatic
        fun fileFor(fileName: String, subDir: String?): File {
            val root = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                JsBridge.DOWNLOAD_SUBDIR)
            val sub = subDir ?: ""
            val dir = if (sub.isEmpty()) root else File(root, sub)
            return File(dir, fileName)
        }

        /**
         * 找名字叫 fileName 的已下载文件，返回真实可读的位置。
         *
         * 顺序：
         *   1) DownloadManager 的记录（权威，Android 10+ 唯一可靠的来源）
         *   2) 公共下载目录下的名义路径（Android 9 及以下兜底）
         *
         * @return 找到返回文件对象；彻底找不到返回 null。**不保证返回的文件此刻存在** ——
         *         调用方要么再判一次 exists()，要么直接用返回的地址去读并处理异常。
         */
        @JvmStatic
        fun resolve(ctx: Context?, fileName: String?): File? = resolve(ctx, fileName, "")

        /**
         * 找名字叫 fileName 的已下载文件，返回真实可读的位置。
         *
         * @param subDir Download/githup/ 下的子目录（如 "release"、"议题"）；空串 = 根目录
         *
         * ⚠️ 为什么要这个参数：文件实际按来源分目录存放
         * （Download/githup/release/xxx.apk）。以前只按名字拼
         * `Download/githup/xxx.apk`，子目录里的文件**永远定位不到** ——
         * 用户点「打开」就弹「文件不在了（可能已被删除）」，可文件明明就在那儿。
         */
        @JvmStatic
        fun resolve(ctx: Context?, fileName: String?, subDir: String?): File? {
            if (fileName == null || fileName.isEmpty()) return null
            if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return null
            var sub = subDir ?: ""
            if (sub.contains("/") || sub.contains("\\") || sub.contains("..")) sub = ""

            val fromDm = fromDownloadManager(ctx, fileName)
            if (fromDm != null) return fromDm

            val cands = if (sub.isEmpty())
                arrayOf(fileFor(fileName, ""))
            else
                arrayOf(fileFor(fileName, sub), fileFor(fileName, ""))
            for (f in cands) {
                if (f.exists()) return f
            }
            return null
        }

        /**
         * 从 DownloadManager 的历史记录里按文件名找最近一次下载的落盘位置。
         *
         * 为什么按名字而不是按 id：这个 Provider 是被系统安装器以外部的身份调用的，
         * 拿到的只有 URI 里的文件名（`content://…/githup-1.1.3.apk`），
         * 没有下载 id。按标题/文件名反查是这里唯一能走通的路。
         */
        private fun fromDownloadManager(ctx: Context?, fileName: String): File? {
            if (ctx == null) return null
            var c: Cursor? = null
            return try {
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                    ?: return null
                /*
                  ⚠️ 不能写 setFilterByStatus(A | B | C | D)。

                  AOSP 里这个方法是**等值比较**（WHERE status = ?），
                  而 STATUS_* 不是位标志，是普通枚举 1/2/4/8。位或出来是 15，
                  SQL 变成 `WHERE status = 15` —— 真实状态只会是 1/2/4/8/16 之一，
                  **永远命中 0 条**。

                  所以这条路以前一直是死的。不设过滤即可（总量很小）。
                */
                c = dm.query(DownloadManager.Query()) ?: return null

                val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
                if (iTitle < 0) return null

                var fallback: File? = null
                while (c.moveToNext()) {
                    val title = c.getString(iTitle)
                    if (title == null || title != fileName) continue

                    /* 用 getUriForDownloadedFile 而不是去解 COLUMN_LOCAL_URI 字符串：
                     * 后者在 Android 10+ 可能给的是 content:// 形式的 MediaStore 地址，
                     * 各 ROM 写法还不一致；前者是官方 API，返回 file:// 或 content://
                     * 都能被 ContentResolver 正确打开。 */
                    val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                    if (iId >= 0) {
                        val id = c.getLong(iId)
                        var u: Uri? = null
                        try { u = dm.getUriForDownloadedFile(id) } catch (ignored: Throwable) { }
                        val f = fileOf(ctx, u)
                        if (f != null) return f
                    }

                    if (iLocal >= 0) {
                        val local = c.getString(iLocal)
                        if (!local.isNullOrEmpty()) {
                            val f = fileOf(ctx, Uri.parse(local))
                            /* 同一文件名可能有多次下载记录：先记住一个存在的，
                             * 但继续往后找更新的记录，找不到更好的就用它。 */
                            if (f != null) { if (fallback == null) fallback = f }
                        }
                    }
                }
                fallback
            } catch (ignored: Throwable) {
                null
            } finally {
                c?.close()
            }
        }

        /** 把一个 file:// 或 content:// 地址还原成 File；还原不出或文件不存在则 null */
        private fun fileOf(ctx: Context, u: Uri?): File? {
            if (u == null) return null
            return try {
                if ("file".equals(u.scheme, ignoreCase = true)) {
                    val f = File(u.path ?: "")
                    if (f.exists() && f.isFile) f else null
                } else {
                    /* content:// 的写法各 ROM 不同，直接拿路径可能不是文件系统路径。
                     * 不强行还原，交给调用方用 ContentResolver 打开 —— 这里只为
                     * openFile() 服务，它有另外一条处理分支。 */
                    null
                }
            } catch (ignored: Throwable) {
                null
            }
        }

        /** 按扩展名猜 MIME，给「打开文件」用 */
        @JvmStatic
        fun mimeFor(name: String?): String {
            if (name == null) return "application/octet-stream"
            val n = name.lowercase()
            if (n.endsWith(".apk")) return "application/vnd.android.package-archive"
            if (n.endsWith(".zip")) return "application/zip"
            if (n.endsWith(".pdf")) return "application/pdf"
            if (n.endsWith(".png")) return "image/png"
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg"
            if (n.endsWith(".gif")) return "image/gif"
            if (n.endsWith(".webp")) return "image/webp"
            if (n.endsWith(".svg")) return "image/svg+xml"
            if (n.endsWith(".mp3")) return "audio/mpeg"
            if (n.endsWith(".ogg")) return "audio/ogg"
            if (n.endsWith(".wav")) return "audio/wav"
            if (n.endsWith(".mp4")) return "video/mp4"
            if (n.endsWith(".webm")) return "video/webm"
            if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".md")) return "text/plain"
            if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html"
            if (n.endsWith(".json")) return "application/json"
            if (n.endsWith(".xml")) return "text/xml"
            if (n.endsWith(".csv")) return "text/csv"
            if (n.endsWith(".tgz") || n.endsWith(".gz")) return "application/gzip"
            if (n.endsWith(".tar")) return "application/x-tar"
            if (n.endsWith(".jar")) return "application/java-archive"
            return "application/octet-stream"
        }
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw FileNotFoundException("bad name")
        }
        var sub = uri.getQueryParameter("sub")
        if (sub == null) sub = ""
        if (name.contains("..")) throw FileNotFoundException("bad name")

        /* 第一优先：问 DownloadManager 要这个文件的读句柄。
         * 它在任何 Android 版本上都给得出可读的地址，也绕开了分区存储。 */
        val pfd = openViaDownloadManager(name)
        if (pfd != null) return pfd

        /*
          兜底：公共下载目录下的名义路径（Android 9 及以下，或外部传文件名进来）。

          sub 要带上：文件按来源分目录存放（Download/githup/release/…），
          只按名字去根目录找会 FileNotFoundException —— 用户看到的就是
          「已阻止安装：校验失败：FileNotFoundException」，可文件完好无损。
        */
        var f = resolve(context, name, sub)
        if ((f == null || !f.exists() || !f.isFile) && sub.isNotEmpty()) {
            f = resolve(context, name, "")      // 再退回根目录试一次
        }
        if (f == null || !f.exists() || !f.isFile) {
            throw FileNotFoundException("找不到已下载的文件：$name")
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** 通过 DownloadManager 打开指定文件名的已下载文件，拿不到返回 null */
    private fun openViaDownloadManager(fileName: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        var c: Cursor? = null
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return null
            c = dm.query(DownloadManager.Query()) ?: return null
            val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            if (iTitle < 0 || iId < 0) return null

            while (c.moveToNext()) {
                val title = c.getString(iTitle)
                if (title == null || title != fileName) continue
                val id = c.getLong(iId)
                /* 官方 API：拿到的是「这个下载任务产出的文件」的地址，
                 * 分区存储与否都不影响可读性。 */
                try {
                    val u = dm.getUriForDownloadedFile(id)
                    if (u != null) {
                        val p = ctx.contentResolver.openFileDescriptor(u, "r")
                        if (p != null) return p
                    }
                } catch (ignored: Throwable) { }
                /* 官方 API 在某些 ROM 上返回 null，退回 openDownloadedFile */
                try {
                    return dm.openDownloadedFile(id)
                } catch (ignored: Throwable) { }
            }
        } catch (ignored: Throwable) {
        } finally {
            c?.close()
        }
        return null
    }

    override fun getType(uri: Uri): String? = mimeFor(uri.lastPathSegment)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        /* 返回 _data 列（文件绝对路径）：签名校验等逻辑拿到 content:// 后
         * 会按惯例查这一列来还原真实路径。之前这里返回 null，导致
         * 「无法验证安装包签名」的误报 —— 包明明是官方签的却被拦下。
         *
         * 注意：Android 10 起 _data 这一列对调用方的可见性变差，
         * 所以它只能算「尽力而为」的一路，真正的读取走 openFile()。 */
        val name = uri.lastPathSegment
        if (name == null || name.isEmpty()
            || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null
        }
        val f = resolve(context, name) ?: return null
        val c = MatrixCursor(arrayOf("_data", "_size", "_display_name"), 1)
        c.addRow(arrayOf(f.absolutePath, f.length(), name))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
