package com.hubmobile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后台常驻的「动态码」通知。
 *
 * 干什么 ——
 *   用户把 App 退到后台之后，在通知栏留一条常驻通知，上面是两步验证器
 *   里各账户的当前动态码，每 30 秒自动换码、每秒刷新倒计时。这样在别的
 *   应用里要填验证码时，拉一下通知栏就能看到，不用切回 githup。
 *
 * 核心架构（两次闪退修复之后定型）——
 *
 *   1.2.15 初版：onPause → startForegroundService，onResume → stopService。
 *   在国产 ROM（一加 / OPPO / 努比亚）上必崩：
 *
 *   · Android 12+ 有「后台启动前台服务限制」。onPause 发起
 *     startForegroundService 时，系统可能已经把本应用判定为「后台」，
 *     服务里的 startForeground 会抛 ForegroundServiceStartNotAllowedException。
 *   · 第一版修复把 startForeground 提前到 onCreate 并用 try-catch 兜底，
 *     但异常被吞掉之后，ServiceRecord 上「必须进前台」的标记仍挂着，
 *     系统看门狗在 10 秒后照样抛出 ForegroundServiceDidNotStartInTimeException
 *     杀进程——异常投递到主线程，表现为「重新打开 App 后 1 秒闪退」。
 *
 *   现在的做法：
 *
 *   · onResume（App 必定在前台）就把服务拉起来，服务立刻进入前台状态，
 *     但挂的是 IMPORTANCE_MIN 的「安静通知」——状态栏没有图标，只在
 *     通知栏最底下缩成一条，使用 App 时完全无感。
 *   · onPause 时服务【已经是前台服务】了，只需把通知内容换成动态码，
 *     不发生任何新的「后台启动」，从根上绕开限制。
 *   · onResume 时再换回安静通知，服务保持存活，随时待命。
 *   · startForeground 真的失败（被系统拒绝）时，立刻 stopSelf 尽快移除
 *     ServiceRecord，绝不留着等看门狗来杀。
 *   · 用户从最近任务列表划掉 App（onTaskRemoved）时停掉服务，不永久空跑。
 *
 * 关于自启动 ——
 *   开机广播（见 BootReceiver）负责在重启后把通知恢复出来，BOOT_COMPLETED
 *   属于系统豁免的启动场景。部分国产 ROM 仍需用户在系统设置里额外允许
 *   「自启动」，App 不会反复弹窗纠缠。
 *
 * 【Kotlin 迁移】
 *   Service 同样由系统反射实例化，必须是 public class + 无参构造。
 *   静态入口搬进 companion object 并加 @JvmStatic，MainActivity / JsBridge /
 *   BootReceiver 这些 Java 调用方一个字都不用改。
 */
class TotpService : Service() {

    companion object {
        /** 动态码渠道：LOW，退到后台时真正展示内容的通知 */
        private const val CHANNEL_CODES = "githup_totp"
        /** 安静渠道：MIN，App 在前台时服务保命用，状态栏无图标、通知栏底部折叠 */
        private const val CHANNEL_QUIET = "githup_totp_quiet"
        private const val NOTIFY_ID = 7301
        private const val PREFS = "hub_prefs"
        private const val KEY_ENABLED = "totp_bg_enabled"

        /** App 退到后台 / 开机恢复：展示动态码 */
        private const val ACTION_SHOW = "com.hubmobile.totp.SHOW"
        /** App 回到前台：收回动态码、换安静通知，服务保持存活 */
        private const val ACTION_HIDE = "com.hubmobile.totp.HIDE"

        /** 刷新间隔：动态码默认 30 秒一轮，1 秒刷一次足够跟上倒计时 */
        private const val TICK_MS = 1000L

        /* ---------------- 外部开关 ---------------- */

        @JvmStatic
        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        @JvmStatic
        fun isEnabled(ctx: Context): Boolean {
            /*
             * 默认 true —— 设置页里那个「后台显示动态码」开关已经按用户要求删掉了，
             * 这个值不再由用户翻，所以不能停在 false 上，否则通知栏动态码
             * 永远出不来（第一步就是要它在通知里显示）。
             *
             * 默认开启不会造成骚扰，因为挂通知还有几道前置条件：
             *   · accounts.isEmpty() → stopSelf()，没有账户时一条通知都不留；
             *   · App 在前台时只挂 MIN 级安静通知，状态栏没有图标；
             *   · Android 13+ 没给通知权限时通知不显示，不影响 App 本身。
             * 也就是说用户没加过任何两步验证账户的话，这个 true 什么都不做。
             */
            return prefs(ctx).getBoolean(KEY_ENABLED, true)
        }

        /**
         * 申请通知权限（Android 13+），只在「还没授权」时静默发起一次。
         *
         * 以前这件事是在用户点设置页那个开关时做的。开关删掉之后，
         * 改成 App 启动 / 回到前台时顺手要一次 —— 不给也不纠缠，
         * 用户只是看不到通知栏里的动态码，App 其余功能一切照常。
         * 通过 JsBridge 走同一条权限申请路径（结果由 MainActivity 转回来）。
         */
        @JvmStatic
        fun ensureNotificationPermission(activity: MainActivity?) {
            if (activity == null) return
            if (Build.VERSION.SDK_INT < 33) return
            try {
                if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                    return
                }
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    JsBridge.REQ_NOTIFY_PERM)
            } catch (ignored: Throwable) {
                // 申请失败（比如没有 Activity 窗口）就当没这回事，不打断启动流程
            }
        }

        /**
         * 开 / 关后台通知。
         *
         * 设置页的开关删掉之后，前端已经不再调用这里 —— 保留是因为它仍然是
         * 「强制关掉」最干净的一条路（停服务 + 撤通知 + 落一个 false 的值），
         * 日后若要加回开关不必重写。当前默认值走 isEnabled 里的 true。
         */
        @JvmStatic
        fun setEnabled(ctx: Context, on: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
            if (on) {
                send(ctx, null)
            } else {
                try { ctx.stopService(Intent(ctx, TotpService::class.java)) } catch (ignored: Throwable) { }
                cancel(ctx)
            }
        }

        /**
         * 账户列表变了：让服务换一副新内容。
         * 服务没在跑就顺手拉起来（调用方在前台 UI 里，启动合法）。
         */
        @JvmStatic
        fun refresh(ctx: Context) {
            if (!isEnabled(ctx)) return
            send(ctx, null)
        }

        /**
         * 开机 / 覆盖安装后由 BootReceiver 调用：直接恢复成「展示动态码」状态。
         * BOOT_COMPLETED / MY_PACKAGE_REPLACED 属于系统豁免的前台服务启动场景。
         */
        @JvmStatic
        fun sync(ctx: Context) {
            if (!isEnabled(ctx)) {
                cancel(ctx)
                return
            }
            send(ctx, ACTION_SHOW)
        }

        /**
         * App 退到后台时调用（MainActivity.onPause）：把动态码通知挂出来。
         *
         * 正常情况下服务在 onResume 时已经启动、已经是前台服务，这里只是
         * 让它把通知内容换成动态码（ACTION_SHOW → 仅更新通知，不触发任何
         * 新的前台启动）。万一服务没活着（被系统回收等极端情况），send
         * 内部对 startForegroundService 的异常也做了兜底，入口处被拒绝时
         * 最多是这次看不到通知，绝不会闪退。
         */
        @JvmStatic
        fun showNotification(ctx: Context?) {
            if (ctx == null) return
            if (!isEnabled(ctx)) return
            send(ctx, ACTION_SHOW)
        }

        /**
         * App 回到前台时调用（MainActivity.onResume）。
         *
         * 注意：这里【不再停服务】，而是发 ACTION_HIDE ——
         * 服务保持存活，通知换成 MIN 级安静通知。这样下一次 onPause 时
         * 服务已经是前台状态，彻底避开 Android 12+ 的「后台启动前台服务」
         * 限制（1.2.15 两次闪退的根因）。
         * 服务若还没启动（冷启动后第一次 onResume），这里就以【前台身份】
         * 合法地把它拉起来。
         */
        @JvmStatic
        fun hideNotification(ctx: Context?) {
            if (ctx == null) return
            if (!isEnabled(ctx)) {
                try { ctx.stopService(Intent(ctx, TotpService::class.java)) } catch (ignored: Throwable) { }
                cancel(ctx)
                return
            }
            send(ctx, ACTION_HIDE)
        }

        /**
         * 统一的服务启动入口。
         * 系统在入口处就拒绝（后台限制，抛 ForegroundServiceStartNotAllowedException
         * 或 IllegalStateException）时，结果只是「这次不显示通知」，绝不能让它崩。
         */
        private fun send(ctx: Context, action: String?) {
            try {
                val i = Intent(ctx, TotpService::class.java)
                if (action != null) i.action = action
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (ignored: Throwable) { }
        }

        private fun cancel(ctx: Context) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFY_ID)
            } catch (ignored: Throwable) { }
        }

        /** 密钥规整：去掉空格 / 连字符 / 补位，统一大写 —— 判定「是不是同一个账户」用 */
        private fun normKey(secret: String?): String {
            if (secret == null) return ""
            return secret.uppercase()
                .replace(Regex("[\\s\\-_]"), "")
                .replace(Regex("=+$"), "")
        }

        /**
         * 文字进度条：用方块画一条会走的倒计时。
         * 例：[██████░░░░░░] 18s
         */
        private fun bar(left: Long, period: Int): String {
            val total = 10
            var filled = Math.round((left * 1.0 / period) * total).toInt()
            if (filled < 0) filled = 0
            if (filled > total) filled = total
            val sb = StringBuilder("[")
            for (i in 0 until filled) sb.append('█')
            for (i in filled until total) sb.append('░')
            sb.append("] ").append(left).append('s')
            return sb.toString()
        }
    }

    private var handler: Handler? = null
    private var ticker: Runnable? = null
    /** 已解析的账户（每次刷新时重新读，免得前端改了列表这里还是旧的） */
    private var accounts: MutableList<JSONObject> = ArrayList()
    /** 上一次画出来的标题，内容没变就不重复 notify，省电 */
    private var lastText = ""
    /** startForeground 是否真正成功过；只有成功过系统的超时看门狗才不会咬人 */
    private var foregroundReady = false

    /* ---------------- 服务生命周期 ---------------- */

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        ensureChannels()

        /*
         * 服务一旦创建，立刻尝试进入前台状态（先用安静通知）。
         *
         * 注意：这里绝不能像上一版修复那样「try-catch 吞掉异常继续跑」——
         * startForeground 抛 ForegroundServiceStartNotAllowedException
         * 意味着 ServiceRecord 上「必须进前台」的标记会一直挂着，
         * 10 秒后系统看门狗照样抛 ForegroundServiceDidNotStartInTimeException。
         *
         * 策略：安静通知失败 → 最简通知再试一次 → 还失败立刻 stopSelf，
         * 尽快让系统移除 ServiceRecord（服务销毁会取消挂起的超时消息）。
         */
        if (!promote(buildQuietNotification())) {
            if (!promote(buildBareNotification())) {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        // 再确保一次前台状态。正常情况下 onCreate 已成功，重复调用无副作用。
        if (!foregroundReady && !promote(buildQuietNotification())) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        reload()
        val empty = accounts.isEmpty()

        if (ACTION_SHOW == action) {
            // App 退到后台 / 开机恢复：展示动态码并开始每秒刷新
            stopTicker()
            if (empty) {
                stopSelf()
                return START_NOT_STICKY
            }
            drawCodes()
            startTicker()
        } else {
            // ACTION_HIDE（onResume）或无 action（refresh / setEnabled）：
            // App 在前台，停掉刷新、换安静通知，服务保持存活待命。
            stopTicker()
            if (empty) {
                stopSelf()
                return START_NOT_STICKY
            }
            showQuiet()
        }

        // START_STICKY：被系统回收后自动重建，通知不会莫名其妙消失
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务列表划掉了 App：服务不必再留着，收摊。
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopTicker()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null   // 不需要绑定

    /* ---------------- 前台状态 ---------------- */

    /**
     * 尝试真正进入前台状态。只有成功返回，系统的超时看门狗才不会咬人。
     *
     * @return true 表示 startForeground 成功；false 表示被系统拒绝
     *         （最典型：Android 12+ 后台启动限制），调用方必须立刻停服务。
     */
    private fun promote(n: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                // 显式带上 dataSync 类型（与 Manifest 声明一致），
                // 个别国产 ROM 对无类型 startForeground 处理不稳定。
                startForeground(NOTIFY_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFY_ID, n)
            }
            foregroundReady = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 换成安静通知（App 在前台时） */
    private fun showQuiet() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFY_ID, buildQuietNotification())
        } catch (ignored: Throwable) { }
    }

    /* ---------------- 动态码刷新 ---------------- */

    private fun startTicker() {
        stopTicker()
        ticker = object : Runnable {
            override fun run() {
                try {
                    update()
                } catch (ignored: Throwable) { }
                // 每秒自查一次：账户被删空、或开关被关掉，就自行收摊
                if (!isEnabled(this@TotpService) || accounts.isEmpty()) {
                    stopSelf()
                    return
                }
                handler?.postDelayed(this, TICK_MS)
            }
        }
        handler?.post(ticker!!)
    }

    private fun stopTicker() {
        if (handler != null && ticker != null) handler!!.removeCallbacks(ticker!!)
        ticker = null
    }

    /** 重新读一遍账户清单 */
    private fun reload() {
        accounts = ArrayList()
        /*
         * 去重：同一个账户被存了两份（换过 issuer / name、导入时重复写、
         * 旧版本缓存没清干净）时，通知里就会并排出现两行一模一样的码。
         * 这里按「规整后的密钥 + 发行方 + 账户名」判重，只留第一条。
         */
        val seen = HashSet<String>()
        try {
            val raw = prefs(this).getString("totp_accounts_cache", "[]")
            val arr = JSONArray(raw ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val secret = o.optString("secret", "")
                if (secret.isEmpty()) continue
                if ("totp" != o.optString("type", "totp")) continue   // HOTP 没有时间概念，不在这展示
                var key = normKey(secret)
                key += "|" + o.optString("type", "totp")
                key += "|" + o.optInt("digits", 6)
                key += "|" + o.optInt("period", 30)
                key += "|" + o.optString("algo", "SHA1")
                if ("hotp" == o.optString("type", "totp")) {
                    key += "|" + o.optInt("counter", 0)
                }
                if (!seen.add(key)) continue   // 重复账户，丢掉
                accounts.add(o)
            }
        } catch (ignored: Throwable) { }
    }

    /** 立刻画一版动态码（不等第一拍 tick） */
    private fun drawCodes() {
        lastText = ""
        update()
    }

    /** 算每个账户当前的码并重画通知 */
    private fun update() {
        /*
         * 每一轮都重读一遍账户清单 —— 前端那边增删改账户之后，下一拍
         * 通知就要跟上。SharedPreferences 读的是进程内缓存，每秒一次的
         * 开销可忽略。
         */
        reload()
        if (accounts.isEmpty()) return

        /*
         * 逐账户算码，坏账户跳过。
         * 谁算得出来就显示谁，一个坏账户（secret 损坏、原生端不认）
         * 不再拖垮整条通知。
         */
        val lines = ArrayList<String>()
        var title: String? = null
        var left = 0L
        var period = 30
        for (acct in accounts) {
            val code = Totp.compute(acct)
            if (code == null || code.isEmpty()) continue
            var name = acct.optString("issuer", "")
            if (name.isEmpty()) name = acct.optString("name", "")
            if (name.isEmpty()) name = "两步验证"
            val line = name + "  " + Totp.group(code)
            if (title == null) {
                // 第一个账户进标题行；它不再重复出现在展开列表里，
                // 否则单账户时收起 / 展开会看到两行一模一样的码。
                title = line
                left = Totp.remaining(acct)
                val p = acct.optInt("period", 30)
                period = if (p > 0) p else 30
                continue
            }
            lines.add(line)
        }
        if (title == null) return

        /*
         * 倒计时 —— 这一版的核心改动。
         *
         * 以前只有「还剩 N 秒」一句话，秒数藏在副标题里、一眼扫不到；
         * 现在同时给三种形态，用户随便哪种习惯都能看到：
         *   1) 系统原生进度条（setProgress）：从左退到右，剩余时间一眼可见；
         *   2) 副标题里的「剩余 Ns」大字；
         *   3) 展开视图里的一条方块进度条 + 秒数。
         */
        if (left < 0) left = 0
        if (left > period) left = period.toLong()
        val elapsed = (period - left).toInt()

        var text = "剩余 " + left + "s · " + period + " 秒一轮"
        if (accounts.size > 1) text += " · 共 " + accounts.size + " 个账户"

        // 内容没变（同一秒内重复触发）就不重复推，省电
        val stamp = title + "|" + text
        if (stamp == lastText) return
        lastText = stamp

        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            nm.notify(NOTIFY_ID, buildCodesNotification(title, text, lines, left, period, elapsed))
        } catch (ignored: Throwable) { }
    }

    /* ---------------- 通知构建 ---------------- */

    private fun newBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId)
        else Notification.Builder(this)

    /**
     * 安静通知：服务保命用。
     * 渠道 IMPORTANCE_MIN：状态栏无图标、无声，只在通知栏最底部折叠成一条。
     */
    private fun buildQuietNotification(): Notification {
        val b = newBuilder(CHANNEL_QUIET)
        b.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("githup")
            .setContentText("动态码随时待命")
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MIN)
        }
        return b.build()
    }

    /**
     * 最简通知：安静通知都构建失败时的兜底，不带任何 PendingIntent /
     * 样式，只求 startForeground 能成功，不触发超时崩溃。
     */
    private fun buildBareNotification(): Notification {
        val b = newBuilder(CHANNEL_QUIET)
        b.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("githup")
            .setOngoing(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MIN)
        }
        return b.build()
    }

    /**
     * 动态码通知：退到后台时真正展示的内容。
     *
     * @param title   标题行：第一个账户的「名字 + 分组后的码」
     * @param text    副标题：倒计时秒数 + 周期
     * @param lines   【除第一个之外】其余账户的行（第一个已在标题里，不再重复）
     * @param left    本轮剩余秒数
     * @param period  本轮总秒数
     * @param elapsed 已经过去的秒数（给系统进度条用）
     */
    private fun buildCodesNotification(title: String, text: String, lines: List<String>,
                                       left: Long, period: Int, elapsed: Int): Notification {
        val open = Intent(this, MainActivity::class.java)
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // 直接落到验证器页面：从通知点进来就是要看码，不该让人再找一遍
        open.putExtra("route", "/totp")

        var piFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags = piFlags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 0, open, piFlags)

        val b = newBuilder(CHANNEL_CODES)
        b.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)              // 常驻：不能左右滑掉，否则用户以为坏了
            .setShowWhen(false)
            .setOnlyAlertOnce(true)        // 每秒刷新不能每秒响一声
            .setCategory(Notification.CATEGORY_STATUS)

        /*
         * 倒计时进度条（系统原生）：max = 本轮总秒数，progress = 已过秒数，
         * 于是条子会随秒数一格格填满，填满即换码。
         * 这是「看得见的倒计时」最直观的一种形态。
         */
        b.setProgress(period, elapsed, false)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // 老系统没有渠道，用低优先级避免响铃与悬浮
            b.setPriority(Notification.PRIORITY_LOW)
        }

        // 展开了能一眼看清码，不用眯眼找。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val style = Notification.InboxStyle()
            /*
             * 大标题只放账户名与码。
             *
             * 以前这里会把每个账户再 addLine 一遍 —— 只有一个账户时，
             * 大标题是「GitHub 794 407」，下面又是一行「GitHub 794 407」，
             * 看起来就是「两排一模一样的数字」。现在第一个账户不再进列表，
             * 展开后标题 + 其余账户 + 倒计时行，绝不重复。
             */
            style.setBigContentTitle(title)
            // 展开视图的第一行就是倒计时：方块进度条 + 剩余秒数
            style.addLine(bar(left, period) + " 后刷新")
            if (lines.isNotEmpty()) {
                for (l in lines) style.addLine(l)
            }
            style.addLine("打开 githup 可复制或管理账户")
            b.setStyle(style)
        }
        return b.build()
    }

    /**
     * Android 8+ 必须先建渠道才会有通知。
     *
     * 两个渠道：
     *   · githup_totp       IMPORTANCE_LOW —— 动态码，退后台时展示
     *   · githup_totp_quiet IMPORTANCE_MIN —— 服务保命，App 在前台时无感
     * 都不响铃、不震动、不弹角标。
     */
    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (nm.getNotificationChannel(CHANNEL_CODES) == null) {
            val ch = NotificationChannel(
                CHANNEL_CODES, "两步验证动态码", NotificationManager.IMPORTANCE_LOW)
            ch.description = "在通知栏显示两步验证器的动态码（仅在你离开 App 时出现）"
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }

        if (nm.getNotificationChannel(CHANNEL_QUIET) == null) {
            val ch = NotificationChannel(
                CHANNEL_QUIET, "动态码后台服务", NotificationManager.IMPORTANCE_MIN)
            ch.description = "githup 随时准备在通知栏显示动态码（状态栏不显示图标）"
            ch.setShowBadge(false)
            ch.enableVibration(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }
}
