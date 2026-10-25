package com.hubmobile.app.compose

import android.content.Context

/*
 * 设置存档 —— 对齐网页版 window.Store 里的 settings。
 *
 * 网页版那套存在 localStorage，原生版存在 SharedPreferences（私有、不备份）。
 * 字段与取值沿用网页版：
 *   theme     auto / light / dark
 *   codeFont  12 / 13 / 15 / 17（px）
 *   startTab  home / notifications / explore / profile
 */

object Prefs {
    private const val P = "hub_prefs"
    private const val K_THEME = "theme"
    private const val K_FONT = "codeFont"
    private const val K_START = "startTab"

    fun theme(ctx: Context): String = get(ctx, K_THEME, "auto")
    fun setTheme(ctx: Context, v: String) = put(ctx, K_THEME, v)

    fun codeFont(ctx: Context): Int = get(ctx, K_FONT, "13").toIntOrNull() ?: 13
    fun setCodeFont(ctx: Context, v: Int) = put(ctx, K_FONT, v.toString())

    fun startTab(ctx: Context): String = get(ctx, K_START, "home")
    fun setStartTab(ctx: Context, v: String) = put(ctx, K_START, v)

    private fun get(ctx: Context, k: String, def: String): String =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString(k, def) ?: def

    private fun put(ctx: Context, k: String, v: String) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(k, v).apply()
    }
}
