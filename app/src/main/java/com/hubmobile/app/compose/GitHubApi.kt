package com.hubmobile.app.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hubmobile.app.Http
import com.hubmobile.app.SecurePrefs
import org.json.JSONObject

/*
 * 路线 B 的数据层。
 *
 * 关键决定：不引入 OkHttp / Retrofit / Gson，也不另写一套网络栈，
 * 直接复用项目里已经跑了两年的 Http.kt（基于 SSLSocket 手写，支持
 * 连接复用、重定向、限速）和 SecurePrefs（Keystore AES-GCM 存令牌）。
 *
 * 这样路线 B 只换「怎么画」，不换「怎么取数」—— 网络层的行为
 * （超时、代理、TLS、重试）与网页版完全一致，不会因为换 UI 就出现
 * 「网页能开、原生打不开」这种差异。
 *
 * JSON 用框架自带的 org.json，不引 Gson。
 */

data class RepoInfo(
    val fullName: String,
    val ownerLogin: String,
    val ownerAvatarUrl: String,
    val description: String,
    val stars: Int,
    val forks: Int,
    val watchers: Int,
    val openIssues: Int,
    val language: String,
    val license: String,
    val defaultBranch: String,
    val updatedAt: String,
    val private: Boolean,
    val fork: Boolean = false,
    val topics: List<String>,
    val archived: Boolean = false,
    val homepage: String = "",
    val cloneUrl: String = "",
    val sshUrl: String = ""
) {
    /** 仓库名（去掉 owner 前缀）。列表里显示 owner/name，这里单独要 name */
    val name: String get() = fullName.substringAfterLast('/')
    companion object {
        fun from(o: JSONObject): RepoInfo {
            val owner = o.optJSONObject("owner")
            val lic = o.optJSONObject("license")
            val arr = o.optJSONArray("topics")
            val topics = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    if (s.isNotEmpty()) topics.add(s)
                }
            }
            return RepoInfo(
                fullName = o.safe("full_name"),
                ownerLogin = owner?.safe("login") ?: "",
                ownerAvatarUrl = owner?.safe("avatar_url") ?: "",
                description = o.safe("description"),
                stars = o.optInt("stargazers_count"),
                forks = o.optInt("forks_count"),
                watchers = o.optInt("subscribers_count"),
                openIssues = o.optInt("open_issues_count"),
                language = o.safe("language"),
                license = lic?.safe("spdx_id") ?: "",
                defaultBranch = o.safe("default_branch"),
                updatedAt = o.safe("updated_at"),
                private = o.optBoolean("private"),
                fork = o.optBoolean("fork"),
                topics = topics,
                archived = o.optBoolean("archived"),
                homepage = o.safe("homepage"),
                cloneUrl = o.safe("clone_url"),
                sshUrl = o.safe("ssh_url")
            )
        }
    }
}

data class ContentEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val sha: String
)

/** 请求失败时抛出：把 HTTP 状态码带给 UI，好区分 404 / 403 / 限流。 */
class ApiError(val code: Int, msg: String) : Exception(msg)

/*
 * org.json 的坑：optString() 在字段值是 JSON null 时，
 * 部分实现会返回字符串 "null" 而不是空串 —— 直接画到屏幕上就会显示 "null"。
 * 实测 GitHub API 对「没填描述」「没设语言」的仓库正是返回 null，
 * 首页因此出现了一批 "null"。统一走这个函数，把 null / 字面量 "null" 都收成空串。
 */
private fun JSONObject.safe(key: String): String {
    if (isNull(key)) return ""
    val v = optString(key, "")
    return if (v.isBlank() || "null".equals(v, ignoreCase = true)) "" else v
}

object GitHubApi {

    private const val API = "https://api.github.com"
    /** 与 JsBridge.TOKEN_KEY 保持一致，令牌是同一份，不用重新登录。 */
    const val TOKEN_KEY = "gh_token"

    fun token(ctx: Context): String = SecurePrefs.get(ctx, TOKEN_KEY, "")

    fun hasToken(ctx: Context): Boolean = token(ctx).isNotBlank()

    /** 写操作统一走这里：返回响应体；非 2xx 抛 ApiError */
    private fun write(method: String, url: String, body: String?, ctx: Context): String {
        val r = Http.request(method, url, body, headers(ctx))
        if (r.code !in 200..299) {
            throw ApiError(r.code, r.body.take(120))
        }
        return r.body
    }

    private fun headers(ctx: Context): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/vnd.github+json"
        h["X-GitHub-Api-Version"] = "2022-11-28"
        h["User-Agent"] = "githup"
        val t = token(ctx)
        // 没令牌也能看公开仓库，只是限流更狠（60 次/小时 vs 5000）
        if (t.isNotBlank()) h["Authorization"] = "Bearer $t"
        return h
    }

    /**
     * 阻塞式请求，调用方必须放在 Dispatchers.IO。
     * 这里不自己开线程：把线程策略交给 UI 层，方便统一处理取消。
     */
    private fun get(ctx: Context, url: String): String {
        val r = Http.request("GET", url, null, headers(ctx))
        if (r.code !in 200..299) {
            throw ApiError(r.code, "HTTP ${r.code}: ${r.body.take(200)}")
        }
        return r.body
    }

    fun repo(ctx: Context, owner: String, name: String): RepoInfo =
        RepoInfo.from(JSONObject(get(ctx, "$API/repos/$owner/$name")))

    fun branches(ctx: Context, owner: String, name: String): List<String> {
        val s = get(ctx, "$API/repos/$owner/$name/branches?per_page=100")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("name"))
        return out
    }

    fun contents(
        ctx: Context, owner: String, name: String,
        path: String, ref: String
    ): List<ContentEntry> {
        val p = if (path.isEmpty()) "" else "/" + path.trim('/')
        val url = "$API/repos/$owner/$name/contents$p?ref=" +
            java.net.URLEncoder.encode(ref, "UTF-8")
        val arr = org.json.JSONArray(get(ctx, url))
        val out = ArrayList<ContentEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ContentEntry(
                    name = o.safe("name"),
                    path = o.safe("path"),
                    isDir = "dir" == o.safe("type"),
                    size = o.optLong("size"),
                    sha = o.safe("sha")
                )
            )
        }
        // 目录排前面，再按名字排 —— 与网页版 / GitHub 网页一致
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    /**
     * 头像：走 Http.requestRaw 拿原始字节，BitmapFactory 解码。
     * 不引 Coil / Glide —— 一个头像不值得拉一整个图片库进来。
     */
    /**
     * 头像 / 小图加载。
     *
     * ⚠️ 这里以前是 `BitmapFactory.decodeByteArray(b, 0, b.size)` 直接全尺寸解码 ——
     * 而 GitHub 的 avatar_url 是 `.../u/xxx?v=4`，**不带尺寸参数，返回的是原图**。
     * 用户头像原图常见 1000~4000 px，按 ARGB_8888 算：
     *
     *     4000 × 4000 × 4B = 64 MB
     *
     * 一个头像就吃掉几十 MB，列表里同时加载十几个（首页仓库列表、用户页、
     * 评论列表都在调它）就是几百 MB —— 中低端机直接 OOM，
     * 表现就是「界面还在转圈，然后整个 App 没了」。
     *
     * 现在做两道保险：
     *   1. 请求时就让 GitHub 返回小图（拼 ?s= / &s=）
     *   2. 拿到字节后先 inJustDecodeBounds 读尺寸，再按目标边长降采样
     *
     * 为什么两道都要：CDN 不一定认 s 参数（自建 / 代理镜像常忽略），
     * 只靠 URL 参数挡不住；而只靠降采样的话，大图要先整个下载完，
     * 白白浪费流量和时间。
     */
    fun loadBitmap(ctx: Context, url: String, maxPx: Int = 128): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val sized = sizedAvatarUrl(url, maxPx)
            val raw = Http.requestRaw("GET", sized, headers(ctx))
            val b = raw.body ?: return null
            if (raw.code !in 200..299) return null
            decodeSampled(b, maxPx)
        } catch (e: OutOfMemoryError) {
            /* OOM 要单独吞掉并让 GC 有机会回收：
               继续往外抛会把整个进程带走（就是「闪退」）。 */
            null
        } catch (e: Throwable) {
            null
        }
    }

    /** 给头像 URL 拼尺寸参数：avatars.githubusercontent.com 认 ?s= */
    private fun sizedAvatarUrl(url: String, px: Int): String {
        return try {
            if (!url.contains("avatars.githubusercontent.com")) return url
            val sep = if (url.contains("?")) "&" else "?"
            "$url${sep}s=$px"
        } catch (e: Throwable) { url }
    }

    /** 按目标最大边长降采样解码，避免大图直接撑爆堆 */
    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        val w = probe.outWidth
        val h = probe.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / sample > maxPx || h / sample > maxPx) sample *= 2
        val opt = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
    }

    data class UserInfo(
        val login: String,
        val name: String,
        val avatarUrl: String,
        val bio: String,
        val publicRepos: Int,
        val followers: Int,
        val following: Int,
        val htmlUrl: String,
        /* 编辑资料要用的字段（对齐 page-user.js 的 editProfile 表单） */
        val company: String = "",
        val location: String = "",
        val blog: String = "",
        val email: String = "",
        val hireable: Boolean = false,
        val publicGists: Int = 0,
        val social: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun from(o: JSONObject): UserInfo {
                val soc = HashMap<String, String>()
                val sa = o.optJSONArray("social_accounts")
                if (sa != null) for (i in 0 until sa.length()) {
                    val it = sa.optJSONObject(i) ?: continue
                    val pv = it.safe("provider")
                    if (pv.isNotBlank()) soc[pv] = it.safe("url")
                }
                return UserInfo(
                    login = o.safe("login"),
                    name = o.safe("name"),
                    avatarUrl = o.safe("avatar_url"),
                    bio = o.safe("bio"),
                    publicRepos = o.optInt("public_repos"),
                    followers = o.optInt("followers"),
                    following = o.optInt("following"),
                    htmlUrl = o.safe("html_url"),
                    company = o.safe("company"),
                    location = o.safe("location"),
                    blog = o.safe("blog"),
                    email = o.safe("email"),
                    hireable = o.optBoolean("hireable"),
                    publicGists = o.optInt("public_gists"),
                    social = soc
                )
            }
        }
    }

    /** 当前登录者；没令牌时返回 null（不抛异常，好让 UI 显示"未登录"） */
    fun me(ctx: Context): UserInfo? = try {
        UserInfo.from(JSONObject(get(ctx, "$API/user")))
    } catch (e: Throwable) {
        null
    }

    /**
     * 登录校验专用：返回真实结果，不像 me() 那样把异常吞成 null。
     *
     * 为什么要单独开一个：me() 的 catch-all 会把
     *   401 令牌无效 / 403 被限流 / 网络异常 / 返回体解析失败
     * 四种完全不同的情况全都变成 null，界面只能含糊报
     * 「令牌无效或网络不可用」。登录时必须分得清。
     */
    data class MeResult(val code: Int, val user: UserInfo?)

    fun meChecked(ctx: Context): MeResult {
        val r: Http.Response = try {
            Http.request("GET", "$API/user", null, headers(ctx))
        } catch (e: Throwable) {
            return MeResult(-1, null)          // 网络不可用
        }
        if (r.code == 401) return MeResult(401, null)   // 令牌无效 / 已过期
        if (r.code == 403) return MeResult(403, null)   // 被限流
        if (r.code !in 200..299) return MeResult(r.code, null)
        return try {
            MeResult(200, UserInfo.from(JSONObject(r.body)))
        } catch (e: Throwable) {
            MeResult(-2, null)                  // 返回体解析失败
        }
    }

    fun user(ctx: Context, login: String): UserInfo =
        UserInfo.from(JSONObject(get(ctx, "$API/users/$login")))

    /** 某用户的公开仓库，按更新时间倒序 */
    fun userRepos(ctx: Context, login: String, page: Int = 1): List<RepoInfo> {
        val s = get(ctx,
            "$API/users/$login/repos?per_page=100&page=$page&sort=updated")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<RepoInfo>()
        for (i in 0 until arr.length()) out.add(RepoInfo.from(arr.getJSONObject(i)))
        return out
    }

    /** 当前登录者能看到的仓库（含私有） */
    fun myRepos(ctx: Context, page: Int = 1): List<RepoInfo> {
        val s = get(ctx,
            "$API/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner,collaborator")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<RepoInfo>()
        for (i in 0 until arr.length()) out.add(RepoInfo.from(arr.getJSONObject(i)))
        return out
    }

    fun starred(ctx: Context, login: String, page: Int = 1): List<RepoInfo> {
        val s = get(ctx, "$API/users/$login/starred?per_page=100&page=$page")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<RepoInfo>()
        for (i in 0 until arr.length()) out.add(RepoInfo.from(arr.getJSONObject(i)))
        return out
    }

    data class SearchResult(val total: Int, val items: List<RepoInfo>)

    fun searchRepos(ctx: Context, q: String, page: Int = 1): SearchResult {
        val url = "$API/search/repositories?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&per_page=50&page=$page"
        val o = JSONObject(get(ctx, url))
        val arr = o.optJSONArray("items") ?: org.json.JSONArray()
        val out = ArrayList<RepoInfo>()
        for (i in 0 until arr.length()) out.add(RepoInfo.from(arr.getJSONObject(i)))
        return SearchResult(o.optInt("total_count"), out)
    }

    /* ---------- 文件内容 ---------- */

    data class FileContent(
        val name: String,
        val path: String,
        val size: Long,
        /** 文本内容；二进制文件为 null */
        val text: String?,
        val isBinary: Boolean
    )

    /**
     * 取单个文件内容。
     * GitHub 的 contents API 对文本文件返回 base64 编码的字段；
     * 超过 1MB 的文件这个接口不可用（会 403），那种情况返回 isBinary=true 让 UI 提示。
     */
    fun fileContent(ctx: Context, owner: String, name: String, path: String, ref: String): FileContent {
        val url = "$API/repos/$owner/$name/contents/" +
            path.trim('/').split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") } +
            "?ref=" + java.net.URLEncoder.encode(ref, "UTF-8")
        val o = JSONObject(get(ctx, url))
        val size = o.optLong("size")
        val b64 = o.optString("content", "")
        val enc = o.optString("encoding", "")
        val text = if ("base64" == enc && b64.isNotBlank()) {
            try {
                String(android.util.Base64.decode(b64.replace("\n", ""), android.util.Base64.DEFAULT),
                    Charsets.UTF_8)
            } catch (e: Throwable) { null }
        } else null
        return FileContent(
            name = o.safe("name"),
            path = o.safe("path"),
            size = size,
            text = text,
            isBinary = text == null
        )
    }

    /* ---------- Issues ---------- */

    data class IssueInfo(
        val number: Int,
        val title: String,
        val state: String,
        val user: String,
        val comments: Int,
        val createdAt: String,
        val labels: List<String>
    ) {
        companion object {
            fun from(o: JSONObject): IssueInfo {
                val arr = o.optJSONArray("labels")
                val ls = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.safe("name")?.let { if (it.isNotEmpty()) ls.add(it) }
                }
                return IssueInfo(
                    number = o.optInt("number"),
                    title = o.safe("title"),
                    state = o.safe("state"),
                    user = o.optJSONObject("user")?.safe("login") ?: "",
                    comments = o.optInt("comments"),
                    createdAt = o.safe("created_at"),
                    labels = ls
                )
            }
        }
    }

    /** open / closed / all */
    fun issues(ctx: Context, owner: String, name: String, state: String = "open"): List<IssueInfo> {
        val s = get(ctx, "$API/repos/$owner/$name/issues?state=$state&per_page=50&sort=updated")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<IssueInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // issues 接口会把 PR 一起返回，这里过滤掉
            if (o.has("pull_request")) continue
            out.add(IssueInfo.from(o))
        }
        return out
    }

    /** 相对时间：3 天前 / 2 个月前 */
    fun ago(iso: String): String {
        if (iso.length < 10) return iso
        return try {
            val y = iso.substring(0, 4).toInt()
            val m = iso.substring(5, 7).toInt()
            val d = iso.substring(8, 10).toInt()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            val ny = cal.get(java.util.Calendar.YEAR)
            val nm = cal.get(java.util.Calendar.MONTH) + 1
            val nd = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val days = ((ny - y) * 365 + (nm - m) * 30 + (nd - d))
            when {
                days <= 0 -> "今天"
                days == 1 -> "昨天"
                days < 30 -> "$days 天前"
                days < 365 -> "${days / 30} 个月前"
                else -> "${days / 365} 年前"
            }
        } catch (e: Throwable) { iso.substring(0, 10) }
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024.0 && i < u.size - 1) { v /= 1024.0; i++ }
        return if (i == 0) "$bytes B" else String.format("%.1f %s", v, u[i])
    }

    /** 2026-10-20T08:11:00Z → 2026-10-20 */
    fun shortDate(iso: String): String = if (iso.length >= 10) iso.substring(0, 10) else iso

    /* ---------- 与 api.js 对齐的格式化 ---------- */

    /**
     * 对齐 api.js 的 U.num()：1000000 → "1m"，1000 → "1.2k"，且去掉 ".0"。
     * 之前自己写的 compact() 用的是 "1.2k / 1.0M"，与网页版不一致。
     */
    fun num(n: Int): String {
        if (n >= 1_000_000) {
            val v = (n / 1_000_000.0).let { String.format("%.1f", it) }
            return (if (v.endsWith(".0")) v.dropLast(2) else v) + "m"
        }
        if (n >= 1000) {
            val v = (n / 1000.0).let { String.format("%.1f", it) }
            return (if (v.endsWith(".0")) v.dropLast(2) else v) + "k"
        }
        return n.toString()
    }

    /** 对齐 api.js 的 U.timeAgo() */
    fun timeAgo(iso: String): String {
        if (iso.isBlank()) return ""
        val t = try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(iso)?.time ?: return ""
        } catch (e: Throwable) { return "" }
        val sec = ((System.currentTimeMillis() - t) / 1000).toInt()
        return when {
            sec < 45 -> "刚刚"
            sec < 3600 -> "${sec / 60} 分钟前"
            sec < 86400 -> "${sec / 3600} 小时前"
            sec < 2592000 -> "${sec / 86400} 天前"
            sec < 31536000 -> "${sec / 2592000} 个月前"
            else -> "${sec / 31536000} 年前"
        }
    }

    /** 对齐 api.js 的 U.langColor()：语言色点 */
    fun langColor(lang: String): Long {
        val m = mapOf(
            "JavaScript" to 0xFFF1E05A, "TypeScript" to 0xFF3178C6, "Python" to 0xFF3572A5,
            "Java" to 0xFFB07219, "Kotlin" to 0xFFA97BFF, "Go" to 0xFF00ADD8,
            "Rust" to 0xFFDEA584, "C++" to 0xFFF34B7D, "C" to 0xFF555555,
            "C#" to 0xFF178600, "Ruby" to 0xFF701516, "PHP" to 0xFF4F5D95,
            "Swift" to 0xFFF05138, "Dart" to 0xFF00B4AB, "HTML" to 0xFFE34C26,
            "CSS" to 0xFF563D7C, "SCSS" to 0xFFC6538C, "Vue" to 0xFF41B883,
            "Shell" to 0xFF89E051, "Lua" to 0xFF000080, "Scala" to 0xFFC22D40,
            "Perl" to 0xFF0298C3, "R" to 0xFF198CE7, "Objective-C" to 0xFF438EFF,
            "Dockerfile" to 0xFF384D54, "Makefile" to 0xFF427819,
            "Jupyter Notebook" to 0xFFDA5B0B, "Elixir" to 0xFF6E4A7E,
            "Haskell" to 0xFF5E5086, "Julia" to 0xFFA270BA, "Zig" to 0xFFEC915C,
            "Vim Script" to 0xFF199F4B, "PowerShell" to 0xFF012456,
            "Assembly" to 0xFF6E4C13, "Nix" to 0xFF7E7EFF
        )
        return m[lang] ?: 0xFF8B949E
    }

    /* ---------- 通知 ---------- */

    data class NotifInfo(
        val id: String,
        val title: String,
        val repoFullName: String,
        val reason: String,
        val unread: Boolean,
        val updatedAt: String,
        /** subject url 里的编号，用于拼网页链接 */
        val subjectUrl: String,
        val type: String
    ) {
        companion object {
            fun from(o: JSONObject): NotifInfo = NotifInfo(
                id = o.safe("id"),
                title = o.optJSONObject("subject")?.safe("title") ?: "",
                repoFullName = o.optJSONObject("repository")?.safe("full_name") ?: "",
                reason = o.safe("reason"),
                unread = o.optBoolean("unread"),
                updatedAt = o.safe("updated_at"),
                subjectUrl = o.optJSONObject("subject")?.safe("url") ?: "",
                type = o.optJSONObject("subject")?.safe("type") ?: ""
            )
        }
    }

    fun notifications(ctx: Context): List<NotifInfo> {
        val s = get(ctx, "$API/notifications?per_page=50")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<NotifInfo>()
        for (i in 0 until arr.length()) out.add(NotifInfo.from(arr.getJSONObject(i)))
        return out
    }

    /** 通知的网页链接：subject.url 是 API 地址，要换成 html 的 */
    fun notifWebUrl(n: NotifInfo): String {
        // https://api.github.com/repos/o/r/issues/12 → https://github.com/o/r/issues/12
        val u = n.subjectUrl.replace("https://api.github.com/repos/", "https://github.com/")
        return if (u.startsWith("http")) u else "https://github.com/${n.repoFullName}"
    }

    /* ---------- 动态（events） ---------- */

    data class EventInfo(
        val type: String,
        val actor: String,
        val actorAvatar: String,
        val repoName: String,
        val action: String,
        val title: String,
        val createdAt: String,
        val url: String
    )

    /** /users/:login/events/public —— 用户动态 */
    fun events(ctx: Context, login: String, page: Int = 1): List<EventInfo> {
        val s = get(ctx, "$API/users/$login/events/public?per_page=30&page=$page")
        val arr = org.json.JSONArray(s)
        val out = ArrayList<EventInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.safe("type")
            val actor = o.optJSONObject("actor")?.safe("login") ?: ""
            val repo = o.optJSONObject("repo")?.safe("name") ?: ""
            val payload = o.optJSONObject("payload")
            val title = when (t) {
                "PushEvent" -> {
                    val n = payload?.optJSONArray("commits")?.length() ?: 0
                    "推送了 $n 个提交"
                }
                "IssuesEvent" -> (payload?.safe("action") ?: "") + " 议题 " +
                    (payload?.optJSONObject("issue")?.safe("title") ?: "")
                "PullRequestEvent" -> (payload?.safe("action") ?: "") + " 拉取请求 " +
                    (payload?.optJSONObject("pull_request")?.safe("title") ?: "")
                "WatchEvent" -> "Star 了"
                "ForkEvent" -> "Fork 到 " + (payload?.optJSONObject("forkee")?.safe("full_name") ?: "")
                "CreateEvent" -> "创建了 " + (payload?.safe("ref_type") ?: "")
                "IssueCommentEvent" -> "评论了 " +
                    (payload?.optJSONObject("issue")?.safe("title") ?: "")
                "DeleteEvent" -> "删除了 " + (payload?.safe("ref_type") ?: "")
                "ReleaseEvent" -> "发布了 " +
                    (payload?.optJSONObject("release")?.safe("name") ?: "")
                "PublicEvent" -> "公开了仓库"
                "MemberEvent" -> "添加了协作者"
                else -> t
            }
            out.add(EventInfo(
                type = t,
                actor = actor,
                actorAvatar = o.optJSONObject("actor")?.safe("avatar_url") ?: "",
                repoName = repo,
                action = payload?.safe("action") ?: "",
                title = title,
                createdAt = o.safe("created_at"),
                url = if (repo.isNotBlank()) "https://github.com/$repo" else ""
            ))
        }
        return out
    }

    /* ---------- 用户 / 组织 ---------- */

    fun followers(ctx: Context, login: String): List<UserInfo> =
        userList(ctx, "$API/users/$login/followers")

    fun following(ctx: Context, login: String): List<UserInfo> =
        userList(ctx, "$API/users/$login/following")

    fun orgs(ctx: Context, login: String): List<UserInfo> =
        userList(ctx, "$API/users/$login/orgs")

    private fun userList(ctx: Context, url: String): List<UserInfo> {
        val arr = org.json.JSONArray(get(ctx, "$url?per_page=100"))
        val out = ArrayList<UserInfo>()
        for (i in 0 until arr.length()) out.add(UserInfo.from(arr.getJSONObject(i)))
        return out
    }

    data class SearchUsersResult(val total: Int, val items: List<UserInfo>)

    fun searchUsers(ctx: Context, q: String, page: Int = 1): SearchUsersResult {
        val o = JSONObject(get(ctx, "$API/search/users?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&per_page=30&page=$page"))
        val arr = o.optJSONArray("items") ?: org.json.JSONArray()
        val out = ArrayList<UserInfo>()
        for (i in 0 until arr.length()) out.add(UserInfo.from(arr.getJSONObject(i)))
        return SearchUsersResult(o.optInt("total_count"), out)
    }

    /* ---------- 通知操作 ---------- */

    /** 全部标为已读 */
    fun markAllRead(ctx: Context): Boolean = try {
        write("PUT", "$API/notifications", "{}", ctx)
        true
    } catch (e: Throwable) { false }

    /** 单条标为已读：thread id */
    fun markRead(ctx: Context, threadId: String): Boolean = try {
        write("PATCH", "$API/notifications/threads/$threadId", "{}", ctx)
        true
    } catch (e: Throwable) { false }

    /* ---------- Gist ---------- */

    data class GistInfo(
        val id: String,
        val description: String,
        val files: List<String>,
        val updatedAt: String,
        val public: Boolean
    )

    fun gists(ctx: Context, login: String): List<GistInfo> {
        val arr = org.json.JSONArray(get(ctx, "$API/users/$login/gists?per_page=50"))
        val out = ArrayList<GistInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val fs = o.optJSONObject("files")
            val names = ArrayList<String>()
            if (fs != null) {
                val it = fs.keys()
                while (it.hasNext()) names.add(it.next())
            }
            out.add(GistInfo(o.safe("id"), o.safe("description"), names,
                o.safe("updated_at"), o.optBoolean("public")))
        }
        return out
    }

    /* ---------- 仓库子页 ---------- */

    data class PrInfo(
        val number: Int,
        val title: String,
        val state: String,
        val user: String,
        val createdAt: String,
        val merged: Boolean,
        val draft: Boolean,
        val comments: Int
    ) {
        companion object {
            fun from(o: JSONObject): PrInfo {
                val merged = !o.isNull("merged_at") && o.safe("merged_at").isNotBlank()
                return PrInfo(
                    number = o.optInt("number"),
                    title = o.safe("title"),
                    state = o.safe("state"),
                    user = o.optJSONObject("user")?.safe("login") ?: "",
                    createdAt = o.safe("created_at"),
                    merged = merged,
                    draft = o.optBoolean("draft"),
                    comments = o.optInt("comments")
                )
            }
        }
    }

    fun pulls(ctx: Context, owner: String, name: String, state: String = "open"): List<PrInfo> {
        val arr = org.json.JSONArray(get(ctx,
            "$API/repos/$owner/$name/pulls?state=$state&per_page=50&sort=updated"))
        val out = ArrayList<PrInfo>()
        for (i in 0 until arr.length()) out.add(PrInfo.from(arr.getJSONObject(i)))
        return out
    }

    data class ReleaseInfo(
        val id: Long,
        val tag: String,
        val name: String,
        val body: String,
        val publishedAt: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val assets: List<AssetInfo>
    )
    data class AssetInfo(val name: String, val size: Long, val url: String, val downloads: Int)

    fun releases(ctx: Context, owner: String, name: String): List<ReleaseInfo> {
        val arr = org.json.JSONArray(get(ctx, "$API/repos/$owner/$name/releases?per_page=30"))
        val out = ArrayList<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val aa = o.optJSONArray("assets")
            val assets = ArrayList<AssetInfo>()
            if (aa != null) for (j in 0 until aa.length()) {
                val a = aa.getJSONObject(j)
                assets.add(AssetInfo(a.safe("name"), a.optLong("size"),
                    a.safe("browser_download_url"), a.optInt("download_count")))
            }
            out.add(ReleaseInfo(o.optLong("id"), o.safe("tag_name"), o.safe("name"),
                o.safe("body"), o.safe("published_at"), o.optBoolean("draft"),
                o.optBoolean("prerelease"), assets))
        }
        return out
    }

    data class CommitInfo(val sha: String, val message: String, val author: String, val date: String)
    data class ContributorInfo(val login: String, val avatar: String, val contributions: Int)

    fun commits(ctx: Context, owner: String, name: String): List<CommitInfo> {
        val arr = org.json.JSONArray(get(ctx, "$API/repos/$owner/$name/commits?per_page=50"))
        val out = ArrayList<CommitInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(CommitInfo(
                sha = o.safe("sha"),
                message = o.optJSONObject("commit")?.safe("message") ?: "",
                author = o.optJSONObject("author")?.safe("login")
                    ?: o.optJSONObject("commit")?.optJSONObject("author")?.safe("name") ?: "",
                date = o.optJSONObject("commit")?.optJSONObject("author")?.safe("date") ?: ""
            ))
        }
        return out
    }

    fun contributors(ctx: Context, owner: String, name: String): List<ContributorInfo> {
        val arr = org.json.JSONArray(get(ctx, "$API/repos/$owner/$name/contributors?per_page=50"))
        val out = ArrayList<ContributorInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(ContributorInfo(o.safe("login"), o.safe("avatar_url"),
                o.optInt("contributions")))
        }
        return out
    }

    /* ---------- Actions ---------- */

    data class WorkflowInfo(val id: Long, val name: String, val state: String, val path: String)
    data class RunInfo(
        val id: Long, val name: String, val status: String, val conclusion: String,
        val branch: String, val event: String, val createdAt: String, val number: Int
    )

    fun workflows(ctx: Context, owner: String, name: String): List<WorkflowInfo> {
        val o = JSONObject(get(ctx, "$API/repos/$owner/$name/actions/workflows"))
        val arr = o.optJSONArray("workflows") ?: org.json.JSONArray()
        val out = ArrayList<WorkflowInfo>()
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            out.add(WorkflowInfo(w.optLong("id"), w.safe("name"), w.safe("state"), w.safe("path")))
        }
        return out
    }

    fun runs(ctx: Context, owner: String, name: String): List<RunInfo> {
        val o = JSONObject(get(ctx,
            "$API/repos/$owner/$name/actions/runs?per_page=30"))
        val arr = o.optJSONArray("workflow_runs") ?: org.json.JSONArray()
        val out = ArrayList<RunInfo>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out.add(RunInfo(r.optLong("id"), r.safe("name"), r.safe("status"),
                r.safe("conclusion"), r.safe("head_branch"), r.safe("event"),
                r.safe("created_at"), r.optInt("run_number")))
        }
        return out
    }

    /* ---------- API 配额 ---------- */

    data class RateLimit(val limit: Int, val remaining: Int, val resetAt: Long)

    fun rateLimit(ctx: Context): RateLimit {
        val o = JSONObject(get(ctx, "$API/rate_limit"))
        val core = o.optJSONObject("rate") ?: o.optJSONObject("resources")?.optJSONObject("core")
        return RateLimit(
            limit = core?.optInt("limit") ?: 0,
            remaining = core?.optInt("remaining") ?: 0,
            resetAt = core?.optLong("reset")?.times(1000) ?: 0L
        )
    }

    /* ---------- Issue / PR 详情与评论 ---------- */

    data class CommentInfo(
        val id: Long,
        val user: String,
        val avatar: String,
        val body: String,
        val createdAt: String
    ) {
        companion object {
            fun from(o: JSONObject): CommentInfo = CommentInfo(
                id = o.optLong("id"),
                user = o.optJSONObject("user")?.safe("login") ?: "",
                avatar = o.optJSONObject("user")?.safe("avatar_url") ?: "",
                body = o.safe("body"),
                createdAt = o.safe("created_at")
            )
        }
    }

    data class IssueDetail(
        val number: Int,
        val title: String,
        val body: String,
        val state: String,
        val user: String,
        val userAvatar: String,
        val createdAt: String,
        val comments: Int,
        val labels: List<String>,
        val isPr: Boolean
    )

    /** 取 issue / PR 详情（两者在 GitHub 上是同一个接口，PR 多几个字段） */
    fun issueDetail(ctx: Context, owner: String, name: String, number: Int): IssueDetail {
        val o = JSONObject(get(ctx, "$API/repos/$owner/$name/issues/$number"))
        val arr = o.optJSONArray("labels")
        val ls = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { o2 ->
                if (!o2.isNull("name")) { val v = o2.optString("name", ""); if (v.isNotEmpty()) ls.add(v) }
            }
        }
        return IssueDetail(
            number = o.optInt("number"),
            title = o.safe("title"),
            body = o.safe("body"),
            state = o.safe("state"),
            user = o.optJSONObject("user")?.safe("login") ?: "",
            userAvatar = o.optJSONObject("user")?.safe("avatar_url") ?: "",
            createdAt = o.safe("created_at"),
            comments = o.optInt("comments"),
            labels = ls,
            isPr = o.has("pull_request")
        )
    }

    fun comments(ctx: Context, owner: String, name: String, number: Int): List<CommentInfo> {
        val arr = org.json.JSONArray(get(ctx,
            "$API/repos/$owner/$name/issues/$number/comments?per_page=100"))
        val out = ArrayList<CommentInfo>()
        for (i in 0 until arr.length()) out.add(CommentInfo.from(arr.getJSONObject(i)))
        return out
    }

    /** 发评论。需要令牌，且该 issue 未锁定 */
    fun addComment(ctx: Context, owner: String, name: String, number: Int, body: String): Boolean {
        val json = JSONObject().put("body", body).toString()
        write("POST", "$API/repos/$owner/$name/issues/$number/comments", json, ctx)
        return true
    }

    /** 关闭 / 重开 issue */
    fun setIssueState(ctx: Context, owner: String, name: String, number: Int, open: Boolean): Boolean {
        val st = if (open) "open" else "closed"
        write("PATCH", "$API/repos/$owner/$name/issues/$number",
            JSONObject().put("state", st).toString(), ctx)
        return true
    }

    /* ---------- 写操作 ---------- */

    /** 新建仓库。返回 full_name */
    fun createRepo(
        ctx: Context, name: String, description: String,
        private: Boolean, autoInit: Boolean,
        gitignore: String = "", license: String = ""
    ): RepoInfo {
        val b = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", private)
            .put("auto_init", autoInit)
        if (gitignore.isNotBlank()) b.put("gitignore_template", gitignore)
        if (license.isNotBlank()) b.put("license_template", license)
        val s = write("POST", "$API/user/repos", b.toString(), ctx)
        return RepoInfo.from(JSONObject(s))
    }

    /** 上传文件到仓库路径（contents API，需 base64） */
    fun putFile(
        ctx: Context, owner: String, name: String, path: String,
        contentBase64: String, message: String, branch: String
    ): Boolean {
        val b = JSONObject()
            .put("message", message)
            .put("content", contentBase64)
        if (branch.isNotBlank()) b.put("branch", branch)
        write("PUT", "$API/repos/$owner/$name/contents/${path.trim('/')}",
            b.toString(), ctx)
        return true
    }

    /** 创建 Release（不带附件；附件要走 uploads 接口，另做） */
    fun createRelease(
        ctx: Context, owner: String, name: String,
        tag: String, title: String, body: String,
        draft: Boolean, prerelease: Boolean
    ): Boolean {
        val b = JSONObject()
            .put("tag_name", tag)
            .put("name", title)
            .put("body", body)
            .put("draft", draft)
            .put("prerelease", prerelease)
        write("POST", "$API/repos/$owner/$name/releases", b.toString(), ctx)
        return true
    }

    /* ---------- 关注 / 取关 ---------- */

    fun isFollowing(ctx: Context, login: String): Boolean = try {
        Http.request("GET", "$API/user/following/$login", null, headers(ctx))
        true
    } catch (e: Throwable) {
        if (e is ApiError && e.code == 404) false else throw e
    }

    fun follow(ctx: Context, login: String): Boolean = try {
        write("PUT", "$API/user/following/$login", "", ctx); true
    } catch (e: Throwable) { false }

    fun unfollow(ctx: Context, login: String): Boolean = try {
        write("DELETE", "$API/user/following/$login", null, ctx)
        true
    } catch (e: Throwable) { false }

    /** Star / 取消 Star */
    fun star(ctx: Context, owner: String, name: String): Boolean = try {
        write("PUT", "$API/user/starred/$owner/$name", "", ctx); true
    } catch (e: Throwable) { false }

    fun unstar(ctx: Context, owner: String, name: String): Boolean = try {
        write("DELETE", "$API/user/starred/$owner/$name", null, ctx)
        true
    } catch (e: Throwable) { false }

    fun isStarred(ctx: Context, owner: String, name: String): Boolean = try {
        Http.request("GET", "$API/user/starred/$owner/$name", null, headers(ctx))
        true
    } catch (e: Throwable) {
        if (e is ApiError && e.code == 404) false else throw e
    }

    /* ---------- 仓库页操作：Star / 关注 / Fork ---------- */

    /** 是否已订阅（watch）。GET 200=已订阅 404=未订阅 */
    fun isWatching(ctx: Context, owner: String, name: String): Boolean = try {
        Http.request("GET", "$API/repos/$owner/$name/subscription", null, headers(ctx))
        true
    } catch (e: Throwable) {
        if (e is ApiError && e.code == 404) false else false
    }

    /** 关注 / 取关仓库。PUT 订阅，DELETE 取消 */
    fun setWatching(ctx: Context, owner: String, name: String, on: Boolean): Boolean = try {
        val b = if (on) JSONObject().put("subscribed", true)
            .put("ignored", false).toString() else null
        write(if (on) "PUT" else "DELETE",
            "$API/repos/$owner/$name/subscription", b, ctx)
        true
    } catch (e: Throwable) { false }

    /** Fork。返回新仓库 full_name；失败返回 null */
    fun forkRepo(ctx: Context, owner: String, name: String): String? = try {
        val s = write("POST", "$API/repos/$owner/$name/forks", "{}", ctx)
        JSONObject(s).safe("full_name").ifBlank { null }
    } catch (e: Throwable) { null }

    /* ---------- 个人资料编辑 ---------- */

    /**
     * PATCH /user —— 对齐 page-user.js editProfile 提交的那份 payload。
     * 空串要传 null（GitHub 用 null 表示"清空"，空串会留下空值）。
     */
    fun updateProfile(
        ctx: Context,
        name: String?, bio: String?, blog: String?,
        company: String?, location: String?,
        hireable: Boolean,
        social: Map<String, String>
    ): Boolean {
        val b = JSONObject()
        b.put("name", if (name.isNullOrBlank()) null else name)
        b.put("bio", if (bio.isNullOrBlank()) null else bio)
        b.put("blog", if (blog.isNullOrBlank()) null else blog)
        b.put("company", if (company.isNullOrBlank()) null else company)
        b.put("location", if (location.isNullOrBlank()) null else location)
        b.put("hireable", hireable)
        val arr = org.json.JSONArray()
        social.forEach { (k, v) ->
            if (v.isNotBlank()) arr.put(JSONObject().put("provider", k).put("url", v))
        }
        b.put("social_accounts", arr)
        write("PATCH", "$API/user", b.toString(), ctx)
        return true
    }

    /* ---------- 更新检测（对齐 updater.js） ---------- */

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
        val notes: String
    )

    /**
     * 两条路并行：先试 releases/latest，取不到再读 version.json。
     * 与 updater.js 的策略一致。
     */
    fun checkUpdate(ctx: Context, repo: String = "Buwrt/githup"): UpdateInfo? {
        try {
            val o = JSONObject(get(ctx, "$API/repos/$repo/releases/latest"))
            val tag = o.safe("tag_name").removePrefix("v")
            val aa = o.optJSONArray("assets")
            if (aa != null) for (i in 0 until aa.length()) {
                val a = aa.getJSONObject(i)
                val url = a.safe("browser_download_url")
                if (url.endsWith(".apk")) {
                    return UpdateInfo(tag.ifBlank { o.safe("name") }, url,
                        a.optLong("size"), "", o.safe("body"))
                }
            }
            if (tag.isNotBlank()) return UpdateInfo(tag, o.safe("html_url"),
                0L, "", o.safe("body"))
        } catch (e: Throwable) { /* 落到 version.json */ }

        return try {
            val s = get(ctx, "https://raw.githubusercontent.com/$repo/main/version.json")
            val o = JSONObject(s)
            UpdateInfo(o.safe("version"), o.safe("apk"),
                o.optLong("size"), o.safe("sha256"), o.safe("notes"))
        } catch (e: Throwable) { null }
    }

    /**
     * 版本比较，返回：0=无更新 1=可选(z位变化) 2=强制(x或y位变化) -1=无法比较
     * 对齐 README 里的 x.y.z 规则。
     */
    fun compareVersion(cur: String, remote: String): Int {
        fun parts(v: String): List<Int>? {
            val t = v.trim().removePrefix("v")
            val ps = t.split('.')
            if (ps.size != 3) return null
            return try { ps.map { it.toInt() } } catch (e: Throwable) { null }
        }
        val a = parts(cur) ?: return -1
        val b = parts(remote) ?: return -1
        if (b[0] != a[0]) return 2
        if (b[1] != a[1]) return 2
        return if (b[2] > a[2]) 1 else 0
    }

    /* ---------- 探索（trending） ---------- */

    /**
     * 探索页：与网页版 P.explore 同参数 ——
     *   range=daily 取最近 7 天，weekly 30 天，monthly 120 天
     *   q = stars:>N pushed:>日期 [+ language:X]，按 stars 排序
     */
    fun trending(ctx: Context, range: String, lang: String): List<RepoInfo> {
        val d = java.util.Calendar.getInstance()
        when (range) {
            "weekly" -> d.add(java.util.Calendar.DAY_OF_MONTH, -30)
            "monthly" -> d.add(java.util.Calendar.DAY_OF_MONTH, -120)
            else -> d.add(java.util.Calendar.DAY_OF_MONTH, -7)
        }
        val since = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(d.time)
        var q = "stars:>20000 pushed:>$since"
        if (lang.isNotBlank()) q += " language:" + lang
        val url = "$API/search/repositories?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") +
            "&sort=stars&order=desc&per_page=30"
        val o = JSONObject(get(ctx, url))
        val arr = o.optJSONArray("items") ?: org.json.JSONArray()
        val out = ArrayList<RepoInfo>()
        for (i in 0 until arr.length()) out.add(RepoInfo.from(arr.getJSONObject(i)))
        return out
    }
}
