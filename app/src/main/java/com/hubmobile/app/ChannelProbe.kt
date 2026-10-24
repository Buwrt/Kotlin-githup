package com.hubmobile.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 通道自选 + 实时测速 + 慢速建议。
 *
 * 【从 JsBridge 拆出】逻辑一字未改，注释整段搬过来 —— 这里面每一条
 * 都是真机上踩出来的，丢一条就可能在下一个人手里重演一次。
 *
 * 关键约束：**绝不自动测速**。以前试过发车前探测各通道，结果用户
 * 点完下载要干等几秒，比走错通道再换还难受。这里的测速只在用户
 * 主动打开通道面板时才跑，而且是并发的、结果逐个显现，不阻塞任何
 * 正在进行的下载。
 */
internal class ChannelProbe(
    private val ctx: BridgeContext,
    /** 有没有正在下载的任务 —— 预热时要避开，别跟用户抢带宽 */
    private val hasActiveDownloads: () -> Boolean = { false }
) {

    /**
     * 测速目标 —— **必须是真实的文件下载链接，不能用网页地址**。
     *
     * 这是上一版「只有一条通道测得出速度」的根因：
     * 原来用的是 `https://github.com/Buwrt/githup`（一个普通网页），
     * 而 ghproxy 这类加速镜像**只代理文件下载**（Release 附件、源码包、
     * raw 文件），网页路径它们要么不认、要么回一段几十字节的 HTML 错误页 ——
     * 于是绝大多数通道被判成「不可用」，只有个别镜像碰巧能代理网页才测出速度。
     *
     * 换成 Release 附件直链：这是所有镜像的标准用法，成功率天差地别。
     * 文件约 1MB，读满 64KB 就够算速度了。
     */
    private val probeTarget =
        "https://github.com/Buwrt/githup/releases/download/v1.2.15/githup-v1.2.15.apk"

    /**
     * 云速通道专用的测速目标 —— 必须是 **raw 类地址**。
     *
     * 见 measureChannel：jsdelivr 只代理仓库文件，
     * 用上面那个 Release 附件去测它一定 404。
     */
    private val probeTargetRaw =
        "https://raw.githubusercontent.com/Buwrt/githup/main/README.md"

    /** 单条通道的测速预算（毫秒） */
    private val probeBudgetMs = 5_000

    /**
     * 每条通道测多久（毫秒）。
     *
     * ⚠️ 原来是「读固定字节就停」，这是个 bug：
     *   setReadTimeout 是**单次 read 的超时**，不是总时长。慢通道要花很久
     *   才能凑够目标字节，于是它一直挂着，最后一律被判成「不可用」——
     *   慢 ≠ 不可用，20KB/s 的通道明明能下，却被标成失败。
     *   这就是「只有加速 3 能用」的真正原因：**不是别的镜像挂了，
     *   是它们稍慢一点就被误判成死链**。
     *
     * 改成读固定时长：这个时间内读多少算多少，慢通道也能给出真实速度。
     */
    /*
      通道从 10 条扩到 24 条后，一轮测速的总耗时必须压住，否则用户点开
      面板要等很久才看到第一屏结果。所以窗口从 3 秒收到 2 秒 ——
      2 秒足够读出几十 KB，算速度完全够用。
    */
    private val probeWindowMs = 2_000

    /** 单条通道最多读多少字节 —— 兜住快通道，也保证样本够大（太小算不准） */
    private val probeMaxBytes = 1024 * 1024

    /**
     * 测速专用线程池。
     *
     * · 不能跟下载任务抢 pool 那 8 条线程，否则排不上队；
     * · 也不能一次全开十几条：多个 HTTPS 连接同时打出去会互相抢带宽，
     *   在弱网下连首字节都挤不出来，本来能通的也会被挤成超时。
     */
    private val probePool: ExecutorService = Executors.newFixedThreadPool(8)

    /** 是否在持续测速中（面板开着 = true） */
    @Volatile
    private var probing = false

    /**
     * 测速结果的新鲜度上限。
     *
     * 自动模式要拿「当前」最快的通道，但不能用一小时前的速度做决定 ——
     * 那时最快的现在未必还快。超过这个时长没测过，就当没数据，退回固定
     * 顺序，让换道机制去兜底。
     */
    private val probeFreshMs = 10 * 60 * 1000L

    /** 最后一次跑完一整轮测速的时间戳（0 = 从没测过） */
    @Volatile
    private var probeAt = 0L

    /**
     * 各通道连续失败次数。
     *
     * 通道从 10 扩到 24 之后，死链会明显变多。如果每轮都在这些死链上
     * 完整等一遍超时，一轮能拖到一分钟以上 —— 用户点开面板半天看不到
     * 结果，体验直接崩掉。
     *
     * 所以连续失败达到阈值就让这条「休眠」几轮：休眠期间直接标成不可用
     * 并跳过，不发起连接。冷却结束后再给它一次机会（免费镜像随时可能
     * 复活，不能永久拉黑）。
     */
    private val probeFails = HashMap<String, Int>()

    /** 连续失败几次进入休眠 */
    private val probeFailMax = 2

    /** 休眠几轮 */
    private val probeCoolRounds = 3

    /** 通道 key -> 还剩几轮休眠 */
    private val probeCool = HashMap<String, Int>()

    /** 两轮测速之间的间隔（毫秒） */
    private val probeGapMs = 1_500L

    /** 测速循环用的主线程 Handler */
    private val probeHandler = Handler(Looper.getMainLooper())

    private val probeLock = Any()

    /** 通道 key -> 实测速度（字节/秒）；-1 = 不可用；没有这个 key = 还在测 */
    @Volatile
    private var probeBps: Map<String, Long> = HashMap()

    /*
      ═══════ 慢速建议 ═══════

      思路来自 komi-store 的 SlowDownloadDetector：它自己不做加速，而是
      **盯着速度，慢到一定程度就主动告诉用户「你该开镜像了」**。

      为什么 githup 也需要这一层：换道是**静默**的 —— 用户只看到进度条
      一直在爬，不知道软件已经在后台换了好几条通道，更不知道「其实可以
      自己挑一条」。加上这一层之后，慢到一定程度会明确提示一次。

      参数直接沿用 komi-store 的取值（它那套是调过的）：
        · 观察窗口 30 秒 —— 太短会把「刚建连」误判成慢
        · 慢的判据 < 100 KB/s
        · 10 分钟内累计 3 次才提醒 —— 偶尔卡一下不打扰
    */
    /** 慢速事件的统计窗口 */
    private val slowWindowMs = 10 * 60 * 1000L
    /** 单次「慢」的观察窗口（要持续这么久才算数） */
    private val slowSustainedMs = 30 * 1000L
    /** 低于这个速度算慢 */
    private val slowThresholdBps = 100 * 1024L
    /** 窗口内累计几次才提醒 */
    private val slowTrigger = 3
    /** 提醒过一次之后的静默时长，避免反复弹 */
    private val slowSnoozeMs = 10 * 60 * 1000L

    /** 速度采样点 {时刻, 累计字节}，用于算 30 秒窗口内的平均速度 */
    private val slowSamples = ArrayDeque<LongArray>()
    /** 每一次判定为「慢」的时刻 */
    private val slowEvents = ArrayDeque<Long>()
    private var slowSnoozeUntil = 0L

    /* ═══════════════ 慢速采样 ═══════════════ */

    /**
     * 喂一个速度采样点。
     *
     * 只在**已有数据进账**之后才喂（建连期一个字节都没有，那不是慢）。
     * 窗口攒够 30 秒就算一次平均速度，低于阈值记一次慢事件。
     */
    fun noteSlowSample(now: Long, sofar: Long, speed: Long) {
        slowSamples.addLast(longArrayOf(now, sofar))
        while (!slowSamples.isEmpty() && slowSamples.peekFirst()[0] < now - slowSustainedMs) {
            slowSamples.pollFirst()
        }
        if (slowSamples.size < 2) return
        val a = slowSamples.peekFirst()
        val b = slowSamples.peekLast()
        val span = b[0] - a[0]
        if (span < slowSustainedMs - 500) return      /* 窗口还没攒满 */
        var dB = b[1] - a[1]
        if (dB < 0) dB = 0
        val avg = dB * 1000L / if (span <= 0) 1 else span
        if (avg < slowThresholdBps) recordSlowEvent(now)
    }

    /**
     * 记一次「慢」，够次数就提醒。
     *
     * 由 JsBridge 在「真的因为慢要换道」时调用 —— 那是实打实的一次慢，
     * 不是采样窗口里的估算。
     */
    fun recordSlowEvent(now: Long) {
        slowEvents.addLast(now)
        while (!slowEvents.isEmpty() && slowEvents.peekFirst() < now - slowWindowMs) {
            slowEvents.pollFirst()
        }
        if (slowEvents.size < slowTrigger) return
        slowEvents.clear()
        fireSlowAdvice(now)
    }

    /**
     * 真的提醒一次。
     *
     * 三道闸，缺一个都会变成骚扰：
     *   1) 用户点过「不再提示」 —— 永久不问（存盘）
     *   2) 刚提醒过 —— 静默 10 分钟
     *   3) 用户**已经手动指定过通道** —— 他自己挑过了，再说就是废话
     *      （这一条对应 komi-store 里「已经开了镜像就不再建议」）
     */
    private fun fireSlowAdvice(now: Long) {
        try {
            val sp = ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
            if (sp.getBoolean(JsBridge.KEY_ADVICE_OFF, false)) return
            if (now < slowSnoozeUntil) return
            val pref = readPreferredChannel()
            if (pref.isNotEmpty() && pref != "auto") return
            slowSnoozeUntil = now + slowSnoozeMs
            ctx.runJs("(function(){try{window.DlAdvice && window.DlAdvice.show();}catch(e){}})()")
        } catch (ignored: Throwable) {
        }
    }

    /** 用户点了「不再提示」 */
    @JavascriptInterface
    fun dismissSlowAdvice() {
        try {
            ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(JsBridge.KEY_ADVICE_OFF, true).apply()
        } catch (ignored: Throwable) {
        }
    }

    /* ═══════════════ 通道选择 ═══════════════ */

    /** 用户指定的通道 key；空 = 自动（按 MIRRORS 固定顺序） */
    private fun readPreferredChannel(): String {
        return try {
            ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
                .getString(JsBridge.KEY_CH_PREF, "") ?: ""
        } catch (ignored: Throwable) {
            ""
        }
    }

    private fun savePreferredChannel(key: String?) {
        try {
            ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
                .edit().putString(JsBridge.KEY_CH_PREF, key ?: "").apply()
        } catch (ignored: Throwable) {
        }
    }

    /** 当前选中的通道 key（前端用来打勾）；空串 = 自动 */
    @JavascriptInterface
    fun getChannel(): String = readPreferredChannel()

    /**
     * 选定通道。
     *
     * key = "auto"：自动 —— 取当前测速结果里最快的那条；还没测过就清空，
     * 走默认的固定顺序。
     * key = 某条通道（如 "https://ghfast.top/" 或 "direct"）：锁定它。
     */
    @JavascriptInterface
    fun setChannel(key: String?) {
        var k = key ?: ""
        /*
          自动模式存的就是 "auto" 这个**模式**，不是当时最快的那条通道。

          以前是把当时最快的那条锁死写进 prefs —— 那不叫自动，那叫
          「在某一瞬间自动了一次」，之后速度怎么变都跟它无关了。
          存 "auto" 之后，每次发车都重新去查当前最快的那条，
          实时数据变了，起步通道跟着变。
        */
        savePreferredChannel(k)
    }

    /** 已知测速结果里最快的那条；没有新鲜结果返回 null */
    private fun fastestKnownChannel(): String? = fastestKnownChannel(-1)

    /**
     * 已知测速结果里最快的那条。
     *
     * @param maxAgeMs 允许的最大数据年龄；<0 表示用默认新鲜度（probeFreshMs）
     * @return 最快的通道 key；没有结果 / 数据过期 返回 null
     */
    private fun fastestKnownChannel(maxAgeMs: Long): String? {
        val budget = if (maxAgeMs < 0) probeFreshMs else maxAgeMs
        val at = probeAt
        if (at <= 0L) return null                                   // 从没测过
        if (System.currentTimeMillis() - at > budget) return null    // 数据过期
        var best: String? = null
        var bv = 0L
        synchronized(probeLock) {
            for (e in probeBps.entries) {
                val v = e.value ?: continue
                if (v <= 0) continue
                if (v > bv) {
                    bv = v
                    best = e.key
                }
            }
        }
        return best
    }

    /* ═══════════ 预热：让面板一打开就有数字 ═══════════
     *
     * 原先只在用户点开面板时才起测，于是总要「测量中…」等几秒。
     * 改成后台提前测好：结果落盘，面板打开时先显示上次成绩，
     * 同时在后台刷新一轮 —— 数字立刻可见，且是新鲜的。
     */

    /** 预热结果的有效期：超过就重新测 */
    private val probeTtlMs = 10 * 60 * 1000L
    /** 上次预热完成的时间戳 */
    private val spProbeAt = "probe_at"
    /** 上次预热结果："key:bps|key:bps" */
    private val spProbeVal = "probe_val"

    /** 启动时调用：有缓存且还新鲜就直接用，否则后台静默测一轮 */
    fun warmup() {
        var at = 0L
        try {
            at = ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
                .getLong(spProbeAt, 0)
        } catch (ignored: Throwable) {
        }
        if (System.currentTimeMillis() - at < probeTtlMs) {
            loadCachedProbe()          // 有新鲜缓存，直接用
            return
        }
        /* 有正在下载的任务就不凑热闹 —— 别跟用户抢带宽 */
        if (hasActiveDownloads()) return
        /*
          后台预热只跑**一轮**，不进持续模式 —— 那时面板没开，没必要
          一直跑；跑一轮把成绩存下来，用户下次打开面板就能立刻看到数字。
        */
        synchronized(probeLock) { probing = false }
        runProbeRound()
    }

    private fun loadCachedProbe() {
        try {
            val sp = ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
            val raw = sp.getString(spProbeVal, "")
            if (raw.isNullOrEmpty()) return
            val m = HashMap<String, Long>()
            for (seg in raw.split("\\|".toRegex())) {
                if (seg.isEmpty()) continue
                val c = seg.lastIndexOf(':')
                if (c <= 0) continue
                try {
                    m[seg.substring(0, c)] = seg.substring(c + 1).toLong()
                } catch (ignored: NumberFormatException) {
                }
            }
            if (m.isNotEmpty()) {
                synchronized(probeLock) { probeBps = m }
                /* 缓存里的时间戳也恢复，否则进程重启后自动选择会以为没数据 */
                probeAt = sp.getLong(spProbeAt, 0)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun saveCachedProbe() {
        try {
            val sb = StringBuilder()
            synchronized(probeLock) {
                var first = true
                for (e in probeBps.entries) {
                    if (first) first = false else sb.append('|')
                    sb.append(e.key).append(':').append(e.value)
                }
            }
            ctx.activity.getSharedPreferences(JsBridge.PREF_DL, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(spProbeVal, sb.toString())
                .putLong(spProbeAt, System.currentTimeMillis())
                .apply()
        } catch (ignored: Throwable) {
        }
    }

    /* ═══════════════ 测速 ═══════════════ */

    /**
     * 开启**持续**测速：跑完一轮自动接着跑下一轮，速度数字一直在跳。
     *
     * 用户要的是「实时数据」—— 面板开着就一直在测，能看到各通道此刻的
     * 快慢，而不是测一次定终身。所以这里不是测完即止，而是循环。
     *
     * 必须调 stopChannelProbe() 收摊，否则面板关了线程还在跑（费电、费流量）。
     */
    @JavascriptInterface
    fun startChannelProbe() {
        synchronized(probeLock) {
            probing = true
            /* 用户主动打开面板：清空休眠，给所有通道一次机会。
               冷却只是为了避免死链拖慢轮询，不该拦着用户主动测。 */
            probeCool.clear()
            probeFails.clear()
            /*
              ⚠️ 这里**不要清空** probeBps。

              清空的话每轮一开始所有通道都变回「测量中」，界面整片闪一下
              再逐个出数字 —— 看着像卡了，也不是「实时」该有的样子。
              保留上一轮的值，新结果回来时逐个替换，数字直接跳变，
              这才是持续刷新。只有第一次打开（本来就没值）才会显示
              「测量中」。
            */
        }
        runProbeRound()
    }

    /** 停止持续测速。面板关闭时必须调 */
    @JavascriptInterface
    fun stopChannelProbe() {
        synchronized(probeLock) {
            probing = false
        }
        saveCachedProbe()
    }

    /** 跑一轮：所有通道并发，全部回来后如果还在「持续」就自动接下一轮 */
    private fun runProbeRound() {
        val keys = ArrayList<String>()
        for (m in DownloadChannels.MIRRORS) keys.add(m)
        keys.add(DownloadChannels.DIRECT)
        val left = AtomicInteger(keys.size)
        for (k in keys) {
            probePool.execute {
                /*
                  ⚠️ measureChannel() 必须在锁**外**跑。

                  它一次要花几秒（连接 + 读 2 秒窗口）。如果握着
                  probeLock 去调它，24 条通道就得一条一条排队 ——
                  并发线程池形同虚设，一轮要跑好几分钟。
                  只在更新结果那一小段加锁。
                */
                var sleep = false
                synchronized(probeLock) {
                    val cool = probeCool[k]
                    if (cool != null && cool > 0) {
                        /* 休眠中：直接判不可用，不发起连接。
                           省下的正是「等一个死链超时」的那几秒。 */
                        probeCool[k] = cool - 1
                        sleep = true
                    }
                }
                val bps = if (sleep) -1 else measureChannel(k)
                synchronized(probeLock) {
                    if (!sleep) {
                        if (bps < 0) {
                            val f = (probeFails[k] ?: 0) + 1
                            probeFails[k] = f
                            if (f >= probeFailMax) {
                                probeCool[k] = probeCoolRounds
                                probeFails[k] = 0
                            }
                        } else {
                            probeFails[k] = 0   // 活了就清零
                        }
                    }
                    val next = HashMap(probeBps)
                    next[k] = bps
                    probeBps = next
                }
                /* 全部跑完才落盘：写一半的结果没意义，而且下次读进来
                   会缺几条，面板上就又出现「测量中」。 */
                if (left.decrementAndGet() != 0) return@execute
                /* 整轮跑完，这批数据才算「新鲜」，可以拿去做自动选择 */
                probeAt = System.currentTimeMillis()
                saveCachedProbe()
                val goon: Boolean
                synchronized(probeLock) { goon = probing }
                if (!goon) return@execute
                /* 两轮之间喘口气：既避免把镜像打得太狠（都是免费服务），
                   也让用户有时间看清上一轮的数字。 */
                probeHandler.postDelayed({
                    val goon2: Boolean
                    synchronized(probeLock) { goon2 = probing }
                    if (goon2) runProbeRound()
                }, probeGapMs)
            }
        }
    }

    /** 测一条通道：连上、读一小段，用耗时算吞吐。失败/超时返回 -1 */
    private fun measureChannel(key: String): Long {
        val url: String = if (DownloadChannels.DIRECT == key) {
            probeTarget
        } else if (DownloadChannels.isTemplated(key)) {
            /*
              云速（jsdelivr）只代理仓库文件，拿 Release 附件去测必然 404。
              必须换一个 raw 类目标，否则面板上它会永远显示「不可用」——
              而实际上它是最快的那条（CI 实测 155MB/s）。
            */
            DownloadChannels.templatedUrl(probeTargetRaw) ?: return -1
        } else {
            key + probeTarget
        }
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.instanceFollowRedirects = true
            c.connectTimeout = probeBudgetMs
            c.readTimeout = probeBudgetMs
            c.setRequestProperty("User-Agent", "githup")
            /* 只要前几十 KB 就能算出吞吐，没必要把整个文件拉完 ——
               顺带避免「测个速度反而吃掉用户几十 MB 流量」 */
            c.setRequestProperty("Range", "bytes=0-" + (probeMaxBytes - 1))
            val code = c.responseCode
            /* 206 = 服务端支持断点续传（我们主动要了 Range），这是正常且更快的；
               200 = 不支持 Range，直接从头读也一样算。两者都算通。 */
            if (code != 200 && code != 206 && (code < 200 || code >= 400)) return -1
            var input: InputStream? = null
            val buf = ByteArray(16 * 1024)
            var got = 0L
            var firstByteAt = 0L
            var last = 0L
            try {
                input = c.inputStream
                /*
                  ⚠️ 计时的起点必须是**首字节到达**，不是「发起请求」。

                  这里踩过一个坑：上一版把 t0 取在 getResponseCode() 之前，
                  deadline = t0 + 3 秒。但 getResponseCode() 要完成 DNS、
                  TLS 握手、等响应头 —— 第三方镜像是跨国代理，光这一步
                  常常就要 2~4 秒。等它返回时 3 秒窗口已经用光，循环一次
                  都不执行，got 恒为 0，于是**所有通道**一律被判成
                  「不可用」。本来能用的加速 3 也跟着挂了。

                  连接慢 ≠ 通道死。所以：
                    · 连接 / 等响应头的耗时**不计入**速度窗口
                    · 首字节迟迟不来（超过 read timeout）才算真不通，
                      由 SocketTimeoutException 兜住
                    · 窗口从首字节起算，这段才真正反映吞吐
                */
                while (got < probeMaxBytes) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val now = SystemClock.elapsedRealtime()
                    if (got == 0L) {
                        firstByteAt = now                      // 首字节到达，开始计窗口
                    } else if (now - firstByteAt >= probeWindowMs) {
                        break                                  // 窗口到了，收
                    }
                    got += n
                    last = now
                }
            } finally {
                try {
                    input?.close()
                } catch (ignored: Throwable) {
                }
            }
            /* 一个字节都没读到 = 这条通道根本没在送数据，才算真不可用 */
            if (got <= 0) return -1
            /*
              正常情况 dt 会接近 3 秒（读满窗口）。只有流提前结束时 dt 才小。
              给个 200ms 的下限：只捞到一两包就结束的话，直接除会算出
              天文数字（16KB / 1ms = 16MB/s），那不是真实速度。
            */
            /*
              ⚠️ 下限 200ms + 上限 512KB，天花板只有 512KB/0.2s ≈ 2.6MB/s。

              快通道几十毫秒就把 512KB 读完了，dt 被硬拉到 200ms，
              算出来一律是 2.5 MB/s —— 面板上所有能用的通道都显示同一个
              数字，用户根本分不出哪条更快，「测速」就白测了。

              所以样本放大到 1MB、下限收到 100ms：天花板抬到约 10MB/s，
              快通道之间就能拉开差距。慢通道读不满 1MB 也不会多耗流量
              —— 窗口到了就收，不是非得读满。
            */
            var dt = last - firstByteAt
            if (dt < 100) dt = 100
            got * 1000L / dt
        } catch (ignored: Throwable) {
            -1
        } finally {
            try {
                c?.disconnect()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 通道列表 + 各自的实时速度。
     *
     * state: pending（还在测）/ ok / fail
     */
    @JavascriptInterface
    fun channelProbe(): String {
        val out = JSONArray()
        synchronized(probeLock) {
            var i = 0
            for (m in DownloadChannels.MIRRORS) {
                i++
                /* 第二组是未经实测的候选，界面上标注出来 —— 用户看到
                   「候选」就知道这条可能压根用不了，别误以为软件坏了。 */
                val verified = i <= DownloadChannels.VERIFIED_COUNT
                /* 名字统一走 channelLabel：第 1 条叫「云析通道」、第 2 条
                 * 「云速通道」，其后「加速1」「加速2」…。i 从 1 起，减 1 换下标。 */
                out.put(
                    channelItem(
                        m, DownloadChannels.channelLabel(i - 1), hostOf(m),
                        probeBps[m], verified
                    )
                )
            }
            out.put(
                channelItem(
                    DownloadChannels.DIRECT, "直连", "github.com",
                    probeBps[DownloadChannels.DIRECT], true
                )
            )
        }
        return out.toString()
    }

    private fun channelItem(
        key: String, name: String, host: String,
        bps: Long?, verified: Boolean
    ): JSONObject {
        val o = JSONObject()
        try {
            o.put("key", key)
            o.put("name", name)
            o.put("host", host)
            o.put("verified", verified)
            /* 适用范围（云速 = 「仅仓库文件」），前端原样显示 */
            val sc = DownloadChannels.scopeOf(key)
            if (!sc.isNullOrEmpty()) o.put("scope", sc)
            if (bps == null) {
                o.put("state", "pending")
                o.put("bps", 0)
            } else if (bps < 0) {
                o.put("state", "fail")
                o.put("bps", 0)
            } else {
                o.put("state", "ok")
                o.put("bps", bps)
            }
        } catch (ignored: Throwable) {
        }
        return o
    }

    /** "https://ghfast.top/" -> "ghfast.top" */
    private fun hostOf(mirrorPrefix: String): String {
        return try {
            var h = mirrorPrefix
            val i = h.indexOf("://")
            if (i >= 0) h = h.substring(i + 3)
            while (h.endsWith("/")) h = h.substring(0, h.length - 1)
            h
        } catch (ignored: Throwable) {
            mirrorPrefix
        }
    }

    /* ═══════════════ 候选地址 ═══════════════ */

    /**
     * 生成候选下载地址。具体规则（哪些域名可加速、带令牌必须直连）
     * 都在 DownloadChannels 里，那边是纯逻辑、能单测。
     *
     * 用户手动挑过通道的话，把那条顶到最前 —— 这是「客户选择哪个就走
     * 哪个通道」的落点。没挑过（自动）就保持固定顺序。
     */
    fun candidateUrls(url: String, allowMirror: Boolean): MutableList<String> {
        val c = DownloadChannels.candidates(url, allowMirror)
        var pref = readPreferredChannel()
        if (pref.isEmpty() || c.size <= 1) return c

        /*
          自动模式：**每次发车都重新查**当前最快的那条。

          这里就是「实时选择」的落点 —— 存的不是某条通道，而是 "auto"
          这个意图，所以实时数据变了，起步通道跟着变，不需要用户再点一次。

          查不到新鲜数据（从没测过 / 结果过期）就退回固定顺序，
          剩下的交给换道机制：走错了也会被 switchChannel 换掉。
        */
        val isAuto = "auto" == pref
        if (isAuto) {
            val best = fastestKnownChannel() ?: return c
            pref = best
        }

        /*
          ⚠️ 手动挑的通道可能**压根进不了候选链**。

          云速（jsdelivr）只代理仓库文件，下载 Release 附件时 buildUrl
          返回 null，candidates() 直接把它剔掉了。于是下面的循环一条都
          匹配不上，什么都不做 —— 候选链保持原样，下载照常成功。

          结果就是用户看到的怪事：明明选了云速、面板上它还标着「不可用」，
          东西却下载下来了。**因为压根没走它**。以前这里静默跳过，用户
          只能自己猜。现在说明白：它适用于哪类文件、这次实际走了哪条。
        */
        if (!isAuto && c.isNotEmpty()) {
            var hit = false
            for (u in c) {
                if (pref == DownloadChannels.channelKey(u)) {
                    hit = true
                    break
                }
            }
            if (!hit) {
                val why = if (DownloadChannels.isTemplated(pref))
                    "（这条只代理仓库里的文件，Release 附件用不上）" else ""
                val ri = indexOfKey(c[0])
                val real = if (ri < 0) "直连" else DownloadChannels.channelLabel(ri)
                ctx.toast("已选通道不适用于这个文件$why，本次改用$real")
                return c
            }
        }

        for (i in c.indices) {
            if (pref == DownloadChannels.channelKey(c[i])) {
                val hit = c.removeAt(i)
                c.add(0, hit)
                break
            }
        }
        return c
    }

    /**
     * 候选链第一条在 MIRRORS 里的下标（用来取名字）；直连返回 -1。
     *
     * 注意传进来的已经是**拼好的**地址（mirror + 原 URL），所以只能拿
     * host 反查 —— 再用 buildUrl 正推会拼成「前缀 + 前缀 + 原地址」，
     * 一条都匹配不上。
     */
    private fun indexOfKey(builtUrl: String): Int {
        val h = hostOf(builtUrl)
        if (h.isEmpty()) return -1
        for (i in DownloadChannels.MIRRORS.indices) {
            if (h == hostOf(DownloadChannels.MIRRORS[i])) return i
        }
        return -1
    }
}
