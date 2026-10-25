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
    val topics: List<String>
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
                topics = topics
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
    fun loadBitmap(ctx: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val raw = Http.requestRaw("GET", url, headers(ctx))
            val b = raw.body ?: return null
            if (raw.code !in 200..299) return null
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Throwable) {
            null
        }
    }

    data class UserInfo(
        val login: String,
        val name: String,
        val avatarUrl: String,
        val bio: String,
        val publicRepos: Int,
        val followers: Int,
        val following: Int,
        val htmlUrl: String
    ) {
        companion object {
            fun from(o: JSONObject): UserInfo = UserInfo(
                login = o.safe("login"),
                name = o.safe("name"),
                avatarUrl = o.safe("avatar_url"),
                bio = o.safe("bio"),
                publicRepos = o.optInt("public_repos"),
                followers = o.optInt("followers"),
                following = o.optInt("following"),
                htmlUrl = o.safe("html_url")
            )
        }
    }

    /** 当前登录者；没令牌时返回 null（不抛异常，好让 UI 显示"未登录"） */
    fun me(ctx: Context): UserInfo? = try {
        UserInfo.from(JSONObject(get(ctx, "$API/user")))
    } catch (e: Throwable) {
        null
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
