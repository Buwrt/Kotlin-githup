package com.hubmobile.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

/**
 * 签名自校验：确保跑起来的是「你自己签的那个包」。
 *
 * 为什么需要它 ——
 *   别人把你开源出来的代码重新打个包、改掉几个字、甚至塞点广告或偷令牌的代码，
 *   再用他自己的密钥签名，就得到一个「看起来一样」的 APP。如果他能骗用户装上，
 *   损失的是你的口碑，用户丢的是数据。
 *
 * 它是怎么拦住的 ——
 *   签名是 APK 的一部分，改了包内容签名就对不上，重签又必然换密钥。
 *   所以只要在运行时比对「当前安装包的签名指纹」与「写死在代码里的那个指纹」，
 *   不一致就说明这个包不是你发的 —— 这时拒绝继续运行。
 *
 * 诚实说明它的边界 ——
 *   这不是不可破解的：懂行的人可以反编译、把这段校验删掉、再重新签名。
 *   它挡的是「拿源码随手改一改就发个盗版」这类低成本抄袭，
 *   以及「官方包被二次打包后重签」的常见路径。真正的恶意逆向挡不住，
 *   任何客户端都挡不住 —— 这是加密学上的限制，不是实现问题。
 *
 * 和 githup（加固版）里那份的关系 ——
 *   逻辑是同一份，只有两处不同：
 *     1. 调试判定用 BuildConfig.DEBUG（本库没有 DEBUG_BUILD 这个自定义字段）
 *     2. 校验失败时由调用方决定怎么提示（本库没有 BlockedActivity）
 *
 * Kotlin 迁移说明 ——
 *   这是安全相关代码，迁移后必须做一次真机验证：拿 debug 包装一次（应放行）、
 *   拿 release 包装一次（应与 EXPECTED_SHA256 比对通过）。
 *   指纹字符串一个字符都不能错，下面那个常量请照抄，不要用格式化工具重排。
 */
class SignCheck private constructor() {

    companion object {

        /**
         * 正式签名的证书 SHA-256 指纹（小写、无冒号）。
         * 换密钥时这里必须同步更新，否则 App 会拒绝运行。
         *
         * 这个值不是猜的：用 apksigner 从真正发出去的包里读出来的 ——
         *   apksigner verify --print-certs githup-1.1.5.apk
         *   Signer #1 certificate SHA-256 digest: 863dd1cd...
         */
        private const val EXPECTED_SHA256 =
            "863dd1cd3752e59165e152bc4ab35fde478fcd296d724a6bc58cec0368184927"

        /** 官方签名证书的 SHA-256，供更新安装 / 关于页展示时参考 */
        @JvmStatic
        fun officialCertSha256(): String = EXPECTED_SHA256

        /**
         * 当前安装包的签名是否为官方签名。
         * 调试构建（debug 签名）一律放行 —— 那是开发时自己用的。
         */
        @JvmStatic
        fun isOfficial(ctx: Context): Boolean {
            if (BuildConfig.DEBUG) return true
            val actual = signingSha256(ctx)
            // 读不到签名信息：宁可当成异常，也不放行
            if (actual == null || actual.isEmpty()) return false
            return actual == EXPECTED_SHA256
        }

        /** 取当前安装包签名证书的 SHA-256（小写十六进制，无分隔符） */
        @JvmStatic
        fun signingSha256(ctx: Context): String? {
            return try {
                val pm = ctx.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pi: PackageInfo = pm.getPackageInfo(
                        ctx.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                    /*
                     * signingInfo 在部分系统上是可空/取不到的，别直接点下去：
                     * 空指针会一路抛到启动校验里，表现是「一开 App 就闪退」。
                     * 先取到局部变量再判空 —— 直接写 pi.signingInfo.xxx 时
                     * Kotlin 会报「可变属性无法智能转换」，取局部变量顺手也解决了。
                     */
                    val si = pi.signingInfo ?: return null
                    val arr: Array<Signature>? = if (si.hasMultipleSigners()) {
                        si.apkContentsSigners
                    } else {
                        si.signingCertificateHistory
                    }
                    if (arr == null || arr.isEmpty()) null else sha256(arr[0])
                } else {
                    @Suppress("DEPRECATION")
                    val old = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                    val legacy: Array<Signature>? = old.signatures
                    if (legacy == null || legacy.isEmpty()) null
                    else sha256(legacy[0])
                }
            } catch (t: Throwable) {
                null
            }
        }

        private fun sha256(sig: Signature): String? {
            return try {
                val d = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                val sb = StringBuilder(d.size * 2)
                for (b in d) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
                sb.toString()
            } catch (t: Throwable) {
                null
            }
        }
    }
}
