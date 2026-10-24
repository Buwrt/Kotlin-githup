package com.hubmobile.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import java.security.SecureRandom
import java.util.HashSet
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 设备能力这一摊：两步验证器的原生侧支持、相机权限、随机数、HMAC、分享。
 *
 * 它们的共同点是「跟某个具体业务无关，只是借系统能力一用」——
 * 放在一块儿比散在 JsBridge 里好找。
 *
 * 【从 JsBridge 拆出】逻辑一字未改。
 */
internal class DeviceBridge(private val ctx: BridgeContext) {

    /** 扫一扫：正在等系统权限框结果的那次 JS 回调 id。 */
    private var pendingCameraId: String? = null

    /**
     * 两步验证器的账户列表同步到原生侧。
     *
     * 后台常驻通知（TotpService）要在 App 不可见时也算出动态码，
     * 而它读不到 WebView 里的数据 —— 所以每次前端改动账户，就顺手
     * 推一份 JSON 过来存着。存的是 SharedPreferences 私有区，
     * 不进日志、不对外暴露。
     */
    @JavascriptInterface
    fun totpSync(accountsJson: String?) {
        try {
            /*
             * 存之前先按「规整密钥 + issuer + name」去重。
             * 历史数据里同一账户可能被存两份（改过名字、重复导入），
             * 通知栏就会并排出现两行一模一样的码。
             */
            var cleaned = accountsJson ?: "[]"
            try {
                val arr = JSONArray(cleaned)
                val out = JSONArray()
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = o.optString("type", "totp")
                    val key = Totp.normalizeSecret(o.optString("secret", "")) +
                            "|" + type +
                            "|" + o.optInt("digits", 6) +
                            "|" + o.optInt("period", 30) +
                            "|" + o.optString("algo", "SHA1")
                    val full = if ("hotp" == type) key + "|" + o.optInt("counter", 0) else key
                    if (!seen.add(full)) continue // 已存过同一账户，跳过
                    out.put(o)
                }
                cleaned = out.toString()
            } catch (ignored: Throwable) {
                /* 解析失败就原样存，不丢用户数据 */
            }

            ctx.activity.getSharedPreferences("hub_prefs", Context.MODE_PRIVATE).edit()
                .putString("totp_accounts_cache", cleaned)
                .apply()
            // 顺手让通知服务按新清单重画一次（新增/删除账户后立刻生效）
            TotpService.refresh(ctx.activity)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 开关「后台常驻动态码通知」。
     *
     * 关掉时要把已经挂着的通知撤掉，否则用户关了开关、通知栏还留着一条，
     * 会以为没关成功。
     *
     * 开启时会顺带申请通知权限 —— Android 13 起没这个权限就发不出通知，
     * 不在这里要，用户打开开关后会看到一个「什么都没发生」的界面。
     */
    @JavascriptInterface
    fun totpSetBackground(on: Boolean) {
        try {
            if (on) requestNotificationPermission()
            TotpService.setEnabled(ctx.activity, on)
        } catch (ignored: Throwable) {
        }
    }

    /** 申请通知权限（Android 13+）。已授权或系统版本低就什么都不做。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (ctx.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) return
            ctx.activity.runOnUiThread {
                try {
                    ctx.activity.requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        JsBridge.REQ_NOTIFY_PERM
                    )
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 查询后台常驻通知当前是不是开着（前端渲染开关状态用） */
    @JavascriptInterface
    fun totpBackgroundEnabled(): Boolean {
        return try {
            TotpService.isEnabled(ctx.activity)
        } catch (ignored: Throwable) {
            false
        }
    }

    /** 当前 App 是否在前台（通知只在后台出现，前台不打扰） */
    @JavascriptInterface
    fun isAppForeground(): Boolean {
        return App.sForeground
    }

    /**
     * 扫一扫的前置：确认相机权限。回调 window.Native._camera(id, true/false)。
     *
     * 为什么要先走原生权限：WebView 里 getUserMedia 的授权链是
     * 「App 先持有 CAMERA 权限 → 页面发起请求 → onPermissionRequest 弹给
     * App → App 批给渲染进程」。App 自己没有 CAMERA 权限时，WebView
     * 连问都不会问、直接拒绝 —— 所以必须先把原生权限要到手，再开摄像头。
     *
     * 已授权：立刻回调 true，不弹框；
     * 没授权：弹一次系统权限框，用户选完（允许/拒绝）都把结果如实回调，
     * 绝不静默 —— 点了扫一扫却什么都没发生，比被拒绝更让人困惑。
     */
    @JavascriptInterface
    fun requestCamera(id: String?) {
        val granted = try {
            ctx.activity.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        } catch (ignored: Throwable) {
            false
        }
        if (granted) {
            cameraResult(id, true)
            return
        }
        pendingCameraId = id
        ctx.activity.runOnUiThread {
            try {
                ctx.activity.requestPermissions(
                    arrayOf(Manifest.permission.CAMERA),
                    JsBridge.REQ_CAMERA_PERM
                )
            } catch (ignored: Throwable) {
                onCameraPermissionResult(false)
            }
        }
    }

    /** MainActivity 在 onRequestPermissionsResult 里转发相机权限结果。 */
    fun onCameraPermissionResult(ok: Boolean) {
        val id = pendingCameraId
        pendingCameraId = null
        if (id != null) cameraResult(id, ok)
    }

    private fun cameraResult(id: String?, ok: Boolean) {
        ctx.runJs(
            "window.Native._camera(" + org.json.JSONObject.quote(id.toString()) + ","
                    + (if (ok) "true" else "false") + ")"
        )
    }

    /**
     * 生成密码学安全的随机字节，十六进制返回。
     *
     * 给「本机生成签名密钥」用（KeyTool），也用于两步验证器的密钥强度提示。
     * 用 SecureRandom 而不是 Math.random —— 后者是可预测的伪随机，
     * 拿来生成密钥等于没生成。
     */
    @JavascriptInterface
    fun randomHex(bytes: Int): String {
        return try {
            val n = bytes.coerceIn(1, 256)
            val buf = ByteArray(n)
            SecureRandom().nextBytes(buf)
            val sb = StringBuilder(n * 2)
            for (b in buf) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (ignored: Throwable) {
            ""
        }
    }

    /**
     * HMAC-SHA1 / HMAC-SHA256，输入输出都是十六进制。
     *
     * 两步验证器每秒都要算一次码。纯 JS 的 SHA 实现在低端机上是
     * 实打实的开销（一个账户一次哈希），交给系统 Mac 走原生实现，
     * 既快又不占主线程。
     *
     * 前端拿不到这个方法时会自动退回纯 JS 实现，功能不受影响。
     */
    @JavascriptInterface
    fun hmacSha1(keyHex: String?, msgHex: String?): String = hmac("HmacSHA1", keyHex, msgHex)

    @JavascriptInterface
    fun hmacSha256(keyHex: String?, msgHex: String?): String = hmac("HmacSHA256", keyHex, msgHex)

    private fun hmac(algo: String, keyHex: String?, msgHex: String?): String {
        return try {
            val key = hexToBytes(keyHex)
            val msg = hexToBytes(msgHex)
            if (key == null || msg == null) return ""
            val mac = Mac.getInstance(algo)
            mac.init(SecretKeySpec(key, algo))
            val out = mac.doFinal(msg)
            val sb = StringBuilder(out.size * 2)
            for (b in out) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
            sb.toString()
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun hexToBytes(hex: String?): ByteArray? {
        if (hex.isNullOrEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    @JavascriptInterface
    fun share(url: String?, title: String?) {
        ctx.activity.runOnUiThread {
            try {
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TEXT, url)
                i.putExtra(Intent.EXTRA_SUBJECT, title ?: "")
                ctx.activity.startActivity(Intent.createChooser(i, "分享"))
            } catch (ignored: Exception) {
                Toast.makeText(ctx.activity, "无法分享", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
