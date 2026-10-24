package com.hubmobile.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 在 App 内部把「一串英文字母和数字」变成真正的 Android 签名密钥。
 *
 * 用户不想为了签名去电脑上敲 keytool，所以这里直接用 JDK/Android 自带的
 * 密码学 API 生成 RSA 密钥对与自签名证书，打包成 PKCS12 keystore。
 * 整个项目零第三方依赖，所以 X.509 证书是手写 ASN.1 DER 编码出来的。
 *
 * 关键点：**同一串输入一定得到同一把钥匙**。
 * 密钥生成用的随机数由输入派生（SHA-256 后作为种子），因此只要用户记得
 * 那串字符，就能在任何时候重建出完全一样的 keystore，打出来的包签名一致、
 * 可以覆盖安装——这正是「是否使用同一个签名」要的效果。
 *
 * Kotlin 迁移说明 ——
 *   这里是逐字节的 DER 编码，属于「转错一个运算符就完全跑不起来」的代码。
 *   迁移后必须跑一次回归：同一个 seed 生成两次 keystore，两份字节必须完全
 *   相等（长度、SHA-256 都一致）；并且生成出来的 keystore 要能真的给
 *   APK 签名成功。tools/keytool-parity.sh 就是干这个的。
 */
class KeyTool private constructor() {

    companion object {

        private const val OID_RSA_ENCRYPTION = "1.2.840.113549.1.1.1"
        private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
        private const val KEY_SIZE = 2048

        /**
         * 有效期必须是固定值：一旦用当前时间，同一串输入每次生成的证书都不一样，
         * 签名也就跟着变，用户就拿不到「同一个签名」。
         * 起点固定在 2020-01-01（早于任何构建时间，不会出现"证书尚未生效"），
         * 终点 2049-12-31（UTCTime 最多能表示到 2049）。
         */
        private const val VALID_FROM = 1577836800000L
        private const val VALID_UNTIL = 2524521600000L

        /**
         * 生成 keystore（PKCS12）。
         *
         * @param seed      用户输入的字母数字，决定钥匙本身
         * @param alias     密钥别名
         * @param storePass 密钥库密码
         * @param keyPass   密钥密码
         * @return PKCS12 二进制内容
         */
        @JvmStatic
        @Throws(Exception::class)
        fun makeKeystore(seed: String, alias: String, storePass: String?, keyPass: String?): ByteArray {
            val kp = generateKeyPair(seed)
            val encoded = selfSignedCertificate(kp, serial(seed))
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, storePass?.toCharArray())
            ks.setKeyEntry(
                alias,
                kp.private,
                keyPass?.toCharArray(),
                arrayOf<Certificate>(cert)
            )
            val out = ByteArrayOutputStream()
            ks.store(out, storePass?.toCharArray())
            return out.toByteArray()
        }

        /** 由输入派生确定性密钥对：同样的输入 → 同样的钥匙 */
        @Throws(Exception::class)
        private fun generateKeyPair(seed: String): KeyPair {
            val rnd = SecureRandom.getInstance("SHA1PRNG")
            rnd.setSeed(sha256(seed))
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(RSAKeyGenParameterSpec(KEY_SIZE, RSAKeyGenParameterSpec.F4), rnd)
            return kpg.generateKeyPair()
        }

        /** 证书序列号也由输入派生，保证可复现 */
        private fun serial(seed: String): BigInteger =
            BigInteger(1, sha256("serial:" + seed)).abs().add(BigInteger.ONE)

        private fun sha256(s: String): ByteArray {
            return try {
                MessageDigest.getInstance("SHA-256").digest(s.toByteArray(charset("UTF-8")))
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        /* ---------------- X.509 手写 DER 编码 ---------------- */

        @Throws(Exception::class)
        private fun selfSignedCertificate(kp: KeyPair, serial: BigInteger): ByteArray {
            val pubInfo = subjectPublicKeyInfo(kp)
            val name = x500Name()

            val tbs = ByteArrayOutputStream()
            tbs.write(tlv(0xA0, integer(BigInteger.valueOf(2))))   // version = v3
            tbs.write(integer(serial))                              // serialNumber
            tbs.write(algorithmIdentifier(OID_SHA256_RSA))          // signature
            tbs.write(name)                                         // issuer
            tbs.write(validity())                                   // validity
            tbs.write(name)                                         // subject（自签名）
            tbs.write(pubInfo)                                      // subjectPublicKeyInfo

            val tbsBytes = tlv(0x30, tbs.toByteArray())

            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(kp.private)
            sig.update(tbsBytes)
            val signed = sig.sign()

            val cert = ByteArrayOutputStream()
            cert.write(tbsBytes)
            cert.write(algorithmIdentifier(OID_SHA256_RSA))
            cert.write(tlv(0x03, concat(byteArrayOf(0x00), signed)))   // BIT STRING
            return tlv(0x30, cert.toByteArray())
        }

        @Throws(Exception::class)
        private fun subjectPublicKeyInfo(kp: KeyPair): ByteArray {
            val pub = kp.public as RSAPublicKey
            val key = ByteArrayOutputStream()
            key.write(integer(pub.modulus))
            key.write(integer(pub.publicExponent))
            val alg = algorithmIdentifier(OID_RSA_ENCRYPTION)
            return tlv(
                0x30,
                concat(alg, tlv(0x03, concat(byteArrayOf(0x00), tlv(0x30, key.toByteArray()))))
            )
        }

        private fun x500Name(): ByteArray {
            val cn = rdn("2.5.4.3", "githup")
            val o = rdn("2.5.4.10", "githup")
            val ou = rdn("2.5.4.11", "Mobile")
            val c = rdn("2.5.4.6", "CN")
            return tlv(0x30, concat(c, o, ou, cn))
        }

        private fun rdn(oid: String, value: String): ByteArray {
            val v = tlv(0x0C, utf8(value))
            return tlv(0x31, tlv(0x30, concat(tlv(0x06, oidBytes(oid)), v)))
        }

        private fun validity(): ByteArray {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            val nb = f.format(Date(VALID_FROM))
            val na = f.format(Date(VALID_UNTIL))
            return tlv(0x30, concat(tlv(0x17, utf8(nb)), tlv(0x17, utf8(na))))
        }

        private fun algorithmIdentifier(oid: String): ByteArray =
            tlv(0x30, concat(tlv(0x06, oidBytes(oid)), byteArrayOf(0x05.toByte(), 0x00.toByte())))

        private fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

        /** TLV：tag + 长度 + 内容 */
        private fun tlv(tag: Int, value: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(tag)
            val n = value.size
            when {
                n < 128 -> o.write(n)
                n < 256 -> { o.write(0x81); o.write(n) }
                n < 65536 -> { o.write(0x82); o.write(n shr 8); o.write(n) }
                else -> { o.write(0x83); o.write(n shr 16); o.write(n shr 8); o.write(n) }
            }
            o.write(value, 0, value.size)
            return o.toByteArray()
        }

        private fun oidBytes(dotted: String): ByteArray {
            val parts = dotted.split(".")
            val o = ByteArrayOutputStream()
            o.write(parts[0].toInt() * 40 + parts[1].toInt())
            for (i in 2 until parts.size) {
                val v = parts[i].toInt()
                if (v < 128) {
                    o.write(v)
                } else {
                    // base-128：每字节低 7 位存数据，最高位置 1 表示还有后续
                    var shift = 28
                    while ((v ushr shift) and 0x7F == 0 && shift > 0) shift -= 7
                    while (shift > 0) {
                        o.write(((v ushr shift) and 0x7F) or 0x80)
                        shift -= 7
                    }
                    o.write(v and 0x7F)
                }
            }
            return o.toByteArray()
        }

        private fun concat(vararg parts: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            for (p in parts) o.write(p, 0, p.size)
            return o.toByteArray()
        }

        private fun utf8(s: String): ByteArray = s.toByteArray(charset("UTF-8"))
    }
}
