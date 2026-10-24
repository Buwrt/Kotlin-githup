package com.hubmobile.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.HashSet

/**
 * GitHub 通知的后台轮询 —— 让「通知」也能像微信那样出现在系统通知栏。
 *
 * ═══ 为什么微信 / QQ / 钉钉能收到，而 githup 以前收不到 ═══
 *
 * 它们有**自己的服务器**。消息一到，服务器主动推给手机（走厂商推送通道
 * 或自己的长连接），手机收到推送就弹系统通知 —— 这是「推」。
 *
 * githup 的通知来自 GitHub。**GitHub 不会把通知推给第三方 App**，
 * 只有 App 主动去问才知道有没有新的 —— 这是「拉」。
 *
 * 而 App 退到后台后 WebView 会被冻结、JS 停摆，根本没人在问。
 * 于是通知永远只在「打开 App 那一刻」才看得到，平时通知栏一条都没有。
 *
 * 所以这里补上那个"问"的人：在原生侧定时轮询，发现新的未读通知就发
 * 一条系统通知。原生侧不受 WebView 冻结影响。
 *
 * ═══ 为什么不用前台服务 ═══
 *
 * 前台服务必须挂一条常驻通知 —— 用户明确反对过（「为什么通知栏里有个
 * 这个，我想让他需要的时候再出现」）。所以这里用：
 *
 *     AlarmManager（定时发广播） + BroadcastReceiver + goAsync()
 *
 * 平时不占任何通知栏位置，只有真有新通知时才出现。
 *
 * ═══ 几条硬规矩 ═══
 *
 *   1) 没登录就不问 —— 没有令牌这个请求本身会失败，也没意义
 *   2) 同一条通知只提醒一次 —— 记下已通知过的 id，否则半小时后同一条
 *      又弹一遍，那是骚扰不是通知
 *   3) 没给通知权限就静默返回 —— 不能崩，也不能偷偷弹
 *   4) goAsync() 拿到的窗口必须 finish()，且只能调一次
 *
 * 【Kotlin 迁移】
 *   BroadcastReceiver 由系统按 manifest 反射实例化，必须是 public class
 *   且带无参构造 —— 所以静态那一半进 companion object，实例方法照旧。
 *   注意 notify() 这个函数名：Java 的 Object 有 final notify()，但 Kotlin
 *   的 Any 没有，所以这里能照原名保留，不用改名。
 */
class NotifWatchReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "githup-notif"

        /** 定时任务的 action */
        const val ACTION_CHECK = "com.hubmobile.app.CHECK_NOTIFICATIONS"

        /**
         * 轮询间隔：15 分钟。
         *
         * 以前是 30 分钟 —— 配合下面那个 Doze bug，实际可能几小时才轮到一次，
         * 用户自然觉得「根本收不到」。
         */
        private const val INTERVAL_MS = 15L * 60L * 1000L

        /** 单次检查最多提醒几条 —— 攒了几百条时不能一次全糊上去 */
        private const val MAX_SHOW = 5

        /** 已通知过的 id 最多记多少个（环形淘汰，防止无限膨胀） */
        private const val SEEN_MAX = 300

        private const val PREF = "notif_watch"
        private const val KEY_SEEN = "seen_ids"
        private const val KEY_LAST = "last_check"
        private const val KEY_ENABLED = "enabled"

        const val CHANNEL_NOTIF = "githup_notifications"

        /* ==================================================================
         * 调度
         * ================================================================== */

        private fun alarmPi(ctx: Context): PendingIntent {
            val i = Intent(ctx, NotifWatchReceiver::class.java)
            i.action = ACTION_CHECK
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(ctx, 2101, i, flags)
        }

        /**
         * 排下一次检查（链式调度）。
         *
         * ═══ 为什么不用 setInexactRepeating ═══
         *
         * 以前是：
         *
         *     am.setInexactRepeating(ELAPSED_REALTIME_WAKEUP, now + 30min, 30min, pi);
         *
         * 这是「收不到通知」的**主因**。Android 6+ 进入 Doze（灭屏静置一会儿
         * 就进）之后，普通 alarm 会被**推迟到维护窗口**才执行 —— 维护窗口
         * 一开始是几小时一次。手机放着不动一晚上，一轮都跑不到。
         * 于是白天用着 App 才有反应，晚上、锁屏期间一条都没有。
         *
         * setAndAllowWhileIdle 能在 idle 时也把设备叫醒干活（系统限制约
         * 9 分钟一次，15 分钟间隔绰绰有余）。但它**没有 repeating 版本** ——
         * 所以只能排单次，每次干完活自己再排下一次。
         *
         * 这就是链式调度。断链的唯一种类是进程被杀，而开机 / 覆盖安装 /
         * 回到前台（MainActivity.onResume）都会重新挂上，断不了多久。
         */
        @JvmStatic
        fun schedule(ctx: Context?) {
            scheduleAfter(ctx, INTERVAL_MS)
        }

        /** @param delayMs 多久之后触发 */
        private fun scheduleAfter(ctx: Context?, delayMs: Long) {
            if (ctx == null) return
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                val pi = alarmPi(ctx)
                val trigger = SystemClock.elapsedRealtime() + delayMs
                if (Build.VERSION.SDK_INT >= 23) {
                    /* AllowWhileIdle：Doze 期间也能触发。不加这个，锁屏后基本收不到。 */
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
                }
                Log.d(TAG, "已排下一次通知检查，" + (delayMs / 1000) + " 秒后")
            } catch (t: Throwable) {
                Log.w(TAG, "排轮询失败: $t")
            }
        }

        /**
         * 刚登录 / 刚加完账户：先排一次「近期」检查。
         *
         * ═══ 为什么 ═══
         *
         * 以前登录后要等满 15 分钟才轮到第一次。而第一次（lastCheck == 0）
         * 按规矩是「只记不发」——它在建基线，把用户未读箱里已经攒着的那一堆
         * 历史通知标记为已通知，免得刚登录就被轰炸。
         *
         * 于是用户的真实体验是：登录 → 等 15 分钟建基线 → 再等 15 分钟 →
         * 期间还得恰巧有人回复他，才第一次看到通知。几乎等于「收不到」。
         *
         * 现在登录后 20 秒就先建一次基线，之后每 15 分钟的正常轮询里，
         * 只要真有新通知就立刻提醒。
         */
        @JvmStatic
        fun scheduleSoonAfterLogin(ctx: Context?) {
            scheduleAfter(ctx, 20L * 1000L)
        }

        /**
         * App 退到后台：排一次近期检查。
         *
         * 用户刚用完 App 离开，接下来一段时间最可能有新动静（别人回复你、
         * @你）。这时候把检查提前一点，比死等 15 分钟贴心得多。
         */
        @JvmStatic
        fun scheduleSoonAfterLeave(ctx: Context?) {
            scheduleAfter(ctx, 3L * 60L * 1000L)
        }

        /**
         * 立刻检查一次（不等待），给「通知页下拉刷新 / 刚登录」这类场景用。
         *
         * 异步执行，调用方不必切线程。
         */
        @JvmStatic
        fun checkNow(ctx: Context?) {
            if (ctx == null) return
            val app = ctx.applicationContext
            Thread({
                try {
                    checkOnce(app)
                } catch (t: Throwable) {
                    Log.w(TAG, "立即检查失败: $t")
                }
            }, "notif-now").start()
        }

        /** 取消定时检查（退出登录时调） */
        @JvmStatic
        fun cancel(ctx: Context?) {
            if (ctx == null) return
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                am?.cancel(alarmPi(ctx))
            } catch (ignored: Throwable) { }
        }

        /**
         * 后台通知提醒是否开启 —— **恒为 true**。
         *
         * ═══ 为什么不再读存档值 ═══
         *
         * 设置里的「通知栏提醒」开关已去掉。而那个开关的存档值（KEY_ENABLED）
         * 还在用户的 SharedPreferences 里 —— 谁当初顺手关过一次，存的就是
         * false。开关一去掉，就**再也没有入口能把它改回来**，后台提醒等于
         * 永久失效。
         *
         * 所以这里不再读那个值，直接返回 true：功能始终开着。
         *
         * 顺手把旧的 false 存档清掉，免得将来哪天又加回开关时，用户一进来
         * 发现它是关着的、却想不起自己什么时候关过。
         */
        @JvmStatic
        fun isEnabled(ctx: Context?): Boolean {
            if (ctx == null) return true
            try {
                val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                if (sp.getBoolean(KEY_ENABLED, true)) return true
                /* 存的是 false：清掉，让「默认开」重新成为事实 */
                sp.edit().remove(KEY_ENABLED).apply()
            } catch (ignored: Throwable) { }
            return true
        }

        /**
         * 保留这个入口只是为了兼容（原生接口还在），但它不再把「关」写进存档 ——
         * 写了就没有入口能改回来。关掉 = 不排下一次轮询，下一次打开 App
         * （MainActivity.onResume）仍会重新挂上。
         */
        @JvmStatic
        fun setEnabled(ctx: Context?, on: Boolean) {
            if (ctx == null) return
            try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, true).apply()
                if (on) schedule(ctx) else cancel(ctx)
            } catch (ignored: Throwable) { }
        }

        /* ==================================================================
         * 一次检查
         * ================================================================== */

        /**
         * 真正的一次检查。调用方保证不在主线程。
         *
         * 顺便拿个 WakeLock：AlarmManager 的 _WAKEUP 只保证唤醒到 onReceive
         * 结束，goAsync 期间 CPU 可能睡下去，请求会卡在半路。
         */
        private fun checkOnce(ctx: Context?) {
            if (ctx == null) return
            if (!isEnabled(ctx)) return

            val token = SecurePrefs.get(ctx, "gh_token", "")
            if (token == null || token.isEmpty()) {
                /* 没登录：这一轮没得问，但链还得续 —— 用户随时可能登录 */
                try { scheduleAfter(ctx, INTERVAL_MS) } catch (ignored: Throwable) { }
                return
            }

            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var wl: PowerManager.WakeLock? = null
            if (pm != null) {
                try {
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "githup:notif")
                    wl.acquire(60_000L)
                } catch (ignored: Throwable) { wl = null }
            }

            try {
                val headers: MutableMap<String, String> = HashMap()
                headers["Authorization"] = "Bearer $token"
                headers["Accept"] = "application/vnd.github+json"
                headers["User-Agent"] = "githup/1.0"

                val r = Http.request("GET",
                    "https://api.github.com/notifications?per_page=20", null, headers)

                if (r.code < 200 || r.code >= 300) {
                    /* 401 = 令牌失效、403 = 限流。都不弹通知，静默等下一轮。 */
                    Log.d(TAG, "拉取通知返回 " + r.code + "，本轮跳过")
                    return
                }

                /* ═══ 首次只记不发 ═══

                   第一次拉到通知时，用户的未读箱里往往已经攒了几十上百条
                   （从来没清过）。如果照单全发，一登录就被历史通知砸晕 ——
                   那不是提醒，是轰炸。

                   所以：从没检查过（lastCheck == 0）时，这一轮只把 id 记进
                   去重表，一条都不弹。之后新来的才提醒。 */
                val firstRun = lastCheck(ctx) == 0L

                val fresh = parseFresh(ctx, r.body)
                if (fresh.isEmpty() || firstRun) {
                    Log.d(TAG, if (firstRun) "首次检查，只记不弹（避免历史通知轰炸）" else "没有新通知")
                    return
                }

                /* 权限没给就到此为止 —— 记过 id 了，下一轮不会重复提醒 */
                if (!canNotify(ctx)) {
                    Log.d(TAG, "没有通知权限，跳过弹窗")
                    return
                }
                notify(ctx, fresh)
            } catch (t: Throwable) {
                Log.w(TAG, "检查通知异常: $t")
            } finally {
                if (wl != null) { try { wl.release() } catch (ignored: Throwable) { } }
                /* 链式调度：干完这一轮，排下一轮。
                   放在 finally 里 —— 就算这轮请求失败/抛异常，链也不能断。 */
                try { scheduleAfter(ctx, INTERVAL_MS) } catch (ignored: Throwable) { }
            }
        }

        /* ==================================================================
         * 解析与去重
         * ================================================================== */

        private class Item {
            var id: String = ""
            var title: String = ""
            var repo: String = ""
            var reason: String = ""
        }

        /** 解析出「还没提醒过」的未读通知 */
        private fun parseFresh(ctx: Context, body: String?): List<Item> {
            val out = ArrayList<Item>()
            if (body == null || body.isEmpty()) return out
            val sp: SharedPreferences = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val seenRaw = sp.getString(KEY_SEEN, "") ?: ""
            val seen = HashSet<String>()
            for (s in seenRaw.split(",")) {
                if (s.isNotEmpty()) seen.add(s)
            }

            try {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.optBoolean("unread", false)) continue

                    val id = o.optString("id", "")
                    if (id.isEmpty() || seen.contains(id)) continue

                    val it = Item()
                    it.id = id
                    val subj = o.optJSONObject("subject")
                    it.title = if (subj != null) subj.optString("title", "") else ""
                    if (it.title.isEmpty()) it.title = "新通知"
                    val repo = o.optJSONObject("repository")
                    it.repo = if (repo != null) repo.optString("full_name", "") else ""
                    it.reason = o.optString("reason", "")

                    out.add(it)
                    seen.add(id)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "解析通知失败: $t")
            }

            /* 去重表落盘（不管有没有新通知，只要拉成功了就更新，避免反复重试） */
            try {
                val sb = StringBuilder()
                var n = 0
                for (s in seen) {
                    if (n > 0) sb.append(',')
                    sb.append(s)
                    n++
                    if (n >= SEEN_MAX) break
                }
                sp.edit().putString(KEY_SEEN, sb.toString())
                    .putLong(KEY_LAST, System.currentTimeMillis()).apply()
            } catch (ignored: Throwable) { }

            return out
        }

        /* ==================================================================
         * 发通知
         * ================================================================== */

        private fun canNotify(ctx: Context): Boolean {
            return try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false
                if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false
                if (Build.VERSION.SDK_INT >= 33) {
                    if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) return false
                }
                true
            } catch (ignored: Throwable) {
                false
            }
        }

        /** 建（或复用）通知渠道。IMPORTANCE_DEFAULT = 状态栏有图标、通知栏占位。 */
        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                if (nm.getNotificationChannel(CHANNEL_NOTIF) != null) return
                val ch = NotificationChannel(
                    CHANNEL_NOTIF, "GitHub 通知", NotificationManager.IMPORTANCE_DEFAULT)
                ch.description = "有人回复你、@你、或你关注的仓库有新动静时提醒"
                ch.enableVibration(false)
                ch.setSound(null, null)
                nm.createNotificationChannel(ch)
            } catch (ignored: Throwable) { }
        }

        private fun notify(ctx: Context, items: List<Item>) {
            try {
                ensureChannel(ctx)
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

                /*
                  点通知 → 打开 App 的通知页。

                  用 "route" 这个 extra：MainActivity.applyNotifRoute() 已经实现了
                  「把 route 交给前端 Router.go」这条路，复用它比另写一套稳。
                  通知页的路由是 /notifications（见 app.js 的 RESERVED 与路由表）。
                */
                val open = Intent(ctx, MainActivity::class.java)
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                open.putExtra("route", "/notifications")
                var pif = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 23) pif = pif or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getActivity(ctx, 2102, open, pif)

                val b: Notification.Builder =
                    if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_NOTIF)
                    else Notification.Builder(ctx)

                val first = items[0]
                val total = items.size

                if (total == 1) {
                    val sub = if (first.repo.isEmpty()) reasonText(first.reason) else first.repo
                    b.setContentTitle(first.title)
                        .setContentText(sub)
                        .setStyle(Notification.BigTextStyle().bigText(
                            first.title + "\n" + sub))
                } else {
                    /* 多条：用 InboxStyle 列出前几条，末尾说明还有多少 */
                    val inbox = Notification.InboxStyle()
                    val n = Math.min(total, MAX_SHOW)
                    for (i in 0 until n) {
                        val it = items[i]
                        inbox.addLine(if (it.repo.isEmpty()) it.title else it.repo + "：" + it.title)
                    }
                    if (total > n) inbox.addLine("还有 " + (total - n) + " 条…")
                    inbox.setBigContentTitle(total.toString() + " 条新通知")
                    inbox.setSummaryText("点开查看全部")
                    b.setContentTitle(total.toString() + " 条新通知")
                        .setContentText(items[0].title)
                        .setStyle(inbox)
                }

                b.setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)

                try {
                    val ic = ctx.resources.getIdentifier(
                        "ic_launcher", "mipmap", ctx.packageName)
                    if (ic != 0) {
                        b.setLargeIcon(BitmapFactory.decodeResource(ctx.resources, ic))
                    }
                } catch (ignored: Throwable) { }

                nm.notify(3101, b.build())
                Log.d(TAG, "已发出通知，共 " + total + " 条")
            } catch (t: Throwable) {
                Log.w(TAG, "发通知失败: $t")
            }
        }

        /** 把 GitHub 的 reason 字段翻成中文 */
        private fun reasonText(r: String?): String = when (r) {
            "mention" -> "有人提到了你"
            "assign" -> "有人把任务指派给你"
            "author" -> "你参与的话题有更新"
            "comment" -> "有人评论了你参与的内容"
            "review_requested" -> "有人请你评审"
            "state_change" -> "状态有变化"
            "subscribed" -> "你关注的仓库有动静"
            "team_mention" -> "你的团队被提到"
            else -> "GitHub 通知"
        }

        /** 给设置页显示上次检查时间用 */
        @JvmStatic
        fun lastCheck(ctx: Context?): Long {
            if (ctx == null) return 0
            return try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST, 0)
            } catch (ignored: Throwable) { 0 }
        }

        /** 清掉去重表（退出登录时用，换账号后 id 不该继承） */
        @JvmStatic
        fun resetSeen(ctx: Context?) {
            if (ctx == null) return
            try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().remove(KEY_SEEN).remove(KEY_LAST).apply()
            } catch (ignored: Throwable) { }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action
        if (action != null && ACTION_CHECK != action
            && Intent.ACTION_BOOT_COMPLETED != action
            && Intent.ACTION_MY_PACKAGE_REPLACED != action) {
            return
        }

        /* goAsync() 给这段工作续一条命 —— onReceive 返回后进程随时可能被回收。
         * 拿到的 PendingResult 必须 finish()，且只能调一次。 */
        val result = goAsync()
        val app = context.applicationContext

        Thread({
            try {
                checkOnce(app)
            } catch (t: Throwable) {
                Log.w(TAG, "检查通知失败: $t")
            } finally {
                try { result.finish() } catch (ignored: Throwable) { }
            }
        }, "notif-check").start()
    }
}
