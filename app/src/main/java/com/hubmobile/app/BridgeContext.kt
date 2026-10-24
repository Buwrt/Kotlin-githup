package com.hubmobile.app

import android.app.Activity
import android.webkit.WebView
import java.util.concurrent.ExecutorService

/**
 * 拆出来的桥接模块共用的一小份"宿主能力"。
 *
 * 为什么需要它 ——
 *   JsBridge 原来是一个 7000 行的类：网络、下载、日志、令牌、TOTP、剪贴板
 *   全挤在一起，改一段逻辑得先在里面翻半天。拆成模块之后，每个模块真正
 *   需要的其实只有几样东西：Activity（起界面、读偏好）、一个后台线程池
 *   （耗时的活儿不能卡住 JS 线程）、往前端发代码的出口，以及当前的 WebView。
 *
 *   把这几样收在这里统一发下去，模块就不必认识 JsBridge 本身 ——
 *   也就不会出现「为了调一个方法又被塞回那个巨型类」的情况。
 *
 *   线程池是 JsBridge 手上那个 8 线程的网络池，不是新建的：
 *   线程是 GC root，每个模块各起一个池等于白占内存，而且并发请求数会
 *   悄悄翻几倍（翻译引擎并行探测 5 家时尤其明显）。
 *
 *   WebView 之所以是个函数而不是一个值：渲染进程被系统回收后 MainActivity
 *   会换一个新的 WebView 上来，存值的话拿到的就是那个已经废掉的实例。
 */
internal class BridgeContext(
    val activity: Activity,
    val pool: ExecutorService,
    private val jsSink: (String) -> Unit,
    private val webViewRef: () -> WebView? = { null },
    private val toastSink: (String) -> Unit = {}
) {

    /** 当前的 WebView，可能为 null（页面已销毁）。 */
    val webView: WebView?
        get() = webViewRef()

    /** 往前端发一段 JS。内部自己切回 UI 线程，调用方不必关心线程。 */
    fun runJs(js: String) = jsSink(js)

    /**
     * 弹一条 Toast，并顺手把「像是出错」的那类提示记进错误日志。
     *
     * 走 JsBridge 那条统一的出口，是为了让「同一句只记一次」的去重表
     * 只有一份 —— 拆成模块之后，如果每个模块各记各的，自动重试能把
     * 同一句刷上百遍，把真正有价值的首条淹掉。
     */
    fun toast(msg: String) = toastSink(msg)

    /**
     * 丢一段任务到后台线程池。
     *
     * 池子已经关闭（比如 Activity 正在销毁）时静默放弃：
     * 这类任务大多是「写日志」「发回调」，失败了也不该把异常抛回 JS 线程。
     */
    fun background(block: () -> Unit) {
        try {
            pool.execute(block)
        } catch (ignored: Throwable) {
        }
    }
}
