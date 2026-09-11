package com.hbtv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("SetJavaScriptEnabled")
object WebViewKeeper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewMap = mutableMapOf<String, WebView>()
    private val liveUrls = ConcurrentHashMap<String, String>()

    fun getUrl(cid: String): String? = liveUrls[cid]

    fun init(context: Context) {
        mainHandler.post {
            for (ch in CHANNELS) {
                if (webViewMap.containsKey(ch.id)) continue
                val wv = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            LogManager.log("[${ch.name}] 页面已加载，开始抓取流...")
                            scheduleCheck(ch.id)
                        }
                    }
                    loadUrl("https://news.hbtv.com.cn/app/tv/${ch.id}")
                }
                webViewMap[ch.id] = wv
            }
            // 每隔 20 分钟执行一次 reload()，防止 30 分钟硬过期
            schedulePeriodicReload()
        }
    }

    private fun scheduleCheck(cid: String, attempts: Int = 0) {
        if (attempts > 10) return
        mainHandler.postDelayed({
            val wv = webViewMap[cid] ?: return@postDelayed
            val js = "(function(){ var v=document.querySelector('video'); if(v){ v.muted=true; try{v.play();}catch(e){} return v.currentSrc; } return ''; })()"
            wv.evaluateJavascript(js) { res ->
                val cleaned = res?.trim('"', '\'', ' ') ?: ""
                if (cleaned.isNotEmpty() && cleaned.startsWith("http")) {
                    liveUrls[cid] = cleaned
                    val name = CHANNELS.find { it.id == cid }?.name ?: cid
                    LogManager.log("[$name] 成功获取地址: ${cleaned.take(45)}...")
                } else {
                    scheduleCheck(cid, attempts + 1)
                }
            }
        }, 3000)
    }

    private fun schedulePeriodicReload() {
        mainHandler.postDelayed({
            LogManager.log("开始每 20 分钟定时换新...")
            for ((cid, wv) in webViewMap) {
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
