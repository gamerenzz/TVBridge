package com.hbtv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import java.util.concurrent.ConcurrentHashMap

const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

val HBTV_CHANNELS = ChannelRepository.channels.filter { it.group == ChannelGroup.LOCAL }

@SuppressLint("SetJavaScriptEnabled")
object WebViewKeeper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewMap = mutableMapOf<String, WebView>()
    private val liveUrls = ConcurrentHashMap<String, String>()

    fun getUrl(cid: String): String? = liveUrls[cid]

    fun init(context: Context, container: ViewGroup? = null) {
        mainHandler.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)

                for (ch in HBTV_CHANNELS) {
                    if (webViewMap.containsKey(ch.id)) continue
                    val wv = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = PC_UA
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                LogManager.log("[${ch.name}] 页面就绪，启动抓流...")
                                scheduleCheck(ch.id, 1)
                            }
                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.proceed()
                            }
                        }

                        onResume()
                        resumeTimers()
                        loadUrl("https://news.hbtv.com.cn/app/tv/${ch.id}")
                    }

                    container?.addView(wv)
                    webViewMap[ch.id] = wv
                }
                schedulePeriodicReload()
            } catch (e: Exception) {
                LogManager.log("WebViewKeeper 异常: ${e.message}")
            }
        }
    }

    private fun scheduleCheck(cid: String, attempts: Int) {
        if (attempts > 20) {
            webViewMap[cid]?.reload()
            return
        }

        mainHandler.postDelayed({
            val wv = webViewMap[cid] ?: return@postDelayed
            val js = """
                (function(){
                    var v = document.querySelector('video');
                    if (v) {
                        v.muted = true;
                        try { v.play(); } catch(e){}
                        if (v.currentSrc && v.currentSrc.length > 5) return v.currentSrc;
                        if (v.src && v.src.length > 5) return v.src;
                    }
                    return '';
                })()
            """.trimIndent()

            wv.evaluateJavascript(js) { res ->
                val cleaned = res?.trim('"', '\'', ' ') ?: ""
                val name = HBTV_CHANNELS.find { it.id == cid }?.name ?: cid
                if (cleaned.isNotEmpty() && cleaned.startsWith("http")) {
                    liveUrls[cid] = cleaned
                    LogManager.log("[$name] 抓流成功: ${cleaned.take(45)}...")
                } else {
                    scheduleCheck(cid, attempts + 1)
                }
            }
        }, 2000)
    }

    private fun schedulePeriodicReload() {
        mainHandler.postDelayed({
            for ((_, wv) in webViewMap) {
                wv.reload()
            }
            schedulePeriodicReload()
        }, 20 * 60 * 1000L)
    }

    fun destroy() {
        mainHandler.post {
            for ((_, wv) in webViewMap) {
                wv.destroy()
            }
            webViewMap.clear()
            liveUrls.clear()
        }
    }
}
