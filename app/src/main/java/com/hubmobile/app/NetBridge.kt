package com.hubmobile.app

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 网络这一摊：普通请求、base64 拉二进制（README 图片 / 头像）、图片快车道。
 *
 * 页面上的数据请求和 README 图片都从这里走原生网络栈 ——
 * WebView 直连 raw.githubusercontent.com 在不少网络下不通，而同样的网络下
 * 仓库列表 / README 文本能正常加载，差别就在这里。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class NetBridge(private val ctx: BridgeContext) {

    /** README 图片代理：由 MainActivity 建好后交过来，详见 ImageProxy */
    private var imageProxy: ImageProxy? = null

    fun setImageProxy(p: ImageProxy?) {
        imageProxy = p
    }

    @JavascriptInterface
    fun http(id: String?, method: String?, url: String?, body: String?, headersJson: String?) {
        ctx.background {
            try {
                val headers = parseHeaders(headersJson, keepEmpty = true)
                val r = Http.request(method ?: "GET", url ?: "", body, headers)
                ctx.runJs(
                    "window.Native._cb(" + JSONObject.quote(id.toString()) + ","
                            + r.code + "," + JSONObject.quote(r.body ?: "") + ","
                            + JSONObject.quote(r.headers ?: "{}") + ")"
                )
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                ctx.runJs(
                    "window.Native._cb(" + JSONObject.quote(id.toString()) + ",0,"
                            + JSONObject.quote("") + ","
                            + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "}") + ")"
                )
            }
        }
    }

    /**
     * 以 Base64 拉取二进制资源（README 图片、头像等）。
     *
     * 带什么头由前端决定（只有 GitHub 自家域名才带令牌，外链绝不带）。
     */
    @JavascriptInterface
    fun httpB64(id: String?, url: String?, headersJson: String?) {
        ctx.background {
            try {
                val headers = parseHeaders(headersJson, keepEmpty = false)
                val r = Http.requestB64("GET", url ?: "", null, headers)
                emitB64(id, r.code, r.body, r.headers ?: "{}")
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                emitB64(id, 0, "", "{\"error\":" + JSONObject.quote(msg) + "}")
            }
        }
    }

    private fun parseHeaders(headersJson: String?, keepEmpty: Boolean): Map<String, String> {
        val headers = HashMap<String, String>()
        if (headersJson.isNullOrEmpty()) return headers
        val jo = JSONObject(headersJson)
        val it = jo.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = jo.optString(k, "")
            if (keepEmpty || v.isNotEmpty()) headers[k] = v
        }
        return headers
    }

    /**
     * 前端问一句：图片的快车道开着吗？
     *
     * 开着就意味着 App 会在 WebView 取图的时候直接把字节接过去（见
     * ImageProxy），前端于是**什么都不用做** —— <img src> 保持原样，
     * 让 WebView 自己去拉就是最快的那条路。
     * 关着（或者拼法变了拿不到这个方法）就退回老的 base64 通道，
     * 慢是慢点，图照样出得来。
     */
    @JavascriptInterface
    fun imageProxyReady(): Boolean = imageProxy != null

    /**
     * 预热：README 渲染完之后把前几张图的地址丢过来，后台先下到缓存里。
     *
     * 有了懒加载，图片是滑到眼前才发请求的 —— 用户看到的「每次滑到这儿
     * 都要等一下」就是这么来的。提前灌进缓存，滑到时是读本地文件。
     */
    @JavascriptInterface
    fun prefetchImages(urlsJson: String?) {
        val p = imageProxy ?: return
        if (urlsJson.isNullOrEmpty()) return
        try {
            val arr = org.json.JSONArray(urlsJson)
            val n = minOf(arr.length(), 12)
            val urls = ArrayList<String>(n)
            for (i in 0 until n) {
                val u = arr.optString(i, "")
                if (u.isNotEmpty()) urls.add(u)
            }
            if (urls.isEmpty()) return
            ctx.background { p.prefetch(urls) }
        } catch (ignored: Throwable) {
            /* 预热失败不算失败：到时候会走常规路径，用户无感 */
        }
    }

    /*
     * Base64 回传的单次上限（字符数）。
     *
     * evaluateJavascript 底层是 Binder IPC，单次事务上限约 1MB —— README 里
     * 一张 417KB 的赞赏码图，base64 之后就是 556KB 字符，再叠上 4 条并发，
     * 一次要塞 2MB 过 Binder，结果是一张都传不回来（表现就是「大图加载不出来，
     * 小图没事」）。所以超过阈值的响应切成小片依次下发，前端拼好再交付。
     *
     * 48KB 是留足余量的取值：连電普通 API 响应（几十 KB）根本不会走到分片
     * 这条路，只有真正的图片/大附件才会。 */
    private val b64Chunk = 48 * 1024

    /**
     * 下发 base64 响应：小的一次给完，大的先 _begin 登记、再逐片 _chunk。
     * 全程走 runOnUiThread，同一线程的 post 是 FIFO，顺序不会乱。
     */
    private fun emitB64(id: String?, code: Int, body: String?, headers: String?) {
        val b = body ?: ""
        val qid = JSONObject.quote(id.toString())
        val qh = JSONObject.quote(headers ?: "{}")
        if (b.length <= b64Chunk) {
            ctx.runJs(
                "window.Native._cb(" + qid + "," + code + ","
                        + JSONObject.quote(b) + "," + qh + ")"
            )
            return
        }
        val n = (b.length + b64Chunk - 1) / b64Chunk
        ctx.runJs("window.Native._begin(" + qid + "," + code + "," + n + "," + qh + ")")
        for (i in 0 until n) {
            val end = minOf(b.length, (i + 1) * b64Chunk)
            ctx.runJs(
                "window.Native._chunk(" + qid + "," + i + ","
                        + JSONObject.quote(b.substring(i * b64Chunk, end)) + ")"
            )
        }
    }
}
