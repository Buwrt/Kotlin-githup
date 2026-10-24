package com.hubmobile.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * README 图片的「原样转发」通道。
 *
 * 背景：以前 App 显示 README 图片走的是这么一条路 ——
 * 前端发现一个 <img>，把它的 https 地址交给原生；原生用 Http.requestB64
 * 把图片**读成字符串**（Base64，凭空胖 33%），再切成 48KB 一片塞进
 * evaluateJavascript 过 Binder 念给 JS；JS 拼回字符串、塞成 data: URI，
 * WebView 再从头解一遍码。
 *
 * 三次本不必要的开销：体积 +33%、两次编解码、以及 Binder 这一趟本身就慢。
 * 更要命的是它**丢掉了浏览器天生就有的一切**：HTTP 缓存用不上、
 * 渐进式渲染没有（必须整张到齐才出第一帧）、<img> 自己的并发调度也全废了。
 * 所以网页浏览器里「滑到就出图」，App 里要等好几秒。
 *
 * 这里做的正是在裁判位置上把球接住：WebView 要取这张图的时候
 * （shouldInterceptRequest），我们用已经跑通的原生网络栈去拿，拿到的**字节原样**
 * 交给 WebView 自己解码渲染 —— 不经过 String、不经过 Binder、不经过 JS。
 * 这一层再配一个磁盘缓存，第二次打开同一张图就是读本地文件，零网络。
 *
 * 失败一律返回 null = 「我没意见，你自己来」。WebView 会照原路重试。
 * 这个降级不是客套：它图的是「接住的时候就该够快」，不是「一定能成」——
 * 任何一处不确定都该把路让回去，而不是替用户拍板给一张裂图。
 *
 * 【Kotlin 迁移】
 *   这个类有实例状态（缓存目录、单线程池、锁表、令牌记忆），所以是 class
 *   不是 object；纯函数那部分留在 companion object 里。
 *   synchronized(lock) 用 Kotlin 的同名内联函数，语义与 Java 的
 *   synchronized 块一致（monitorenter/monitorexit）。
 */
class ImageProxy(ctx: Context, private val tokens: TokenProvider?) {

    /**
     * 给拦截器取令牌的接口。JsBridge 实现它，把登录态递进来。
     *
     * 声明成 fun interface（单一抽象方法）：Kotlin 这边可以直接写 lambda，
     * Java 那边照样能用匿名类或 lambda —— 编译出来仍是普通接口，
     * 两边的写法都不受影响。
     */
    fun interface TokenProvider {
        fun get(): String?
    }

    companion object {
        private const val TAG = "ImageProxy"

        /** 磁盘缓存的新鲜期。README 里的图几乎是写完就不再改的资源。 */
        private const val CACHE_TTL = 24L * 3600 * 1000
        private const val MAX_CACHE_BYTES = 150L * 1024 * 1024
        private const val MAX_CACHE_FILES = 800
        /** 单张图上限：超过就不要了（此时更应该给它一张裂图，而不是拖住整个队列） */
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

        /** 和浏览器默认完全一致的一行，省得 CDN 因为 Accept 不同错判格式 */
        private const val DEFAULT_ACCEPT =
            "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

        private const val PREFETCH_LIMIT = 8

        /**
         * 渲染完之后，先闷一会儿再开始预热。
         *
         * 刚渲染完那几百毫秒里，WebView 正在拉屏幕上的图 —— 这时候我们插进去
         * 下载，抢的是同一根管子。屏幕上那张本来几百毫秒就出来了，被自己人挤到
         * 几秒，用户看到的正是「怎么比 1.2.1 还慢」。等它走完再动，两边都不亏：
         * 首屏照旧是 WebView 的速度，等用户滑到第二屏，东西已经在磁盘上了。
         */
        private const val PREFETCH_DELAY_MS = 1200L

        /**
         * 预热单个文件的上限。
         *
         * 无扩展名的附件（github.com/user-attachments/assets/<uuid>）可能是
         * 几十 MB 的录屏。预热本意是「issue 里的截图滑到就有」，不是替用户把他
         * 没点开的视频也下下来 —— 所以这类地址先探一下大小，超了就不预热。
         */
        private const val PREFETCH_MAX_BYTES = 4 * 1024 * 1024

        /**
         * GitHub 网页端上传的附件：拖进 issue / PR 的图片、录屏都长这样，
         * 一律没有扩展名，从 URL 上看不出是图还是视频。
         *
         * 前端 md.js 里有一条一模一样的正则（ATTACH_RE）。两处必须同步 ——
         * 前端靠它决定渲染成 <video> 去试探，这里靠它决定要不要预热。
         */
        private val GITHUB_ATTACH: Pattern = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/user-attachments/[a-z]+/[0-9a-zA-Z_-]{6,}",
            Pattern.CASE_INSENSITIVE)

        /** 逐跳首部和对不上账的首部：原样透传会让 WebView 读不完这条响应 */
        private val DROP_HEADERS: List<String> = listOf(
            "content-encoding",     /* Http 层已经解开了，再写着 gzip 就是骗 WebView */
            "transfer-encoding",
            "content-length",       /* 下面按解压后的真实长度重算 */
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "trailer",
            "upgrade")

        /** 这张图归我管吗？请求头里写着呢：<img> 发的请求 Accept 必然含 image/。 */
        private fun looksLikeImage(url: String, reqHeaders: Map<String, String>?): Boolean {
            var accept = reqHeaders?.get("Accept")
            if (accept == null) accept = reqHeaders?.get("accept")
            if (accept != null && accept.lowercase(Locale.US).contains("image/")) return true
            // 没有 Accept（老版本 WebView 偶尔不给）就退而看扩展名，再不行就别管
            return isImageUrl(url)
        }

        /**
         * 这个地址值得预热吗？
         *
         * 比 isImageUrl 宽一类：无扩展名的 GitHub 附件也算。
         * issue / PR 里的截图几乎全是这种地址，而它们恰恰是最该预热的那一批 ——
         * 不认它们，就永远只有 README 的图享受得到「滑到就有」。
         *
         * 注意这里【只用于预热】。拦截（intercept）仍走 looksLikeImage，
         * 那条路要求 Accept 里带 image/ 字样，所以 <video> 的请求不会被我们接过去
         * —— 视频要靠 Range 请求做 seek，我们返回的是整包 200，接了反而会把播放弄坏。
         */
        private fun prefetchable(url: String): Boolean =
            isImageUrl(url) || GITHUB_ATTACH.matcher(url).find()

        /**
         * 先探个头，看看这个附件有多大。
         *
         * 说不出大小的一律当作「不预热」：预热失败最坏也就是「到时候现拉」，
         * 而误把一个几十 MB 的录屏拉下来，是实打实的流量和缓存开销。
         */
        private fun smallEnough(url: String): Boolean {
            return try {
                val r = Http.requestRaw("HEAD", url, null)
                if (r.code != 200) return false
                var cl: String? = null
                for (kv in r.headers) {
                    if (kv.size >= 2 && "content-length".equals(kv[0], ignoreCase = true)) {
                        cl = kv[1]
                    }
                }
                if (cl == null) return false
                val n = cl.trim().toLong()
                n > 0 && n <= PREFETCH_MAX_BYTES
            } catch (t: Throwable) {
                false
            }
        }

        private fun isImageUrl(url: String): Boolean {
            val u = url.split("\\?", limit = 2)[0].split("#", limit = 2)[0].lowercase(Locale.US)
            return u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg")
                    || u.endsWith(".gif") || u.endsWith(".webp") || u.endsWith(".bmp")
                    || u.endsWith(".svg") || u.endsWith(".avif") || u.endsWith(".ico")
        }

        /** 只有 GitHub 自己的域名配拿令牌 —— 同上：令牌不能外泄给第三方图床 */
        private fun githubHost(url: String): Boolean {
            return try {
                val host = URL(url).host.lowercase(Locale.US)
                host == "github.com"
                        || host.endsWith(".github.com")
                        || host == "githubusercontent.com"
                        || host.endsWith(".githubusercontent.com")
                        || host == "github.io"
                        || host.endsWith(".github.io")
            } catch (e: Exception) {
                false
            }
        }

        private fun pick(m: Map<String, String>?, key: String): String? {
            if (m == null) return null
            for (e in m.entries) {
                if (e.key.equals(key, ignoreCase = true)) return e.value
            }
            return null
        }

        private fun headersToMap(r: Http.RawResponse): Map<String, String> {
            val out = HashMap<String, String>()
            for (kv in r.headers) {
                if (kv.size < 2) continue
                val k = kv[0].lowercase(Locale.US)
                if (DROP_HEADERS.contains(k)) continue
                out[kv[0]] = kv[1]
            }
            return out
        }

        /** 扩展名 → MIME。URL 是比响应头更硬的事实：文件叫什么就是什么。 */
        private fun mimeByExt(url: String): String {
            val u = url.split("\\?", limit = 2)[0].lowercase(Locale.US)
            if (u.endsWith(".png")) return "image/png"
            if (u.endsWith(".jpg") || u.endsWith(".jpeg")) return "image/jpeg"
            if (u.endsWith(".gif")) return "image/gif"
            if (u.endsWith(".webp")) return "image/webp"
            if (u.endsWith(".svg")) return "image/svg+xml"
            if (u.endsWith(".bmp")) return "image/bmp"
            if (u.endsWith(".avif")) return "image/avif"
            if (u.endsWith(".ico")) return "image/x-icon"
            return "application/octet-stream"
        }

        /**
         * 把「WebView 拒绝当图渲染」的 MIME 纠正成扩展名给出的那个。
         *
         * Content-Type 是 image/ 开头的照单全收；text/plain、octet-stream、
         * 空值这三类对 <img> 来说都是死路 —— SVG 曾经踩过第一个坑
         * （部分 CDN 给 SVG 回 text/plain），读缓存丢过 .type 的图踩过第二个。
         */
        private fun saneMime(mime: String?, url: String): String? {
            if (mime == null || mime.isEmpty()
                || mime.startsWith("text/plain")
                || mime.startsWith("application/octet-stream")) {
                val byExt = mimeByExt(url)
                if ("application/octet-stream" != byExt) return byExt
            }
            return mime
        }

        private fun mimeOf(contentType: String?, url: String): String {
            if (contentType != null) {
                val ct = contentType.split(";", limit = 2)[0].trim()
                if (ct.isNotEmpty()) return ct
            }
            return mimeByExt(url)
        }

        /* ============================================================
         * 同一份附件有两条官方地址，缓存必须落在同一个文件上
         *
         *   1) 带签名的 CDN 地址：private-user-images.githubusercontent.com/…<uuid>.png?jwt=…
         *      —— 官网自己就是这么加载的，直出字节（不用跳）
         *   2) 不带签名的稳定地址：github.com/user-attachments/assets/<uuid>
         *      —— 这个会 302 到官方的 S3 才拿到字节（多一跳、还多一次握手）
         *
         * 以前缓存 key 就是整条 URL 的哈希。带签名的地址每次请求都不一样
         * （签名 5 分钟一换），于是「预热下好一份、加载时又下另一份」——
         * 预热等于白做，用户滑到的每一张都在现拉，这就是「议题里的图比官网慢」
         * 最直接的一条。现在按地址里那个 uuid 认人，两条路共用一份缓存：
         * 用快的那条下载，用稳的那条兜底，缓存在 App 里只有一份。
         * ============================================================ */
        private val ASSET_UUID: Pattern = Pattern.compile(
            "(?:user-attachments/assets/|githubusercontent\\.com/\\d+/\\d+-)"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})",
            Pattern.CASE_INSENSITIVE)

        private fun cacheKey(url: String?): String {
            if (url == null) return ""
            val m = ASSET_UUID.matcher(url)
            if (m.find()) return "asset-" + m.group(1).lowercase(Locale.US)
            return url
        }

        private fun collect(d: File, out: MutableList<File>) {
            val fs = d.listFiles() ?: return
            for (f in fs) {
                if (f.isDirectory) collect(f, out)
                else if (f.isFile) out.add(f)
            }
        }

        private fun readText(f: File): String? {
            return try {
                val n = f.length().toInt()
                if (n <= 0 || n > 4096) return null
                val b = ByteArray(n)
                val input: InputStream = FileInputStream(f)
                try {
                    var off = 0
                    while (off < n) {
                        val got = input.read(b, off, n - off)
                        if (got <= 0) break
                        off += got
                    }
                } finally {
                    input.close()
                }
                String(b, StandardCharsets.UTF_8).trim()
            } catch (t: Throwable) {
                null
            }
        }

        private fun writeText(f: File, s: String) {
            try {
                val os = FileOutputStream(f)
                try {
                    os.write(s.toByteArray(StandardCharsets.UTF_8))
                } finally {
                    os.close()
                }
            } catch (ignored: Throwable) {
            }
        }

        private fun sha1(s: String): String {
            return try {
                val md = MessageDigest.getInstance("SHA-1")
                val d = md.digest(s.toByteArray(StandardCharsets.UTF_8))
                val sb = StringBuilder(d.size * 2)
                for (b in d) sb.append(String.format("%02x", b))
                sb.toString()
            } catch (e: Exception) {
                Integer.toHexString(s.hashCode())
            }
        }

        /** 仓库粒度：/owner/repo 这两段。私有性是按仓库算的，按整条 URL 记等于没记。 */
        private fun authScope(url: String): String? {
            return try {
                val u = URL(url)
                var p = u.path ?: ""
                val head = StringBuilder()
                var seg = 0
                var i = 1
                while (i < p.length && seg < 2) {
                    var j = p.indexOf('/', i)
                    if (j < 0) j = p.length
                    head.append('/').append(p.substring(i, j))
                    i = j + 1
                    seg++
                }
                u.protocol + "://" + u.host.lowercase(Locale.US) + head
            } catch (e: Exception) {
                null
            }
        }
    }

    private class Hit {
        var data: ByteArray = ByteArray(0)
        var mime: String = ""
        var headers: MutableMap<String, String> = HashMap()
    }

    private val dir: File = File(ctx.cacheDir, "imgproxy").apply { mkdirs() }
    /**
     * 预热专用，**单线程**。
     *
     * 以前是 3 条。看着像「下载更快」，实际是**跟用户正在看的那张图抢带宽** ——
     * 三条一起灌，最吃亏的恰恰是屏幕上那一张：它本来几百毫秒就出来了，
     * 被自己人挤到好几秒。预热是锦上添花，不能拿首屏去换。
     * 一条一条来，慢是慢了，但谁都不碍着。
     */
    private val pool: ExecutorService = Executors.newFixedThreadPool(1)
    private val locks = ConcurrentHashMap<String, Any>()
    /** 只为了「写满 N 次才巡一遍目录」，别让每张图都付一次 listing 的钱 */
    private val writesSinceTrim = AtomicInteger()
    /** 「这个地址得带令牌」的记忆：内存一份（快），磁盘一份（下次启动还记得） */
    private val authMemo = ConcurrentHashMap<String, Boolean>()
    private val authPrefs: SharedPreferences? =
        ctx.getSharedPreferences("imgproxy_auth", Context.MODE_PRIVATE)

    /* ============================================================
     * 拦截：只接得住的才接
     *
     * 1.2.9 刚加这一层时的逻辑是「WebView 要图，我们全接过来自己下」。
     * 事后看，这是我们做过最亏的一次替换：
     *
     *   · Chromium 自己的网络栈（HTTP/2 多路复用、TLS 会话复用、它自己的
     *     HTTP 缓存与 304、边下边渲染）比我们这个基于 Socket 的 HTTP/1.1
     *     客户端快。而一旦被我们接住，Chromium 就不再发它自己那趟请求 ——
     *     等于把「快车道」换成了「慢车道」。用户讲的「没有 1.2.1 快」，
     *     根子就在这一条：1.2.9 之前图一直是 WebView 自己拉的。
     *   · 我们是整包下完才给字节，渐进式渲染没了 —— 大图上「先出个模糊的、
     *     再变清楚」的那一段被吃掉，观感上就是干等。
     *   · 这个回调跑在 WebView 有限的几个 IO 线程上，在这里同步阻塞下载，
     *     一张大图就占住一个线程，后面排队的图跟着一起等。
     *
     * 所以现在只做一件**一定更快**的事：磁盘上有，立刻给（零网络）。
     * 磁盘上没有 —— 放手交给 WebView 自己拉，它那套本来就比我们快。
     *
     * 唯一的例外是「匿名拿不到、必须带令牌」的资源（私有仓库的 raw 图）：
     * WebView 手上没有令牌，那种只能我们来，慢一点也比裂图强。
     * 哪些需要令牌是**学着来的**（见 needsAuth）：第一次匿名失败、
     * 补令牌成功之后记下来，以后首趟就带，连那次 401 往返都省了。
     *
     * 于是缓存由谁填？由预热（prefetch）。它本来就是「趁用户还没
     * 滑到，先下好」，而且现在会等首屏那几张走完再动（见那边的注释）。
     * ============================================================ */
    fun intercept(req: WebResourceRequest?): WebResourceResponse? {
        if (req == null) return null
        if (req.isForMainFrame) return null
        if (!"GET".equals(req.method, ignoreCase = true)) return null

        val url = req.url?.toString() ?: return null
        if (!url.startsWith("https://")) return null
        if (!looksLikeImage(url, req.requestHeaders)) return null

        var log = url
        if (log.length > 96) log = log.substring(0, 96) + "…"
        val t0 = SystemClock.elapsedRealtime()

        try {
            val f = fileFor(url)
            var hit = readCache(f)
            if (hit != null) {
                Log.d(TAG, "cache " + (SystemClock.elapsedRealtime() - t0)
                        + "ms " + (hit.data.size / 1024) + "KB " + log)
                /* WebView 对 <img> 的 Content-Type 挑剔得很：text/plain、
                 * octet-stream 一律拒绝渲染成图片，哪怕字节是张完好无损的图。
                 * CDN 会犯糊涂（raw 域名历史上就把 SVG 标成 text/plain），
                 * 磁盘缓存丢了 .type 侧标也会落成 octet-stream。在这里按
                 * URL 扩展名最后把关一次。 */
                hit.mime = saneMime(hit.mime, url) ?: ""
                return wrap(hit)
            }

            /* 磁盘上没有，而这张图匿名就能拿到 —— 交回给 WebView。
             * 不是撒手不管：预热那边多半已经在下它了，下好落盘，
             * 下次（以及用户滑到它之前的那一刻）就是读本地文件。 */
            if (!takeOver(url)) {
                Log.d(TAG, "放行（磁盘上没有，交给 WebView 自己拉）：" + log)
                return null
            }

            /* 同一个 URL 串行：README 里三处引用同一张图时，WebView 会几乎同时
             * 来问三次。第一个去下载，后两个在锁上等它落盘，然后直接读现成的 ——
             * 省下的不是几百毫秒，是两张图的流量。 */
            val lock = lockFor(url)
            synchronized(lock) {
                hit = readCache(f)
                if (hit == null) {
                    hit = download(url, req.requestHeaders)
                    if (hit != null) writeCache(f, hit!!.data, hit!!.mime)
                }
                if (hit == null) {
                    Log.w(TAG, "没接住（退回 WebView 自取）：" + log)
                    return null
                }
                Log.d(TAG, "auth " + (SystemClock.elapsedRealtime() - t0)
                        + "ms " + (hit!!.data.size / 1024) + "KB " + log)
                hit!!.mime = saneMime(hit!!.mime, url) ?: ""
                return wrap(hit!!)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "代理抛异常，退回 WebView：" + log, t)
            return null
        }
    }

    /**
     * 这张图归我们管吗？
     *
     * 只有两种：磁盘上已经有了（零网络，稳赢），或者它非带令牌不可
     * （WebView 手上没令牌，只能我们来）。
     * 其余一律放行 —— 让 Chromium 自己拉，它那套比我们这段 Socket 代码快。
     *
     * 这里只看文件在不在，不真去读：读一次就是整张图进内存，
     * 而这条判断每张图每次都要走一遍。
     */
    private fun takeOver(url: String): Boolean {
        try {
            if (fileFor(url).exists()) return true
        } catch (ignored: Throwable) {
        }
        return needsAuth(url)
    }

    /* ============================================================
     * 「这个地址得带令牌才拿得到」—— 学着来的
     *
     * 私有仓库的 raw 图、私有 issue 的附件，匿名请求一律 404。
     * 反过来，带令牌的请求共享缓存一律不收（CDN 边缘那个几十毫秒就没了），
     * 所以也不能图省事全部带上 —— 第一次匿名、撞墙再补，是两边都不亏的做法。
     *
     * 但「撞墙再补」每次都要多付一趟 401 往返。这里把结论按仓库粒度
     * （scheme://host/owner/repo）记下来，下次首趟就带令牌。
     * 记的是**匿名失败且补令牌成功**的那些 —— 只有这种才算学会了。
     * ============================================================ */
    private fun needsAuth(url: String): Boolean {
        val k = authScope(url) ?: return false
        val v = authMemo[k]
        if (v != null) return v
        val b = authPrefs != null && authPrefs.getBoolean(k, false)
        authMemo[k] = b
        return b
    }

    private fun rememberAuth(url: String) {
        val k = authScope(url) ?: return
        authMemo[k] = true
        if (authPrefs != null) {
            try {
                authPrefs.edit().putBoolean(k, true).apply()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 预热：README 刚渲染完就把前几张图的地址丢过来后台下载。
     *
     * 有了懒加载之后，图片是等到滑到眼前才发请求的 —— 对用户来说
     * 那就是「每次滑到这儿都要等一下」。提前把前几张灌进缓存，滑到时
     * 是读本地文件，才是网页那个「一瞬间」的手感。
     *
     * 只做前几张不是偷懒：一份 README 可能有几十张图，全预热等于替用户
     * 把整份文档连同他根本不会滑到的部分一起买单。
     *
     * 还有两道「让路」的闸，都是被实测教训出来的：
     *
     *   · **延后 PREFETCH_DELAY_MS 毫秒再动。** 渲染刚完那一刻，
     *     WebView 正在拉屏幕上那几张图；这时候插进去下载，抢的是同一根管子，
     *     结果「预热」把首屏拖慢了 —— 用户等的就是屏幕上这一张。
     *   · **一条一条下**（pool 是单线程），不再三条并进。
     *
     * 视口里那几张前端已经剔掉了（见 md.js 的 belowFold）：它们此刻
     * 正由 WebView 自己拉，再下一遍就是同一份字节买两次单。
     */
    fun prefetch(urls: List<String?>?) {
        if (urls == null || urls.isEmpty()) return
        val todo = ArrayList<String>()
        for (u in urls) {
            if (u == null || !u.startsWith("https://")) continue
            if (todo.size >= PREFETCH_LIMIT) break
            if (!prefetchable(u)) continue
            todo.add(u)
        }
        if (todo.isEmpty()) return
        pool.execute {
            /* 先让首屏那趟走完。睡在后台线程上，不碰 UI，也不占拦截的线程。 */
            try {
                Thread.sleep(PREFETCH_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            } catch (ignored: Throwable) {
            }
            for (u in todo) {
                try {
                    val f = fileFor(u)
                    val lock = lockFor(u)
                    synchronized(lock) {
                        if (readCache(f) == null) {
                            /* 无扩展名的附件先探个头再决定下不下 ——
                             * 它很可能是录屏，整包拉下来既费流量又占缓存，
                             * 而用户根本没打算点开它。截图一般几百 KB，
                             * 这道闸拦掉的正是那些几十 MB 的。 */
                            /* 注意：这里是 if/else 而不是 continue。
                             * synchronized 是 inline 函数，在它的 lambda 里
                             * continue 属于尚未正式稳定的特性，需要额外的
                             * 实验性开关才编译得过；改成 if/else 行为完全一致，
                             * 也省掉一个编译选项。 */
                            if (!isImageUrl(u) && !smallEnough(u)) {
                                Log.d(TAG, "预热跳过（太大或探不到大小）" + u)
                            } else {
                                val hit = download(u, null)
                                if (hit != null) {
                                    writeCache(f, hit.data, hit.mime)
                                    Log.d(TAG, "预热 " + (hit.data.size / 1024) + "KB " + u)
                                }
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                    /* 预热不该以任何方式被人看见：失败就是「到时候现拉」而已 */
                }
            }
        }
    }

    private fun download(url: String, reqHeaders: Map<String, String>?): Hit? {
        val h = HashMap<String, String>()
        val accept = pick(reqHeaders, "Accept")
        val ua = pick(reqHeaders, "User-Agent")
        h["Accept"] = accept ?: DEFAULT_ACCEPT
        if (ua != null) h["User-Agent"] = ua

        /* 已经学过「这个仓库得带令牌」的话，首趟就带上 ——
         * 省掉的那趟 401 往返，在慢网下是实打实的一两百毫秒。 */
        val auth = needsAuth(url)
        if (auth) {
            val t0 = tokens?.get()
            if (!t0.isNullOrEmpty() && githubHost(url)) {
                h["Authorization"] = "Bearer $t0"
            }
        }

        var r = Http.requestRaw("GET", url, h)

        /* 私有仓库的 raw 地址、以及 issue 里的私有附件，匿名拉是 404。
         *
         * 为什么不在第一趟就把令牌带上：带 Authorization 的请求共享缓存一律不收，
         * 于是 public repo 的图会次次回源 —— CDN 边缘 HIT 那个「几十毫秒」就没了。
         * 匿名优先、撞墙再补令牌，两边都不亏；撞过一次就记下来（rememberAuth），
         * 下回连这趟 401 都省了。 */
        if (r.code == 401 || r.code == 403 || r.code == 404) {
            val t = tokens?.get()
            if (!t.isNullOrEmpty() && githubHost(url)) {
                h["Authorization"] = "Bearer $t"
                val r2 = Http.requestRaw("GET", url, h)
                if (r2.code == 200 && r2.body != null && r2.body!!.isNotEmpty()) {
                    rememberAuth(url)      /* 学会了：这个仓库匿名不行 */
                    r = r2
                }
            }
        }
        if (r.code != 200 || r.body == null || r.body!!.isEmpty()) return null
        if (r.body!!.size > MAX_IMAGE_BYTES) return null

        val hit = Hit()
        hit.data = r.body!!
        val ct = r.header("Content-Type")
        /* 入缓存前就纠正：.type 侧标里存着 text/plain 的话，
         * 24 小时内每一次读缓存都得再纠一遍。 */
        hit.mime = saneMime(mimeOf(ct, url), url) ?: ""
        for (kv in headersToMap(r).entries) {
            hit.headers[kv.key] = kv.value
        }
        // Content-Type 必须留下：日后这张图从磁盘缓存里出来时，
        // 要靠它告诉 WebView 这是张什么格式的图（文件格式本身 md5 文件名是没有的）
        if (ct != null && ct.isNotEmpty()) hit.headers["Content-Type"] = ct
        return hit
    }

    private fun wrap(hit: Hit): WebResourceResponse {
        hit.headers["Content-Length"] = hit.data.size.toString()
        return WebResourceResponse(
            hit.mime, null, 200, "OK", hit.headers,
            ByteArrayInputStream(hit.data))
    }

    /* ============================================================
     * 磁盘缓存
     * ============================================================ */

    private fun fileFor(url: String): File {
        val name = sha1(cacheKey(url))
        // 两级子目录：一个目录底下堆几千个文件，某些文件系统 Listing 会明显慢下来
        return File(File(dir, name.substring(0, 2)), name)
    }

    private fun readCache(f: File): Hit? {
        try {
            if (!f.exists() || !f.isFile) return null
            if (System.currentTimeMillis() - f.lastModified() > CACHE_TTL) {
                f.delete()
                return null
            }
            val n = f.length().toInt()
            if (n <= 0 || n > MAX_IMAGE_BYTES) return null
            val buf = ByteArray(n)
            val input: InputStream = FileInputStream(f)
            try {
                var off = 0
                while (off < n) {
                    val got = input.read(buf, off, n - off)
                    if (got <= 0) break
                    off += got
                }
                if (off < n) return null
            } finally {
                input.close()
            }
            /* 命中一次就刷一次时间，坐实 trim 里那个 LRU 的说法。
             * 少了这一行，清理时看到的就只是「下载时间」，于是天天在用的图
             * 也可能被当成最旧的删掉。 */
            f.setLastModified(System.currentTimeMillis())
            val hit = Hit()
            hit.data = buf
            hit.mime = "application/octet-stream"   // 没有 .type 就让 WebView 自己嗅探
            val t = File(f.parentFile, f.name + ".type")
            if (t.exists()) {
                val s = readText(t)
                if (!s.isNullOrEmpty()) hit.mime = s
            }
            hit.headers["Content-Type"] = hit.mime
            return hit
        } catch (t: Throwable) {
            return null
        }
    }

    private fun writeCache(f: File, data: ByteArray, mime: String) {
        try {
            val parent = f.parentFile
            if (parent != null) {
                parent.mkdirs()
            }
            val tmp = File(parent, f.name + ".tmp")
            val os: OutputStream = FileOutputStream(tmp)
            try {
                os.write(data)
                os.flush()
            } finally {
                os.close()
            }
            tmp.renameTo(f)        /* 原子：写到一半被杀不会留下半个文件 */
            val t = File(parent, f.name + ".type")
            writeText(t, mime)
            if (writesSinceTrim.incrementAndGet() >= 20) {
                writesSinceTrim.set(0)
                trim()
            }
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 超量清理：按「最后用过的时间」从旧到新删一半。
     *
     * 为什么不看最后的访问时间：文件系统普遍不开 atime（开了就是每个读
     * 多一次写，拿 Cz 兑换，不划算）。命中一次缓存就把 lastModified 刷成现在，
     * 于是这个时间实际是「最后一次被取用的时间」而不是下载时间 —— LRU 该看的
     * 本来就是它。
     */
    private fun trim() {
        try {
            val all = ArrayList<File>()
            collect(dir, all)
            var total = 0L
            for (f in all) total += f.length()
            if (all.size <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) return
            all.sortWith(Comparator { a, b -> java.lang.Long.compare(a.lastModified(), b.lastModified()) })
            var count = all.size
            for (f in all) {
                if (count <= MAX_CACHE_FILES && total <= MAX_CACHE_BYTES) break
                val len = f.length()
                if (f.delete()) {
                    count--
                    total -= len
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun lockFor(url: String): Any {
        // 锁也按 uuid 走：两条地址是同一份字节，别一个在下载、另一个同时也在下
        val key = cacheKey(url).hashCode().toString()
        val o = locks[key]
        if (o != null) return o
        val fresh = Any()
        val prev = locks.putIfAbsent(key, fresh)
        return prev ?: fresh
    }
}
