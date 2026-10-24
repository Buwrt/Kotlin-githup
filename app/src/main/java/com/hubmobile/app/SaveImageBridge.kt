package com.hubmobile.app

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * 保存图片到系统相册。
 *
 * 跟 download() 的区别：
 *   - download 走 DownloadManager，落到 Download/githup/，是「文件」
 *   - saveImage 走 MediaStore.Images，落到 Pictures/githup/，是「照片」，
 *     系统相册、微信、QQ 选图时都能直接看到
 *
 * 触发场景：议题正文里的截图、赞赏码、查看大图时点「保存」。
 * 长按弹菜单由前端负责，这里只做「拿到 URL → 落盘到相册」。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class SaveImageBridge(private val ctx: BridgeContext) {

    @JavascriptInterface
    fun saveImage(url: String?) {
        if (url.isNullOrEmpty()) {
            ctx.toast("图片地址为空")
            return
        }
        ctx.background { doSave(url) }
    }

    private fun doSave(url: String) {
        try {
            /* 1) 拿到图片字节。
             *    分两种来源：
             *      a) 本地 assets 资源（赞赏码 tips.png 这类）—— 直接读 assets
             *      b) 网络图片（GitHub 附件、仓库图）—— 走 Http，带 token */
            var resp: Http.RawResponse? = null
            val isData = url.startsWith("data:")
            var assetPath: String? = null
            val data: ByteArray = if (isData) {
                /*
                  ═══ data: URI —— 字节就在串里，不必再发网络 ═══

                  README 里的图片为了让 raw.githubusercontent.com 这类
                  直连不通的地址也能出图，走了一条「原生取图」快车道：
                  拉回 base64 塞成 data: URI 再赋给 img 的 src（见
                  md.js 的 pumpFetch，以及 ImageProxy 的转发通道）。
                  **于是 img.src 可能整串都是图片数据，而不是一个网址。**

                  浏览器当然能渲染它 —— 也正是它能「下载图片」的原因之一
                  （浏览器存的是已解码的那份，根本不用再按 URL 取一遍）。
                  而以前没有这个分支：data: 掉进下面的网络分支，
                  建连直接抛 MalformedURLException，报错写着
                  「保存失败：no protocol: data:…」，看不懂。
                */
                decodeDataUri(url) ?: ByteArray(0)
            } else {
                assetPath = assetPathOf(url)
                if (assetPath != null) {
                    /* 本地 assets：file:///android_asset/web/img/tips.png
                     * 或相对路径 img/tips.png，都映射到 assets/web/... */
                    ctx.activity.assets.open(assetPath).use { input ->
                        val baos = ByteArrayOutputStream()
                        val buf = ByteArray(16384)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) baos.write(buf, 0, n)
                        baos.toByteArray()
                    }
                } else {
                    /*
                      ═══ 建连前先验一下地址 ═══

                      报错长这样：
                          failed to connect to localhost/127.0.0.1 (port 443)

                      443 这个数字是关键：Http 里端口是这么算的
                          secure = "https" 协议
                          port   = 显式端口 ?: (secure ? 443 : 80)
                      能算出 443 说明 **协议是 https，而 host 是空的**
                      (空 host 经 InetAddress 解析成回环地址，
                      于是报错里写着 localhost/127.0.0.1)。

                      浏览器遇到这种地址是**认得出无效、直接失败**的，
                      绝不会去连本机；我们以前照建连不误。这里挡一道，
                      和浏览器对齐：解析不出主机就不连，并给一句人话。
                    */
                    if (!Http.hostUsable(url)) {
                        ctx.toast("保存失败：图片地址不完整（" + briefUrl(url) + "）")
                        return
                    }
                    val headers = HashMap<String, String>()
                    val token = SecurePrefs.get(ctx.activity, JsBridge.TOKEN_KEY, "")
                    if (token.isNotEmpty()) {
                        headers["Authorization"] = "Bearer $token"
                    }
                    headers["Accept"] = "image/*,*/*;q=0.8"
                    /*
                      ═══ 直连取不到，就换加速通道 ═══

                      用户导出的日志给出真相：地址完全正常 ——
                          https://raw.githubusercontent.com/Buwrt/githup/main/docs/tips.png
                      却连到了 localhost/127.0.0.1。

                      那串不是我们写死的地址，而是**解析结果**：
                      InetAddress.toString() 是「主机名/IP」，127.0.0.1 的
                      反查名恰好是 localhost。所以是这台手机上
                      raw.githubusercontent.com 被解析到了本机 ——
                      hosts 被改（各类 GitHub 加速/hosts 修改器都会写 127.0.0.1）
                      或 VPN 的 DNS 劫持。

                      同一个域名，加载却正常：图片是 **WebView 渲染的**，
                      Chromium 自带解析器、不读系统 hosts；保存走原生
                      InetAddress、会读。**两边问的不是同一个地方。**

                      跟 DNS 较劲不如换个域名：本 App 本来就有十几条
                      加速通道（下载 Release 一直在用），把取图也接上去。
                      直连失败时依次换通道重试 —— 换的是主机，
                      本机那条 hosts 记录就管不着了。
                    */
                    resp = fetchImage(url, headers)
                    if (resp == null || resp.code < 200 || resp.code >= 300 || resp.body == null) {
                        ctx.toast(
                            "保存失败：图片下载不到" + if (resp == null) "（直连与加速通道都连不上）"
                            else "（HTTP " + resp.code + "）"
                        )
                        return
                    }
                    resp.body ?: ByteArray(0)
                }
            }
            if (isData && data.isEmpty()) {
                ctx.toast("保存失败：图片数据读不出来")
                return
            }
            if (data.isEmpty()) {
                ctx.toast("保存失败：图片是空的")
                return
            }

            /* 2) 推断 MIME 类型与扩展名。
             *    网络图片优先看响应头 Content-Type，本地 assets 没有响应头，
             *    从 URL 后缀猜，再不行按文件头魔数判（PNG/JPEG/GIF/WebP）。 */
            var mime: String? = null
            /*
              data: URI 自己就带着类型（形如 data:image/png;base64,…），
              用它的 —— 而且**绝不能去读 resp**：data 分支下 resp 是 null，
              读它会 NPE 被下面的 catch 悄悄吞掉，mime 恒为 null，
              最后只能靠魔数猜，SVG 这类没有魔数的就全存成了 png。
            */
            if (isData) {
                mime = mimeOfDataUri(url)
            } else if (assetPath == null) {
                try {
                    mime = resp?.header("Content-Type")
                } catch (ignored: Throwable) {
                }
                if (mime != null) {
                    val sc = mime.indexOf(';')
                    if (sc > 0) mime = mime.substring(0, sc).trim()
                }
            }
            var ext = extFromMime(mime)
            if (ext == null) ext = extFromUrl(url)
            if (ext == null) ext = extFromMagic(data)
            if (ext == null) ext = "png"
            if (mime == null) mime = mimeFromExt(ext)

            /* 3) 用时间戳生成文件名，避免重名覆盖。 */
            val name = "githup_" + System.currentTimeMillis() + "." + ext

            /* 4) 通过 MediaStore.Images 写进 Pictures/githup/。 */
            val cv = ContentValues()
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            cv.put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/githup"
                )
                cv.put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val item = ctx.activity.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            if (item == null) {
                ctx.toast("保存失败：无法创建相册条目")
                return
            }
            val out = ctx.activity.contentResolver.openOutputStream(item)
            if (out == null) {
                ctx.activity.contentResolver.delete(item, null, null)
                ctx.toast("保存失败：无法写入相册")
                return
            }
            out.use {
                it.write(data)
                it.flush()
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues()
                done.put(MediaStore.Images.Media.IS_PENDING, 0)
                ctx.activity.contentResolver.update(item, done, null, null)
            } else {
                try {
                    @Suppress("DEPRECATION")
                    ctx.activity.sendBroadcast(
                        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, item)
                    )
                } catch (ignored: Throwable) {
                }
            }

            ctx.toast("已保存到相册")
        } catch (t: Throwable) {
            /*
              记一笔：把**真实地址**和异常类型写进错误日志。

              连续几轮都在猜这个地址长什么样（data:? 相对路径?
              空主机? loopback?），每次都猜错。与其继续猜，不如让
              App 自己说出来 —— 去「关于 → 下载错误日志」导一份，
              里面就有这里记的原文，一眼能定位。
            */
            try {
                LogBook.error(
                    ctx.activity, "保存图片失败",
                    briefUrl(url) + " | " + t.javaClass.simpleName
                            + ": " + (t.message ?: "")
                )
            } catch (ignored: Throwable) {
            }
            ctx.toast("保存失败：" + (t.message ?: "未知错误"))
        }
    }

    /**
     * 把前端传的 URL 转成 assets 内的相对路径。
     * 支持：file:///android_asset/web/img/foo.png、img/foo.png、./img/foo.png
     * 不是本地 assets 资源时返回 null。
     */
    private fun assetPathOf(url: String): String? {
        var s = url
        val prefix = "file:///android_asset/"
        s = if (s.startsWith(prefix)) {
            s.substring(prefix.length)
        } else if (s.startsWith("http://") || s.startsWith("https://")
            || s.startsWith("data:")
        ) {
            return null
        } else {
            /* 相对路径：前端的图片都在 assets/web/ 下 */
            if (s.startsWith("./")) s = s.substring(2)
            if (s.startsWith("/")) s = s.substring(1)
            "web/$s"
        }
        return s
    }

    /**
     * 把可能很长的地址截成能进提示与日志的一小段。
     * data: URI 动辄几十上百 KB，整条塞进提示框会把屏幕撑爆。
     */
    private fun briefUrl(url: String): String {
        val s = url
        return if (s.length <= 80) s else s.substring(0, 80) + "…（共 " + s.length + " 字符）"
    }

    /**
     * data: URI 自带的 MIME，如 `data:image/png;base64,xxx` → `image/png`。
     *
     * data: 分支下没有 HTTP 响应头可用，只能从这里取；取不到就退给
     * 文件头魔数兜底。
     */
    private fun mimeOfDataUri(u: String): String? {
        return try {
            if (!u.startsWith("data:")) return null
            val comma = u.indexOf(',')
            if (comma <= 0) return null
            val head = u.substring(5, comma)
            val sc = head.indexOf(';')
            val mime = (if (sc >= 0) head.substring(0, sc) else head).trim()
            if (mime.isEmpty()) null else mime
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 把 data: URI 还原成字节。
     *
     * 字节本来就在字符串里 —— 这一步**不走网络**，所以「浏览器能存、
     * 我们存不了」的那批快车道图片，靠它就能存下来。
     *
     * @return 解码后的字节；失败返回 null
     */
    private fun decodeDataUri(u: String): ByteArray? {
        return try {
            if (!u.startsWith("data:")) return null
            val comma = u.indexOf(',')
            if (comma < 0) return null
            val head = u.substring(5, comma)
            val payload = u.substring(comma + 1)
            val b64 = head.toLowerCase(Locale.US).indexOf("base64") >= 0
            if (b64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                /* 非 base64 的 data: 是百分号编码的文本（SVG 常见） */
                URLDecoder.decode(payload, "UTF-8").toByteArray(charset("UTF-8"))
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    /**
     * 取图片字节：先直连，失败就换加速通道。
     *
     * **为什么不再跟 DNS 较劲**：域名在本机被解析成 127.0.0.1 时，
     * 直连必然失败（Http 里已加 DoH 兜底，但那要求 DoH 端点本身可达，
     * 在 hosts 被改得比较狠的机器上未必成立）。换通道是**换主机**，
     * 本机那条 hosts 记录就管不着了 —— 比修 DNS 可靠得多。
     *
     * **安全**：走镜像时**不带 Authorization**。GitHub token 只能发给
     * GitHub，发给第三方镜像等于把令牌交出去。图片是公开资源，不需要令牌。
     *
     * @return 成功返回响应；全部失败返回直连的那份（好让错误码有意义），
     *         连直连都抛异常时返回 null
     */
    private fun fetchImage(url: String, headers: Map<String, String>): Http.RawResponse? {
        var direct: Http.RawResponse? = null
        try {
            val r = Http.requestRaw("GET", url, headers)
            if (ok200(r)) return r
            direct = r                       // 留着，全失败时报它的码
        } catch (ignored: Throwable) {
            /* 直连抛异常（连不上/被解析到本机），下面换通道 */
        }

        val n = minOf(DownloadChannels.MIRRORS.size, DownloadChannels.VERIFIED_COUNT)
        for (i in 0 until n) {
            val mirror = DownloadChannels.MIRRORS[i]
            if (mirror.isEmpty()) continue
            /* 云速是模板型拼法，不能「前缀 + 原URL」；拼不出来（不是 raw 类
               地址）就跳过这条 —— 图片几乎都是 raw 地址，通常是能拼出来的。 */
            val murl = DownloadChannels.buildUrl(mirror, url) ?: continue
            try {
                val h = HashMap<String, String>()
                h["Accept"] = "image/*,*/*;q=0.8"     /* 不带令牌 */
                val r = Http.requestRaw("GET", murl, h)
                if (ok200(r)) return r
            } catch (ignored: Throwable) {
            }
        }
        return direct
    }

    private fun ok200(r: Http.RawResponse?): Boolean {
        val body = r?.body
        return r != null && r.code >= 200 && r.code < 300 && body != null && body.isNotEmpty()
    }

    private fun extFromMime(mime: String?): String? {
        if (mime == null) return null
        val m = mime.toLowerCase(Locale.US)
        if (m.contains("png")) return "png"
        if (m.contains("jpeg") || m.contains("jpg")) return "jpg"
        if (m.contains("gif")) return "gif"
        if (m.contains("webp")) return "webp"
        if (m.contains("bmp")) return "bmp"
        return null
    }

    private fun extFromUrl(url: String): String? {
        val q = url.indexOf('?')
        val path = if (q > 0) url.substring(0, q) else url
        val dot = path.lastIndexOf('.')
        if (dot < 0 || dot < path.lastIndexOf('/')) return null
        val e = path.substring(dot + 1).toLowerCase(Locale.US)
        if (e.length > 5) return null
        if (e == "jpeg") return "jpg"
        if (e == "png" || e == "jpg" || e == "gif" || e == "webp" || e == "bmp") return e
        return null
    }

    /** 按文件头魔数判类型：PNG / JPEG / GIF / WebP */
    private fun extFromMagic(data: ByteArray): String? {
        if (data.size < 4) return null
        if ((data[0].toInt() and 0xFF) == 0x89 && (data[1].toInt() and 0xFF) == 0x50
            && (data[2].toInt() and 0xFF) == 0x4E && (data[3].toInt() and 0xFF) == 0x47
        ) return "png"
        if ((data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
            && (data[2].toInt() and 0xFF) == 0xFF
        ) return "jpg"
        if ((data[0].toInt() and 0xFF) == 0x47 && (data[1].toInt() and 0xFF) == 0x49
            && (data[2].toInt() and 0xFF) == 0x46 && (data[3].toInt() and 0xFF) == 0x38
        ) return "gif"
        if (data.size >= 12 && (data[0].toInt() and 0xFF) == 0x52
            && (data[1].toInt() and 0xFF) == 0x49
            && (data[2].toInt() and 0xFF) == 0x46 && (data[3].toInt() and 0xFF) == 0x46
            && (data[8].toInt() and 0xFF) == 0x57 && (data[9].toInt() and 0xFF) == 0x45
            && (data[10].toInt() and 0xFF) == 0x42 && (data[11].toInt() and 0xFF) == 0x50
        ) return "webp"
        return null
    }

    private fun mimeFromExt(ext: String): String {
        return when (ext) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }
}
