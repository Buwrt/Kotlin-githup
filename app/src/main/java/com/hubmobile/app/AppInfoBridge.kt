package com.hubmobile.app

import android.webkit.JavascriptInterface
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 「设置 → 关于」那一页要的几个自身信息：版本号、签名指纹、APK 指纹。
 *
 * 之所以单独拆出来：这几个方法彼此无关，但都是「问自己一句」，
 * 而且都不依赖前端传什么进来，放哪儿都容易找丢。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class AppInfoBridge(private val ctx: BridgeContext) {

    /** 应用版本号，供「设置 → 关于」显示。 */
    @JavascriptInterface
    fun appVersion(): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * 本机安装包的签名证书指纹（SHA-256，小写），供「设置 → 关于」显示。
     *
     * 让人能自己核对：这个包的签名是不是官方那个。读不到就返回空串 ——
     * 编一个假的比不显示更有害。
     */
    @JavascriptInterface
    fun certSha256(): String {
        return try {
            SignCheck.signingSha256(ctx.activity) ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * 本机已安装 APK 的 SHA-256 指纹。
     *
     * 用途：版本号冻结不变、但包里内容已经换过的情况下，
     * 前端靠比对这个指纹来判断「有没有新内容」。
     * 读不到就返回空串（前端会跳过指纹比对，不当成异常）。
     */
    @JavascriptInterface
    fun apkSha256(): String {
        return try {
            val ai = ctx.activity.packageManager
                .getApplicationInfo(ctx.activity.packageName, 0)
            val apk = File(ai.sourceDir)
            if (!apk.exists()) return ""
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            FileInputStream(apk).use { input ->
                var n: Int
                while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
            }
            val digest = md.digest()
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append(String.format("%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (ignored: Throwable) {
            ""
        }
    }
}
