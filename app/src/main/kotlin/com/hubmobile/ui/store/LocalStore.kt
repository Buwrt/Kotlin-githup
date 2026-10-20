package com.hubmobile.ui.store

import android.content.Context

/**
 * 本机偏好与收藏 —— 这一版唯一"真会记住"的地方。
 *
 * 为什么先做这三件：它们完全不依赖网络，装上去就能验证"点了有反应、
 * 关掉再开还在"，而原项目那些仓库浏览、Issue/PR、Actions 都要先接
 * GitHub API 和令牌体系。先把本机这一层跑通，后面接网络时界面不用改。
 *
 * 只存字符串和布尔，用 SharedPreferences 就够了，不必上 DataStore。
 */
object LocalStore {

    private const val FILE = "githup_ui"
    private const val K_DARK = "dark"
    private const val K_NAV = "nav_style"
    private const val K_STAR = "starred"

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun p(): android.content.SharedPreferences =
        prefs ?: throw IllegalStateException("LocalStore 未初始化：先调 init(context)")

    /** 深色模式 */
    var dark: Boolean
        get() = p().getBoolean(K_DARK, false)
        set(v) { p().edit().putBoolean(K_DARK, v).apply() }

    /** 底栏样式：true = 悬浮玻璃胶囊，false = 贴底实心栏 */
    var glassNav: Boolean
        get() = p().getBoolean(K_NAV, true)
        set(v) { p().edit().putBoolean(K_NAV, v).apply() }

    /** 收藏（本地星标） */
    fun starred(): Set<String> =
        p().getStringSet(K_STAR, emptySet()) ?: emptySet()

    fun isStarred(fullName: String): Boolean = fullName in starred()

    fun toggleStar(fullName: String): Boolean {
        val set = starred().toMutableSet()
        val now = if (fullName in set) { set.remove(fullName); false } else { set.add(fullName); true }
        p().edit().putStringSet(K_STAR, set).apply()
        return now
    }
}
