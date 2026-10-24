package com.hubmobile.app

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP/HOTP 的实现。
 *
 * 为什么前端已经有一份 JS 实现，这里还要再写一份 ——
 *   后台常驻通知（TotpService）跑在原生侧，App 退到后台后 WebView 会被
 *   系统冻结，JS 根本算不了。要在通知栏持续刷新码，原生必须自己能算。
 *
 * 两份实现必须给出完全一致的结果（算法是 RFC 6238，本来也只有一种解法）。
 * 前端优先走 JsBridge 的 hmacSha1/hmacSha256（同样是这套代码），
 * 拿不到原生桥时才用纯 JS 兜底 —— 三条路殊途同归。
 *
 * Kotlin 迁移说明 ——
 *   这是纯算法，最容易转也最该先转：转完只要「同一把种子算出来的码」
 *   和转之前一模一样，就等于验证了整条 Kotlin 工具链没跑偏。
 *   迁移后请在真机上做一次对照：App 里的码和系统里另一个验证器 App 的码
 *   必须对得上，对不上说明位运算写错了（下面几处 ushr / and 0xff 是重点）。
 */
class Totp private constructor() {

    companion object {

        private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** Base32 解码（大小写不敏感，忽略空格与连字符） */
        @JvmStatic
        fun b32decode(input: String?): ByteArray? {
            if (input == null) return null
            val s = input.uppercase().replace(Regex("[\\s\\-_]"), "").replace(Regex("=+$"), "")
            if (s.isEmpty()) return null
            var bits = 0
            var value = 0
            var n = 0
            val tmp = ByteArray(s.length * 5 / 8 + 1)
            for (i in s.indices) {
                val idx = B32.indexOf(s[i])
                if (idx < 0) return null
                value = (value shl 5) or idx
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    tmp[n++] = ((value ushr bits) and 0xff).toByte()
                }
            }
            if (n == 0) return null
            return tmp.copyOf(n)
        }

        /** 把密钥规整成「同一把钥匙就是同一个字符串」的形态：
            转大写、去掉空格 / 连字符 / 下划线、去掉尾部补位等号。
            去重时用它做指纹 —— 不然「JBSW Y3DP」和「jbswy3dp」会被当成两个账户。 */
        @JvmStatic
        fun normalizeSecret(input: String?): String {
            if (input == null) return ""
            return input.uppercase().replace(Regex("[\\s\\-_]"), "").replace(Regex("=+$"), "")
        }

        /** 算一个账户当前的动态码；失败返回 null */
        @JvmStatic
        fun compute(acct: JSONObject?): String? {
            if (acct == null) return null
            val key = b32decode(acct.optString("secret", "")) ?: return null

            var period = acct.optInt("period", 30)
            if (period <= 0) period = 30
            var digits = acct.optInt("digits", 6)
            if (digits != 8) digits = 6
            val algo = acct.optString("algo", "SHA1")

            val counter = System.currentTimeMillis() / 1000L / period
            return hotp(key, counter, digits, algo)
        }

        /** 当前这一轮还剩几秒 */
        @JvmStatic
        fun remaining(acct: JSONObject?): Long {
            var period = acct?.optInt("period", 30) ?: 30
            if (period <= 0) period = 30
            val t = System.currentTimeMillis() / 1000L
            return period - (t % period)
        }

        /** 按「XXX XXX」分段，跟各家验证器显示一致，长串不容易看错位 */
        @JvmStatic
        fun group(code: String?): String {
            if (code == null || code.isEmpty()) return ""
            if (code.length == 6) return code.substring(0, 3) + " " + code.substring(3)
            if (code.length == 8) return code.substring(0, 4) + " " + code.substring(4)
            return code
        }

        /** RFC 4226：HMAC → 动态截断 → 取模 */
        private fun hotp(key: ByteArray, counter: Long, digits: Int, algo: String): String? {
            return try {
                /*
                 * 计数器写进 8 字节缓冲区的大端表示。
                 *
                 * 注意这里用 long 分高低两段写 —— 直接 i=7..0 循环右移在
                 * Java 里对 long 是可行的，但一旦有人把 counter 改成 int
                 * 就会踩到「>>> 32 等于不位移」的坑。分开写更不容易出错。
                 */
                val msg = ByteArray(8)
                var high = counter ushr 32
                var low = counter and 0xffffffffL
                for (i in 3 downTo 0) {
                    msg[i] = (high and 0xff).toByte()
                    high = high ushr 8
                }
                for (i in 7 downTo 4) {
                    msg[i] = (low and 0xff).toByte()
                    low = low ushr 8
                }

                val macAlgo = when {
                    "SHA256".equals(algo, ignoreCase = true) -> "HmacSHA256"
                    "SHA512".equals(algo, ignoreCase = true) -> "HmacSHA512"
                    else -> "HmacSHA1"
                }

                val mac = Mac.getInstance(macAlgo)
                mac.init(SecretKeySpec(key, macAlgo))
                val hash = mac.doFinal(msg)

                val offset = hash[hash.size - 1].toInt() and 0x0f
                val bin = ((hash[offset].toInt() and 0x7f) shl 24)
                        .or((hash[offset + 1].toInt() and 0xff) shl 16)
                        .or((hash[offset + 2].toInt() and 0xff) shl 8)
                        .or(hash[offset + 3].toInt() and 0xff)

                var mod = 1
                repeat(digits) { mod *= 10 }
                var s = (bin % mod).toString()
                while (s.length < digits) s = "0$s"
                s
            } catch (t: Throwable) {
                null
            }
        }

        /** SHA-256 十六进制（给「关于」页展示签名指纹之类的地方备用） */
        @JvmStatic
        fun sha256Hex(data: ByteArray?): String? {
            if (data == null) return null
            return try {
                val d = MessageDigest.getInstance("SHA-256").digest(data)
                val sb = StringBuilder(d.size * 2)
                for (b in d) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
                sb.toString()
            } catch (t: Throwable) {
                null
            }
        }
    }
}
