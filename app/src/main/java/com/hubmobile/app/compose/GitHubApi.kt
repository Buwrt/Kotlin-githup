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
                fullName = o.optString("full_name"),
                ownerLogin = owner?.optString("login") ?: "",
                ownerAvatarUrl = owner?.optString("avatar_url") ?: "",
                description = o.optString("description"),
                stars = o.optInt("stargazers_count"),
                forks = o.optInt("forks_count"),
                watchers = o.optInt("subscribers_count"),
                openIssues = o.optInt("open_issues_count"),
                language = o.optString("language"),
                license = lic?.optString("spdx_id") ?: "",
                defaultBranch = o.optString("default_branch"),
                updatedAt = o.optString("updated_at"),
                private = o.optBoolean("private"),
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
                    name = o.optString("name"),
                    path = o.optString("path"),
                    isDir = "dir" == o.optString("type"),
                    size = o.optLong("size"),
                    sha = o.optString("sha")
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
                login = o.optString("login"),
                name = o.optString("name"),
                avatarUrl = o.optString("avatar_url"),
                bio = o.optString("bio"),
                publicRepos = o.optInt("public_repos"),
                followers = o.optInt("followers"),
                following = o.optInt("following"),
                htmlUrl = o.optString("html_url")
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
            name = o.optString("name"),
            path = o.optString("path"),
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
                    arr.optJSONObject(i)?.optString("name")?.let { if (it.isNotEmpty()) ls.add(it) }
                }
                return IssueInfo(
                    number = o.optInt("number"),
                    title = o.optString("title"),
                    state = o.optString("state"),
                    user = o.optJSONObject("user")?.optString("login") ?: "",
                    comments = o.optInt("comments"),
                    createdAt = o.optString("created_at"),
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
}
