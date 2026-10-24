package com.hubmobile.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 轻量 HTTP/HTTPS 客户端。
 * 直接基于 Socket 实现，因而支持 PATCH 等在 HttpURLConnection 中受限的方法，
 * 并能完整读取响应头（分页 Link 头）。
 *
 * 【Kotlin 迁移】
 *   两处必须显式处理，否则 Java 调用方会编译不过或行为变掉：
 *
 *   1. 每个抛 IOException 的对外方法都带 @Throws(IOException::class)。
 *      Kotlin 不声明受检异常，少了它 Java 里的
 *      `try { ... } catch (IOException e)` 会被 javac 判为
 *      「异常在 try 块里根本不可能抛出」而报错。
 *
 *   2. Response / RawResponse 的字段带 @JvmField。
 *      Java 那边是 `r.code`、`r.body` 直接读写字段的，不加会变成
 *      getCode()/setCode()，写法全得改。
 */
object Http {

    private const val TAG = "HubHttp"

    /** 上传时的读写缓冲：64KB。
     *  以前是 8KB —— TLS 记录上限 16KB，8KB 的写只填得满半条，
     *  系统调用次数还多一个数量级。64KB 一次凑够多条满额记录。 */
    const val UPLOAD_BUF = 64 * 1024

    /** 内核发送缓冲区：256KB。
     *  默认通常只有几十 KB，在移动网络（RTT 高）下会成为带宽瓶颈。 */
    const val SEND_BUF = 256 * 1024
    /* 连接超时 12s：太长的话，连不上的目标（被墙的域名）会把工作线程
     * 白白占住 20s —— 线程池就那么大，页面自己的请求全在后面排队。 */
    private const val CONNECT_TIMEOUT = 12000
    private const val READ_TIMEOUT = 30000
    /* 内部标记头：调用方用它声明「这条请求重复发送也无副作用」，本层据此
     * 才敢对 POST 复用长连接并在连接已死时重试。永远不会写入报文。
     * 只有幂等只读的请求该带（翻译类接口）；写操作（建 Issue、传 Release 附件）
     * 不带，保持一次成型、绝不重试的语义。 */
    private const val MARKER_REUSE = "X-Hub-Reuse"

    /* 搜索接口要多给点时间。
     * GitHub 的搜索接口（/search/ 开头的那些）是「先算再答」，q githubup 这种没加限定符的词，
     * 服务端要多花好几秒；用 30s 之外的更短超时只会让请求白跑一趟还得重试。 */
    private const val SEARCH_READ_TIMEOUT = 60000

    private const val MAX_REDIRECT = 5

    /** Base64 响应上限：图片类资源足够，防一张超大文件把内存吃光 */
    private const val MAX_B64_BYTES = 15 * 1024 * 1024

    /* ---------- 连接复用 ----------
     *
     * 以前每个请求都是 new Socket + 完整 TLS 握手 + Connection: close。
     * 一次 TLS 握手在移动网络下要 300~800ms，而列表页动不动就并发 3~5 个请求，
     * 于是每个请求都白白多付一次握手的钱 —— 这就是「比之前慢」的主要来源之一。
     *
     * 现在按 host 缓存空闲连接并复用（HTTP/1.1 的 keep-alive 语义），
     * 只有真的复用了才继续保活；服务端要关就让它关，下次重建即可。
     * 连接池上限 6 条，空闲超过 60 秒就丢掉，避免一直占着 NAT 表项。
     */
    private val POOL: MutableMap<String, ArrayList<Pooled>> = HashMap()
    /* 每个 host 留 8 条空闲连接 —— 以前是 6。
     * 有道翻译在 JS 侧就是 6 并发（见 translate.js 的 YOUDAO_PAR_MAX），
     * 6 条刚好卡在边上是会出事的：只要同一时刻再多出一个请求（「翻译中」
     * 顺带刷一下未读数，或者拆分 103 时多出来那半个包的重发），池子就不够分，
     * 那个请求只能退回去重新握手 —— 于是每个大批量翻译里总有几个包
     * 要多付一次完整的 TLS 握手，正好卡在最能感知的位置。
     * 给到 8（= JsBridge 线程池的大小）留两条富余，多这两条空载也几乎不占资源。 */
    private const val POOL_MAX_PER_HOST = 8
    private const val POOL_IDLE_MS = 60000L

    private class Pooled(val socket: Socket) {
        val idleSince: Long = System.currentTimeMillis()
    }

    /*
      三个池操作在 Java 里都是 `static synchronized`（锁 Http.class）。
      Kotlin object 里 @Synchronized 锁的是 Http.INSTANCE —— 锁对象变了，
      但三者共用同一把锁，彼此之间仍然严格互斥，POOL 不会被并发改坏。
    */
    @Synchronized
    private fun takePooled(key: String): Socket? {
        val list = POOL[key] ?: return null
        val now = System.currentTimeMillis()
        while (!list.isEmpty()) {
            // 从最近的开始取：刚还回去的连接最可能还活着
            val p = list.removeAt(list.size - 1)
            if (now - p.idleSince > POOL_IDLE_MS) {
                closeQuietly(p.socket)
                continue
            }
            if (p.socket.isClosed || !p.socket.isConnected) {
                closeQuietly(p.socket)
                continue
            }
            return p.socket
        }
        return null
    }

    @Synchronized
    private fun putPooled(key: String, socket: Socket?) {
        if (socket == null || socket.isClosed) return
        var list = POOL[key]
        if (list == null) {
            list = ArrayList()
            POOL[key] = list
        }
        if (list.size >= POOL_MAX_PER_HOST) {
            closeQuietly(list.removeAt(0).socket)
        }
        list.add(Pooled(socket))
    }

    @Synchronized
    private fun dropPooled(key: String, socket: Socket) {
        val list = POOL[key] ?: return
        for (i in list.size - 1 downTo 0) {
            if (list[i].socket === socket) list.removeAt(i)
        }
    }

    private fun closeQuietly(s: Socket?) {
        if (s == null) return
        try { s.close() } catch (ignored: Exception) { }
    }

    /** /search/ 之外的接口用默认读超时 */
    private fun readTimeoutFor(urlStr: String?): Int =
        if (urlStr != null && urlStr.contains("/search/")) SEARCH_READ_TIMEOUT else READ_TIMEOUT

    class Response {
        @JvmField var code: Int = 0
        @JvmField var body: String = ""
        @JvmField var headers: String = "{}"
    }

    /** 原始字节响应，用于把二进制资源（图片等）直接交给 WebView，不走序列化。 */
    class RawResponse {
        @JvmField var code: Int = 0
        @JvmField var body: ByteArray? = null
        @JvmField var headers: MutableList<Array<String>> = ArrayList()

        fun header(name: String): String? {
            for (kv in headers) {
                if (kv[0].equals(name, ignoreCase = true)) return kv[1]
            }
            return null
        }
    }

    private class Raw {
        var code: Int = 0
        var reason: String = ""
        var headers: MutableList<Array<String>> = ArrayList()
        var body: ByteArray? = null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun request(method: String, urlStr: String, body: String?,
                headers: Map<String, String>?): Response =
        requestBytes(method, urlStr,
            if (body == null) null else body.toByteArray(StandardCharsets.UTF_8), headers)

    /** 支持二进制请求体的版本（用于上传 Release 附件等）。 */
    @JvmStatic
    @Throws(IOException::class)
    fun requestBytes(method: String, urlStr: String, body: ByteArray?,
                     headers: Map<String, String>?): Response {
        var methodU = method.uppercase(Locale.US)
        var current: String = urlStr
        for (i in 0..MAX_REDIRECT) {
            val raw = execBytes(methodU, current, body, headers)
            val loc = header(raw, "Location")
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.isNotEmpty()) {
                if (raw.code == 303) methodU = "GET"
                current = URL(URL(current), loc).toString()
                continue
            }
            val r = Response()
            r.code = raw.code
            r.body = if (raw.body == null) "" else String(raw.body!!, StandardCharsets.UTF_8)
            r.headers = headersJson(raw)
            return r
        }
        throw IOException("重定向次数过多")
    }

    /**
     * 二进制**响应**版：body 以 Base64 返回。
     *
     * 给 README 图片这类资源走原生通道用 —— WebView 直连
     * raw.githubusercontent.com 在不少网络下不通，而原生栈是通的。
     * 响应体按字节读进来再编码，绝不能过一遍 String（UTF-8 解码会把
     * 二进制搅碎），所以不能复用 requestBytes。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestB64(method: String, urlStr: String, body: ByteArray?,
                   headers: Map<String, String>?): Response {
        var methodU = method.uppercase(Locale.US)
        var current: String = urlStr
        for (i in 0..MAX_REDIRECT) {
            val raw = execBytes(methodU, current, body, headers)
            val loc = header(raw, "Location")
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.isNotEmpty()) {
                if (raw.code == 303) methodU = "GET"
                current = URL(URL(current), loc).toString()
                continue
            }
            if (raw.body != null && raw.body!!.size > MAX_B64_BYTES)
                throw IOException("文件太大（超过 " + (MAX_B64_BYTES / 1024 / 1024) + "MB）")
            val r = Response()
            r.code = raw.code
            r.body = if (raw.body == null) ""
            else android.util.Base64.encodeToString(raw.body, android.util.Base64.NO_WRAP)
            r.headers = headersJson(raw)
            return r
        }
        throw IOException("重定向次数过多")
    }

    /**
     * 原始字节版响应：不做任何编码，body 就是服务端给的那几个字节。
     *
     * 给 WebView 的 shouldInterceptRequest 用 —— 那边拿到字节后要塞进
     * WebResourceResponse 让 WebView 自己解码显示。走 requestB64 的话，
     * 字节要先胖一圈（+33%），再过一遍 Binder 传给 JS，JS 拼成 data URI 后
     * WebView 还得再解一遍 —— 同样的图多付三次代价，还丢掉了渐进式渲染。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestRaw(method: String, urlStr: String, headers: Map<String, String>?): RawResponse {
        var methodU = method.uppercase(Locale.US)
        var current: String = urlStr
        for (i in 0..MAX_REDIRECT) {
            /*
              ═══ 每一轮都要重新校验目标主机 ═══

              以前只在调用方（saveImage 的 hasHost）校验**第一个** URL，
              重定向之后就不管了。而图片恰恰是最爱重定向的 ——
              GitHub 的图基本都是 302 跳到 CDN。

              实测（真实 java.net.URL），base = https://github.com/a/b.png：

                  Location: https:///y.png  →  https:/y.png        host=""  port=443
                  Location: ///z.png        →  https:/z.png        host=""  port=443

              空 host 经 InetAddress.getByName("") 解析成回环地址，于是报错写成
              `failed to connect to localhost/127.0.0.1 (port 443)` ——
              **第一跳明明是合法地址，错却出在跳完之后**，所以在入口怎么查都查不到。

              这里每一轮（含第一跳）都验一次，跳到无效地址就直接停，
              不再去连本机。
            */
            if (!hostUsable(current)) {
                throw IOException("目标地址无效，已放弃：$current")
            }
            val raw = execBytes(methodU, current, null, headers)
            val loc = header(raw, "Location")
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.isNotEmpty()) {
                if (raw.code == 303) methodU = "GET"
                current = URL(URL(current), loc).toString()
                continue
            }
            val r = RawResponse()
            r.code = raw.code
            r.body = raw.body
            r.headers = raw.headers
            return r
        }
        throw IOException("重定向次数过多")
    }

    /**
     * 取 Actions 运行日志包（zip）。
     *
     * 和 requestRaw 唯一的差别在**跳到签名地址之后要把凭据摘掉**。
     *
     * 为什么必须单独写一个：
     * `GET /repos/{o}/{r}/actions/runs/{id}/logs` 不是直接吐 zip，
     * 而是 302 跳到一段**带签名的对象存储地址**（凭据写在 URL 的查询参数里）。
     * 若照 requestRaw 那样每一跳都原样带上 `Authorization`，签名地址会收到
     * 一个它不认识的 Bearer —— 实测直接 400/403，日志就永远取不回来。
     *
     * 所以这里第一跳带令牌、拿到 Location 之后的每一跳都不带。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestLogs(urlStr: String, headers: Map<String, String>?): RawResponse {
        var current: String = urlStr
        var h = headers
        var jumped = false
        for (i in 0..MAX_REDIRECT) {
            if (!hostUsable(current)) {
                throw IOException("目标地址无效，已放弃：$current")
            }
            val raw = execBytes("GET", current, null, h)
            val loc = header(raw, "Location")
            if (raw.code >= 300 && raw.code < 400 && loc != null && loc.isNotEmpty()) {
                val next = URL(URL(current), loc).toString()
                /* 只在「离开 api.github.com」的那一跳摘令牌。
                 * 按主机名判断而不是「第一次跳转就摘」：GitHub 若将来在自家
                 * 域名内多跳一次，按次数摘会过早丢掉凭据，反而取不到日志；
                 * 而只要目标还是自家域名，带令牌就是安全的。 */
                if (!jumped && h != null && h.containsKey("Authorization")
                    && !sameHost(urlStr, next)) {
                    h = HashMap(h)
                    h.remove("Authorization")
                    jumped = true
                }
                current = next
                continue
            }
            val r = RawResponse()
            r.code = raw.code
            r.body = raw.body
            r.headers = raw.headers
            return r
        }
        throw IOException("重定向次数过多")
    }

    /** 两个 URL 的主机是否相同（用于判断跳转让没离开 GitHub）。 */
    private fun sameHost(a: String?, b: String): Boolean {
        return try {
            val ha = URL(a).host
            val hb = URL(b).host
            ha != null && ha.equals(hb, ignoreCase = true)
        } catch (ignored: Throwable) {
            false
        }
    }

    /**
     * 这个地址值得去连吗？
     *
     * 两条判据，缺一不可：
     *
     * **1. 主机不能为空。** `https:///x` 这类「有协议没主机」的残缺串，
     * 空 host 会被解析成回环地址 —— 也就是那句
     * `failed to connect to localhost/127.0.0.1`。
     *
     * **2. 主机不能是本机。** 光判非空还不够：`localhost`、`127.0.0.1`、
     * `0.0.0.0`、`[::1]` 的 getHost() 全都非空，能顺利通过第 1 条，
     * 然后照样连到 127.0.0.1。
     * 而 `InetAddress.getByName("localhost").toString()` 恰好就是
     * `localhost/127.0.0.1` —— 和报错里那串一字不差。
     *
     * 所以两条合起来才是「值得连」。data: / assets / 相对路径不走这里。
     */
    @JvmStatic
    fun hostUsable(url: String?): Boolean {
        if (url == null) return false
        val host: String?
        try {
            host = URL(url).host
        } catch (ignored: Throwable) {
            return false                      // 连解析都失败，更不该去连
        }
        if (host == null || host.isEmpty()) return false
        val h = host.lowercase(Locale.US)
        if (h == "localhost" || h == "0.0.0.0" || h == "[::1]" || h == "::1") return false
        if (h.startsWith("127.")) return false
        return true
    }

    /** 响应头打包成小写 key 的 JSON，供桥接层透传给前端 */
    private fun headersJson(raw: Raw): String {
        val jo = JSONObject()
        for (kv in raw.headers) {
            /* 这里不需要判空：headers 里的每一项都由
             * `h.substring(0, idx).trim()` 得来（idx > 0），
             * Kotlin 的 Array<String> 又保证了元素非空。 */
            try {
                jo.put(kv[0].lowercase(Locale.US), kv[1])
            } catch (ignored: Exception) {
            }
        }
        return jo.toString()
    }

    @Throws(IOException::class)
    private fun execBytes(method: String, urlStr: String, body: ByteArray?,
                          headers: Map<String, String>?): Raw {
        val u = URL(urlStr)
        val secure = "https".equals(u.protocol, ignoreCase = true)
        val port = if (u.port > 0) u.port else (if (secure) 443 else 80)
        val host = u.host
        var path = if (u.path == null || u.path.isEmpty()) "/" else u.path
        if (u.query != null) path += "?" + u.query

        val isGet = "GET".equals(method, ignoreCase = true)
        /* ================= POST 也要能复用长连接 =================
         *
         * 以前这里写的是 `isGet && body == null` —— 于是**只有 GET 能进池子**。
         * 而翻译恰恰是 POST（有道 aidemo.youdao.com/trans、DeepL jsonrpc），
         * 也就是说这套连接池对翻译完全没生效：每一个译文请求照旧是
         * new Socket + 完整 TLS 握手 + Connection: close。
         *
         * 这是可有可无的小优化吗？看同一条链路的实测：
         *     DNS 0.5ms | TCP 0.6ms | **TLS 握手 70~74ms** | 总耗时 ~134ms
         * 也就是服务端真正处理只要约 60ms，**一半以上的时间花在握手上**；
         * 而这还是机房网络。到了手机上，一次 TLS 握手轻轻松松 300~800ms，
         * 握手的占比会涨到八成以上 —— 翻译慢的真身在这里，不在引擎。
         *
         * 为什么原来不敢：注释里写着「POST/PATCH 的失败重试语义不一样」。
         * 顾虑是对的、但解法错了 —— 不该一刀切关掉复用，而该让调用方表态。
         * 拿一条池里的连接出来写请求时，它可能已经被服务端悄悄关掉了；
         * 这时发现连接已死就必须换新的重发一次，而 POST 重发意味着服务端
         * 可能处理过同一个请求两次。所以 POST 能不能复用，取决于这个请求
         * 幂不幂等，而这件事只有调用方知道。
         *
         * 于是加一个内部标记头 X-Hub-Reuse：谁发的时候带上它，谁就是在说
         * 「这条请求重复一次无副作用」。翻译天然幂等（多译一遍同样的句子而已），
         * 所以 translate.js 里所有请求都会带。GitHub 的写操作（建 Issue、
         * 传附件）不带，保持原来的 Connection: close 语义，一次也不会重试。
         * 注意这个头**只在本层读取、不会被写进报文**（下面 emit 时装作看不见）。
         */
        val allowReuse = headers != null && headers.containsKey(MARKER_REUSE)
        val reusable = allowReuse || (isGet && body == null)
        val poolKey = "$secure|$host|$port"
        val readTimeout = readTimeoutFor(urlStr)

        var socket: Socket? = if (reusable) takePooled(poolKey) else null
        val fresh = (socket == null)
        if (fresh) socket = connect(host, port, secure, readTimeout)
        val sock = socket!!
        // 复用来的连接同样要设超时，别沿用上一次的
        try { sock.soTimeout = readTimeout } catch (ignored: Exception) { }

        var selfMade = true
        try {
            val req = StringBuilder()
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            req.append("Host: ").append(host)
            if (port != (if (secure) 443 else 80)) req.append(':').append(port)
            req.append("\r\n")
            // 复用连接时不能再说 close，否则服务端用完就关，下次还得重新握手
            req.append(if (reusable) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            /* 接受 gzip。
             *
             * 以前这里写死 identity（不压缩），一个 50 条仓库的搜索响应
             * 能到 200KB+，在移动网络下光传输就要好几秒；
             * gzip 之后通常只剩 15~25%，解码开销远小于省下的传输时间。
             * 下面的解压分支本来就有，之前形同虚设。 */
            req.append("Accept-Encoding: gzip\r\n")
            req.append("User-Agent: ").append(
                if (headers != null && headers.containsKey("User-Agent"))
                    headers["User-Agent"] else "HubMobile/1.0").append("\r\n")
            if (headers != null) {
                for (e in headers.entries) {
                    if ("User-Agent".equals(e.key, ignoreCase = true)) continue
                    // 编码由本层统一决定，避免调用方传进来的值把上面的 gzip 覆盖掉
                    if ("Accept-Encoding".equals(e.key, ignoreCase = true)) continue
                    if ("Connection".equals(e.key, ignoreCase = true)) continue
                    /* 内部标记，不上线路：它只是调用方给本层的「可以重试」许可，
                     * 真发出去没有任何服务器认识它，反而可能触发 CORS 预检。 */
                    if (MARKER_REUSE.equals(e.key, ignoreCase = true)) continue
                    req.append(e.key).append(": ").append(e.value).append("\r\n")
                }
            }
            var bodyBytes: ByteArray? = null
            if (body != null && body.isNotEmpty()) {
                bodyBytes = body
                req.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            }
            req.append("\r\n")

            val os: OutputStream = sock.getOutputStream()
            try {
                os.write(req.toString().toByteArray(StandardCharsets.US_ASCII))
                if (bodyBytes != null) os.write(bodyBytes)
                os.flush()
            } catch (e: IOException) {
                /* 写就失败了 = 这条连接已经死了（服务端空闲超时关掉了，
                 * 但本地还没感知到）。这时请求**确定没有被服务端执行**
                 * —— 要么一个字节都没发出去，要么发的是残缺的请求、
                 * 服务端解析不了会直接丢掉。所以换一条新连接重发是安全的，
                 * 哪怕这是个 POST（能走到这里的前提本来就是调用方给了许可）。
                 *
                 * 没有这段的话：池子里一旦有条僵尸连接，下一个请求必失败；
                 * 而它的代价不是「慢一点」，是整批译文里这一块永远翻不出来。 */
                dropPooled(poolKey, sock)
                closeQuietly(sock)
                selfMade = false
                if (!fresh) {
                    // 池里捞出来的才是这种模式；本来就全新的话错误是真的，得往外抛
                    return execBytes(method, urlStr, body, headers)
                }
                throw e
            }

            /* 包一层 BufferedInputStream。
             *
             * readLine 是逐字节 read() 的（HTTP 头里那几十行都是这么读的），
             * 直接怼在裸 socket 上就是几十次系统调用；带上缓冲之后
             * 一次就能把整个头部读完。 */
            val input: InputStream = BufferedInputStream(sock.getInputStream(), 16384)
            val raw = Raw()
            val line = readLine(input)
            if (line == null) {
                // 复用的连接被服务端悄悄关了：丢掉它，用新连接重来一次
                dropPooled(poolKey, sock)
                closeQuietly(sock)
                selfMade = false
                if (reusable) return execBytes(method, urlStr, body, headers)
                throw IOException("空响应")
            }
            val parts = line.split(" ", limit = 3)
            raw.code = parts[1].toInt()
            raw.reason = if (parts.size > 2) parts[2] else ""
            while (true) {
                val h = readLine(input)
                if (h == null || h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) {
                    raw.headers.add(arrayOf(
                        h.substring(0, idx).trim(),
                        h.substring(idx + 1).trim()
                    ))
                }
            }
            val enc = header(raw, "Content-Encoding")
            val len = header(raw, "Content-Length")
            val te = header(raw, "Transfer-Encoding")
            val conn = header(raw, "Connection")
            val isChunked = te != null && te.lowercase(Locale.US).contains("chunked")
            val isGzip = enc != null && enc.lowercase(Locale.US).contains("gzip")

            /* 解码顺序必须是【先拆 chunked，再解 gzip】—— 顺序反了会直接炸。
             *
             * 分块传输的「块长度」是明文写在每块前面的，它本身**没有**被 gzip 压缩。
             * 要是先套上 GZIPInputStream，解压器会把这些长度行当成压缩数据吃掉，
             * 要么抛 "Not in GZIP format"，要么吐出乱码，body 就成了 null。
             *
             * 这正是之前首页报 `Cannot read properties of null (reading 'login')` 的原因：
             * /user 的响应是 chunked + gzip，body 解不出来 → r.data 是 null →
             * 读 r.data.login 就崩了。
             *
             * GitHub 的接口默认就是 chunked + gzip，所以这个顺序几乎每个请求都会走到。 */
            var bodyStream: InputStream = input
            /* drained = 「响应体已经被确切读完，流正好停在下一个响应的边界上」。
             * 它决定这条连接能不能放回池子 —— 只有请求尽了本分、边界确定，
             * 下一个人拿去用才不会读到上一次的残羹冷炙。 */
            var drained = true
            if (isChunked) {
                // 先按分块把「已经解压前的原始字节」拼起来
                val cr = readChunked(input)
                drained = cr.complete          // 没读到终止块 = 边界不确定，别复用
                bodyStream = ByteArrayInputStream(cr.data)
            }
            if (isGzip) {
                bodyStream = GZIPInputStream(bodyStream)
            }
            if (isChunked) {
                raw.body = readAll(bodyStream)
            } else if (isGzip) {
                /* 压缩响应绝不能拿 Content-Length 当读取长度。
                 *
                 * Content-Length 记的是**压缩后**的字节数，而此时 bodyStream
                 * 已经套了解压器 —— 再按 n 去读，读的是解压后的前 n 字节，
                 * 而解压后必然比 n 大，于是**内容被静默截断**。
                 *
                 * 这个坑平时看不出来：GitHub 的 API 默认 chunked+gzip，走的是
                 * 上面那条 readAll 分支。但对象存储（Actions 日志包的落地处）
                 * 是 Content-Length + gzip —— 日志 zip 就被切成一截，
                 * ZipInputStream 读不出条目，界面只回一句「日志包里没有内容」，
                 * 看着像日志过期，其实是自己把包读残了。
                 *
                 * 解压流读到 EOF 就是边界，长度交给解压器自己判断。 */
                raw.body = readAll(bodyStream)
            } else if (len != null) {
                val n = len.trim().toInt()
                raw.body = readFully(bodyStream, n)
                /* 说好 n 个字节却只读到一部分 = 连接在中途断了。
                 * 这种 response 的边界同样不确定，别把这条连接留给下一个人。 */
                if (raw.body!!.size < n) drained = false
            } else if (raw.code != 204 && raw.code != 304) {
                /* 走到 readAll 说明既没有 Content-Length 也没有 chunked ——
                 * 响应只能靠「服务端把连接关掉」来标结束，连接已经废了，不能留。 */
                raw.body = readAll(bodyStream)
                drained = false
            }

            /* 响应体读干净了，连接可以留给下一个请求。
             * 以前这里还要求 !isChunked —— 那是把「不确定」当成「不行」：
             * readChunked 已经把终止块和后面的 trailer 都读掉了（见它自己的注释），
             * 只要它正常返回 complete，分块响应的边界同样是确定的。挡住它的代价
             * 恰恰最贵 —— 有道的响应就是 chunked，等于这条优化对翻译永远不生效。
             * 现在改成看 drained：边界确定才留，不确定就丢，和是不是分块无关。 */
            val keep = reusable && drained
                    && conn != null && conn.lowercase(Locale.US).contains("keep-alive")
            if (keep) {
                putPooled(poolKey, sock)
                selfMade = false
            }
            return raw
        } finally {
            if (selfMade) closeQuietly(sock)
        }
    }

    /** 新建一条连接：DNS 解析 + TCP + TLS 握手 */
    @Throws(IOException::class)
    private fun connect(host: String, port: Int, secure: Boolean, readTimeout: Int): Socket {
        val addr = addressFor(host, port)
        if (secure) {
            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            val plain = Socket()
            plain.connect(if (addr != null) InetSocketAddress(addr, port)
            else InetSocketAddress(host, port), CONNECT_TIMEOUT)
            /*
              注意：即使下面连的是** IP**（绕过 hosts 拿到的真地址），
              这里穿进去的仍是**主机名** —— SNI 与证书校验都靠它。
              连 IP、报主机名，两件事各归各，不能混。
            */
            val ssl = f.createSocket(plain, host, port, true) as SSLSocket
            ssl.soTimeout = readTimeout
            ssl.startHandshake()
            val session = ssl.session
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                try { ssl.close() } catch (ignored: Exception) { }
                throw IOException("证书主机名校验失败: $host")
            }
            return ssl
        }
        val socket = Socket()
        socket.connect(if (addr != null) InetSocketAddress(addr, port)
        else InetSocketAddress(host, port), CONNECT_TIMEOUT)
        socket.soTimeout = readTimeout
        return socket
    }

    /* ============================================================
     * 域名在本机上被解析成了 127.0.0.1 —— 绕过去
     * ============================================================
     *
     * 日志里的真相（用户导出，2026-10-13 14:17）：
     *
     *   保存图片出错。ConnectException: failed to connect to
     *   localhost/127.0.0.1 (port 443) from /127.0.0.1 (port 52900)
     *   地址=https://raw.githubusercontent.com/Buwrt/githup/main/docs/tips.png
     *
     * 地址**完全正常**：主机 raw.githubusercontent.com，既非空也不是本机。
     * 可它连的是 127.0.0.1。
     *
     * 那 `localhost/127.0.0.1` 是谁？是**解析结果**。
     * `new InetSocketAddress(host, port)` 会先做 DNS 解析；InetAddress 的
     * toString() 是 `主机名/IP`，而 127.0.0.1 的反查名恰好就是 localhost ——
     * 于是打印成 `localhost/127.0.0.1`。**它描述的是解析出来的地址，
     * 不是我们写死的地址。**
     *
     * 也就是说：这台手机上，raw.githubusercontent.com 被解析到了 127.0.0.1。
     * 常见来源：hosts 文件被改（各类「GitHub 加速 / hosts 修改器 / 去广告」
     * 工具都会往 /system/etc/hosts 里写 127.0.0.1）、或 VPN 的 DNS 劫持。
     *
     * 为什么浏览器照样能下载：Chrome 自带解析器（并可选 DoH），
     * **不读系统的 hosts 文件**；我们走 InetAddress，会读。
     * 同一个域名，两边问到的是两个不同的地方。
     *
     * 所以前面几轮按「地址写得不对」去猜（data:、协议相对、重定向、
     * loopback 字面量）全猜错了 —— 地址从头到尾都是对的，
     * 错的是**解析结果**。这次不再猜，按解析结果处理：
     *
     *   ① 解析结果里有非本机地址 → 用它（正常情况，行为不变）
     *   ② 全是本机 → 走 DNS-over-HTTPS 问一次真实 IP（不读 hosts）
     *   ③ 还拿不到 → 明确报错，把「被解析到本机」这件事说清楚
     *
     * 三条都只在**解析结果是本机**时才动，正常网络下第一步就返回原行为。
     */
    @Throws(IOException::class)
    private fun addressFor(host: String, port: Int): InetAddress? {
        if (host.isEmpty()) return null
        /* 字面 IP 不查 DNS，也就没有 hosts 的问题 */
        if (isLiteralIp(host)) return null

        val all: Array<InetAddress>?
        try {
            all = InetAddress.getAllByName(host)
        } catch (t: Throwable) {
            return null                        // 解析失败：交给系统按主机名再试一次
        }
        var loopback: InetAddress? = null
        if (all != null) {
            for (a in all) {
                if (isLoopbackAddr(a)) {
                    if (loopback == null) loopback = a
                    continue
                }
                return a                       // ① 有正常地址，用它
            }
        }
        if (loopback == null) return null      // 没解析出东西，照旧

        /* ② 全是本机 —— 问 DoH */
        val real = dohResolve(host)
        if (real != null) return real

        /* ③ 说清楚，别再让人猜 */
        throw IOException("域名 " + host + " 被解析到本机 127.0.0.1"
                + "（手机上的 hosts / 加速工具 / VPN 可能改了解析），"
                + "已尝试绕过仍未成功")
    }

    private fun isLiteralIp(host: String): Boolean =
        host.isNotEmpty() && (host[0] == '['
                || (host[0] >= '0' && host[0] <= '9' && host.indexOf(':') < 0))

    private fun isLoopbackAddr(a: InetAddress?): Boolean {
        if (a == null) return false
        if (a.isLoopbackAddress) return true
        val ip = a.hostAddress ?: return false
        return ip.startsWith("127.") || ip == "0.0.0.0" || ip == "::1"
    }

    /** 正在做 DoH 的线程 —— 防止兜底查询自己也走 connect 造成来回递归 */
    private val IN_DOH: ThreadLocal<Boolean> = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    /**
     * 用 DNS-over-HTTPS 问一次真实 IP。
     *
     * DoH 走的是 HTTPS 明文查询接口，**不经过系统 hosts**，所以能拿到
     * 被 hosts 改掉之前的答案。两个端点都试：阿里在国内可达，Google 兜底。
     *
     * @return 真实 IP；拿不到返回 null
     */
    private fun dohResolve(host: String): InetAddress? {
        if (java.lang.Boolean.TRUE == IN_DOH.get()) return null
        IN_DOH.set(java.lang.Boolean.TRUE)
        try {
            val endpoints = arrayOf(
                "https://dns.alidns.com/resolve?name=%s&type=A",
                "https://dns.google/resolve?name=%s&type=A"
            )
            for (ep in endpoints) {
                try {
                    val url = String.format(Locale.US, ep, URLEncoder.encode(host, "UTF-8"))
                    val headers: MutableMap<String, String> = HashMap()
                    headers["Accept"] = "application/dns-json"
                    val r = requestRaw("GET", url, headers)
                    if (r.code != 200 || r.body == null) continue
                    val json = String(r.body!!, StandardCharsets.UTF_8)
                    /*
                      只认 Answer 段里的 A 记录。Status 非 0（域名不存在等）
                      就换下一个端点。
                    */
                    val m = Pattern
                        .compile("\"data\"\\s*:\\s*\"([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\"")
                        .matcher(json)
                    while (m.find()) {
                        val a = InetAddress.getByName(m.group(1))  // 字面 IP，不查 hosts
                        if (!isLoopbackAddr(a)) return a
                    }
                } catch (ignored: Throwable) {
                }
            }
        } finally {
            IN_DOH.set(java.lang.Boolean.FALSE)
        }
        return null
    }

    /** 预热 DNS：把常用的 GitHub 域名提前解析掉，省掉首屏那次解析等待 */
    @JvmStatic
    fun warmUp() {
        Thread({
            val hosts = arrayOf("api.github.com", "github.com",
                "raw.githubusercontent.com", "avatars.githubusercontent.com")
            for (h in hosts) {
                try {
                    InetAddress.getByName(h)
                } catch (ignored: Exception) {
                }
            }
        }, "http-warmup").start()
    }

    @Throws(IOException::class)
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) break
            if (c == '\n'.code) {
                val b = buf.toByteArray()
                var len = b.size
                if (len > 0 && b[len - 1] == '\r'.code.toByte()) len--
                return String(b, 0, len, StandardCharsets.ISO_8859_1)
            }
            buf.write(c)
            if (buf.size() > 8192) break
        }
        val n = buf.size()
        // 原来这里漏了长度参数，等于把整个底层数组转成字符串（尾部带一堆 \0）
        return if (n > 0) String(buf.toByteArray(), 0, n, StandardCharsets.ISO_8859_1) else null
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, len: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, Math.min(buf.size, remaining))
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    @Throws(IOException::class)
    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 分块传输的读取结果：data 是拼好的原始字节，complete 表示读到了终止块。 */
    private class ChunkResult(val data: ByteArray, val complete: Boolean)

    @Throws(IOException::class)
    private fun readChunked(input: InputStream): ChunkResult {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val line = readLine(input) ?: return ChunkResult(out.toByteArray(), false)
            val size: Int
            try {
                size = line.trim().split(";")[0].toInt(16)
            } catch (e: Exception) {
                return ChunkResult(out.toByteArray(), false)
            }
            if (size == 0) {
                // trailer
                while (true) {
                    val t = readLine(input)
                    if (t == null || t.isEmpty()) break
                }
                return ChunkResult(out.toByteArray(), true)
            }
            /* 按块搬运，不再每块都新分配一个 byte[]。
             * 一个 200KB 的响应常有几百个 1KB 的小块，原来那块
             * 「readFully 建数组 + out.write 再拷一次」纯属白干。 */
            var remaining = size
            while (remaining > 0) {
                val n = input.read(buf, 0, Math.min(buf.size, remaining))
                if (n < 0) throw EOFException("分块传输提前结束")
                out.write(buf, 0, n)
                remaining -= n
            }
            readLine(input) // 结尾 CRLF
        }
    }

    /**
     * 流式 POST：边读流边往 socket 写，不把整个文件读进内存。
     *
     * 虽然名字叫 multipart，但它其实只负责「按 Content-Length 把一个流
     * 原样 POST 出去」，multipart 的头尾由调用方拼进流里；头尾都为空时
     * 就是一次裸体二进制上传（GitHub 附件直传用的正是这种）。
     *
     * 为什么要单独一个方法 ——
     *   requestBytes 要求调用方先把整个文件变成 byte[]，一个 25MB 的视频就是
     *   25MB 常驻内存，低端机很容易被系统杀掉。这里改成流式：
     *   头部字符串 → 文件流（8KB 一块搬运）→ 尾部字符串，
     *   内存占用与文件大小无关。
     *
     * 请求体的拼接顺序不能乱：multipart 的字段顺序是签名的一部分，
     * file 字段必须最后，所以头尾由调用方算好并进 fileStream 里，
     * 这里只按 Content-Length 原样搬运，不关心内容是怎么拼的。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestMultipart(urlStr: String, fileStream: InputStream?,
                         contentLength: Long, contentType: String?,
                         headers: Map<String, String>?): Response =
        requestMultipart("POST", urlStr, fileStream, contentLength, contentType, headers)

    /**
     * 同上，但方法可指定 —— GitHub 的 Contents API（仓库文件上传）要 PUT，
     * 附件直传要 POST。其余行为完全一致。
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestMultipart(method: String, urlStr: String, fileStream: InputStream?,
                         contentLength: Long, contentType: String?,
                         headers: Map<String, String>?): Response {
        val u = URL(urlStr)
        val secure = "https".equals(u.protocol, ignoreCase = true)
        val port = if (u.port > 0) u.port else (if (secure) 443 else 80)
        val host = u.host
        var path = if (u.path == null || u.path.isEmpty()) "/" else u.path
        if (u.query != null) path += "?" + u.query

        val socket = openSocket(secure, host, port)
        try {
            /* 上传是大块连续写：调大内核发送缓冲区，让 TCP 窗口撑得满
               移动网络这种高延迟链路；关掉 Nagle，免得小包被攒着等 ACK。
               弱网下这两项对吞吐的影响格外明显。 */
            try { socket.sendBufferSize = SEND_BUF } catch (ignored: Throwable) { }
            try { socket.tcpNoDelay = true } catch (ignored: Throwable) { }

            val req = StringBuilder()
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            buildHead(req, host, port, secure, headers, contentLength, contentType)
            req.append("\r\n")

            /* ── 上传为什么比下载慢那么多 ──────────────────────
             * 下载走系统 DownloadManager：原生实现、多线程、大缓冲、
             * 系统级调优，基本是能跑多快跑多快。
             *
             * 上传以前是把 8KB 小块直接写进裸 socket —— 每次 write
             * 一次系统调用，TLS 下还要封一条 TLS 记录（上限 16KB），
             * 8KB 只能填一半。记录又多又小、系统调用成倍，
             * 吞吐被自己的写方式掐死，跟带宽没关系。
             *
             * 套一层 64KB 缓冲再写：TLS 记录接近满额，
             * 系统调用次数降一个数量级。
             */
            val os = BufferedOutputStream(socket.getOutputStream(), UPLOAD_BUF)
            os.write(req.toString().toByteArray(StandardCharsets.US_ASCII))
            // 请求头先单独刷出去，让服务端尽早准备接收
            os.flush()

            // 前面剩下的部分（已由调用方写进 head 的字节流）
            if (fileStream != null) {
                val buf = ByteArray(UPLOAD_BUF)
                while (true) {
                    val n = fileStream.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                }
            }
            os.flush()

            return readResponse(socket.getInputStream())
        } finally {
            closeQuietly(socket)
        }
    }

    /** 建立（可能是 TLS 的）连接，TLS 时做主机名校验 */
    @Throws(IOException::class)
    private fun openSocket(secure: Boolean, host: String, port: Int): Socket {
        if (!secure) {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            s.soTimeout = READ_TIMEOUT
            return s
        }
        val f = SSLSocketFactory.getDefault() as SSLSocketFactory
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
        val ssl = f.createSocket(plain, host, port, true) as SSLSocket
        ssl.soTimeout = READ_TIMEOUT
        ssl.startHandshake()
        val session = ssl.session
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
            closeQuietly(ssl)
            throw IOException("证书主机名校验失败: $host")
        }
        return ssl
    }

    /** 拼公共请求头（含 Content-Length / Content-Type） */
    private fun buildHead(req: StringBuilder, host: String, port: Int, secure: Boolean,
                          headers: Map<String, String>?, contentLength: Long,
                          contentType: String?) {
        req.append("Host: ").append(host)
        if (port != (if (secure) 443 else 80)) req.append(':').append(port)
        req.append("\r\n")
        req.append("Connection: close\r\n")
        req.append("Accept-Encoding: identity\r\n")
        req.append("User-Agent: ").append(
            if (headers != null && headers.containsKey("User-Agent"))
                headers["User-Agent"] else "HubMobile/1.0").append("\r\n")
        if (headers != null) {
            for (e in headers.entries) {
                if ("User-Agent".equals(e.key, ignoreCase = true)) continue
                if ("Content-Type".equals(e.key, ignoreCase = true)) continue
                if ("Content-Length".equals(e.key, ignoreCase = true)) continue
                req.append(e.key).append(": ").append(e.value).append("\r\n")
            }
        }
        if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n")
        req.append("Content-Length: ").append(contentLength).append("\r\n")
    }

    /** 读一个完整响应（状态行 + 头 + 体） */
    @Throws(IOException::class)
    private fun readResponse(input: InputStream): Response {
        val raw = Raw()
        val line = readLine(input) ?: throw IOException("空响应")
        val parts = line.split(" ", limit = 3)
        raw.code = parts[1].toInt()
        raw.reason = if (parts.size > 2) parts[2] else ""
        while (true) {
            val h = readLine(input)
            if (h == null || h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) {
                raw.headers.add(arrayOf(
                    h.substring(0, idx).trim(),
                    h.substring(idx + 1).trim()
                ))
            }
        }
        val enc = header(raw, "Content-Encoding")
        val len = header(raw, "Content-Length")
        val te = header(raw, "Transfer-Encoding")
        /*
         * 解码顺序必须先是 chunked、后是 gzip —— 和 execBytes() 里保持同一套写法。
         * chunked 的分块长度行是明文，必须在 gzip 解压之前剥掉；反过来套会导致
         * GZIPInputStream 把长度行当成压缩数据，直接抛 ZipException，上传结果丢失。
         */
        val isChunked = te != null && te.lowercase(Locale.US).contains("chunked")
        val isGzip = enc != null && enc.lowercase(Locale.US).contains("gzip")
        var bodyStream: InputStream = input
        if (isChunked) {
            val chunked = readChunked(input)
            bodyStream = ByteArrayInputStream(chunked.data)
        }
        if (isGzip) {
            bodyStream = GZIPInputStream(bodyStream)
        }
        if (isChunked) {
            raw.body = readAll(bodyStream)
        } else if (len != null) {
            val n = len.trim().toInt()
            raw.body = readFully(bodyStream, n)
        } else if (raw.code != 204 && raw.code != 304) {
            raw.body = readAll(bodyStream)
        }

        val r = Response()
        r.code = raw.code
        r.body = if (raw.body == null) "" else String(raw.body!!, StandardCharsets.UTF_8)
        r.headers = headersJson(raw)
        return r
    }

    private fun closeQuietly(c: Closeable?) {
        if (c == null) return
        try { c.close() } catch (ignored: Exception) { }
    }

    private fun header(headers: List<Array<String>>, name: String): String? {
        for (kv in headers) if (kv[0].equals(name, ignoreCase = true)) return kv[1]
        return null
    }

    private fun header(raw: Raw, name: String): String? = header(raw.headers, name)

    @JvmStatic
    fun log(msg: String) {
        Log.d(TAG, msg)
    }
}
