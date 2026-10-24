package com.hubmobile.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore 中的 AES-GCM 密钥加密敏感数据（访问令牌），
 * 密文以 Base64 形式存放在私有 SharedPreferences 中。
 *
 * Kotlin 迁移说明 ——
 *   get / put 是 synchronized 的静态方法。Kotlin 里没有 static synchronized，
 *   用 @JvmStatic 加 @Synchronized（锁的是 companion 实例，语义等价：
 *   Java 静态同步锁的是 Class 对象，Kotlin 的 @Synchronized on companion
 *   方法锁的是 Companion 实例；本类的所有写操作都走这两个入口，
 *   不存在「一个锁 Class 一个锁 Companion」的交叉场景，因此等价成立）。
 */
class SecurePrefs private constructor() {

    companion object {

        private const val KS = "AndroidKeyStore"
        private const val ALIAS = "hubmobile_aes_key"
        private const val PREF = "hub_secure"
        private const val IV_SEP = "]"

        private fun getKey(): SecretKey? {
            return try {
                val ks = KeyStore.getInstance(KS)
                ks.load(null)
                if (ks.containsAlias(ALIAS)) {
                    val entry = ks.getEntry(ALIAS, null)
                    if (entry is KeyStore.SecretKeyEntry) {
                        return entry.secretKey
                    }
                }
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // 保持与迁移前的 Java 版一致：无条件调用。
                    // 这个方法是 API 28 才有的，在 24~27 的机器上会抛
                    // NoSuchMethodError —— 被外层 try 接住后 getKey() 返回 null，
                    // 于是令牌退回明文存储。这是原实现的行为，本次迁移不改它。
                    // （真要修是另一个单独的决定，别夹在语言迁移里做。）
                    .setRandomizedEncryptionRequired(true)
                    .build()
                kg.init(spec)
                kg.generateKey()
            } catch (e: Exception) {
                null
            }
        }

        @JvmStatic
        @Synchronized
        fun get(ctx: Context, key: String, def: String): String {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val raw = sp.getString(key, null) ?: return def
            if (!raw.contains(IV_SEP)) return raw   // 兼容早期明文
            return try {
                val sk = getKey() ?: return def
                val parts = raw.split("\\]".toRegex())
                val iv = Base64.decode(parts[0], Base64.DEFAULT)
                val data = Base64.decode(parts[1], Base64.DEFAULT)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, iv))
                String(c.doFinal(data), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                def
            }
        }

        @JvmStatic
        @Synchronized
        fun put(ctx: Context, key: String, value: String?) {
            val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (value == null) {
                sp.edit().remove(key).apply()
                return
            }
            try {
                val sk = getKey()
                if (sk != null) {
                    val iv = ByteArray(12)
                    SecureRandom().nextBytes(iv)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, iv))
                    val enc = c.doFinal(value.toByteArray(StandardCharsets.UTF_8))
                    val out = Base64.encodeToString(iv, Base64.DEFAULT) + IV_SEP +
                            Base64.encodeToString(enc, Base64.DEFAULT)
                    sp.edit().putString(key, out.replace("\n", "")).apply()
                    return
                }
            } catch (ignored: Exception) {
            }
            sp.edit().putString(key, value).apply()
        }

        @JvmStatic
        fun remove(ctx: Context, key: String) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(key).apply()
        }
    }
}
