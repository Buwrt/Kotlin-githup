package com.hubmobile.ui.screens

import com.hubmobile.ui.components.IssueState

/** 仓库 —— 对应仓库列表行（repoRow）需要的字段 */
data class Repo(
    val owner: String,
    val name: String,
    val desc: String = "",
    val lang: String = "",
    val stars: Int = 0,
    val forks: Int = 0,
    val updated: String = "",
    val isPrivate: Boolean = false,
    val starred: Boolean = false,
    val avatar: String = ""
) {
    val fullName: String get() = "$owner/$name"
}

/** 通知 */
data class NotificationItem(
    val title: String,
    val repo: String,
    val kind: NotifyKind,
    val time: String,
    val unread: Boolean = true
)

enum class NotifyKind { Issue, PullRequest, Release, Mention, Discussion }

/** 动态流事件 */
data class FeedEvent(
    val actor: String,
    val action: String,
    val repo: String,
    val time: String,
    val kind: NotifyKind
)

/** 探索 / 趋势 */
data class TrendingRepo(
    val rank: Int,
    val repo: Repo,
    val starsToday: String,
    val builders: String = ""
)

/** 搜索结果类型，与 .seg 的三段一致 */
enum class SearchType(val label: String) { Repo("仓库"), Issue("议题"), User("用户") }

enum class RepoSort(val label: String) { Updated("最近更新"), Stars("星标数"), Name("名称") }

/** Issue / PR 列表项 */
data class IssueItem(
    val number: Int,
    val title: String,
    val state: IssueState,
    val author: String,
    val comments: Int = 0,
    val time: String = ""
)

/* ---------------- 示例数据：仅为把 UI 撑起来，接真数据时整体替换 ---------------- */

object Sample {
    val me = com.hubmobile.ui.screens.User(
        login = "Buwrt",
        name = "Buwrt",
        bio = "做点小而快的东西",
        followers = 128,
        following = 42,
        publicRepos = 17
    )

    val repos = listOf(
        Repo("Buwrt", "githup", "约 350KB 的轻量 Android 第三方 GitHub 客户端", "Kotlin", 320, 24, "2 小时前"),
        Repo("Buwrt", "githup-release", "发布用仓库：未加固源码 + 签名密钥", "Java", 12, 1, "1 天前", isPrivate = true),
        Repo("google", "dagu", "DAG 工作流引擎", "Go", 8200, 410, "3 小时前"),
        Repo("JetBrains", "compose-multiplatform", "Compose Multiplatform", "Kotlin", 16800, 1450, "5 小时前"),
        Repo("square", "okhttp", "Square 的 HTTP 客户端", "Kotlin", 47000, 9100, "昨天"),
        Repo("facebook", "react-native", "用 React 写原生 App", "JavaScript", 118000, 24000, "昨天")
    )

    val notifications = listOf(
        NotificationItem("修复底栏在部分机型上离家出走", "Buwrt/githup", NotifyKind.Issue, "10 分钟前"),
        NotificationItem("Actions 日志改为 App 内直接查看", "Buwrt/githup", NotifyKind.PullRequest, "1 小时前"),
        NotificationItem("v1.2.16", "Buwrt/githup", NotifyKind.Release, "3 小时前"),
        NotificationItem("有人在评论里提到了你", "Buwrt/githup", NotifyKind.Mention, "昨天", unread = false)
    )

    val feed = listOf(
        FeedEvent("Buwrt", "推送了 3 个提交到", "Buwrt/githup", "10 分钟前", NotifyKind.PullRequest),
        FeedEvent("someone", "创建了议题", "Buwrt/githup", "1 小时前", NotifyKind.Issue),
        FeedEvent("Buwrt", "发布了", "Buwrt/githup", "3 小时前", NotifyKind.Release),
        FeedEvent("dev", "star 了", "square/okhttp", "昨天", NotifyKind.Discussion)
    )

    val trending = listOf(
        TrendingRepo(1, Repo("acme", "awesome-llm", "LLM 应用合集", "Python", 42000, 3800, "今天"), "1.2k stars today", "12 位开发者"),
        TrendingRepo(2, Repo("acme", "swift-ui-kit", "iOS 组件库", "Swift", 9800, 620, "今天"), "860 stars today", "5 位开发者"),
        TrendingRepo(3, Repo("acme", "tiny-http", "单文件 HTTP 服务", "Rust", 5400, 210, "今天"), "410 stars today", "3 位开发者")
    )

    val issues = listOf(
        IssueItem(128, "底栏在朴素样式下离屏幕底边过远", IssueState.Open, "someone", 4, "2 天前"),
        IssueItem(126, "Actions 日志希望支持关键字过滤", IssueState.Closed, "dev", 2, "上周"),
        IssueItem(121, "新增整页翻译", IssueState.Merged, "Buwrt", 9, "上周")
    )
}

data class User(
    val login: String,
    val name: String,
    val bio: String = "",
    val followers: Int = 0,
    val following: Int = 0,
    val publicRepos: Int = 0,
    val avatar: String = ""
)
