package com.hbtv.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object CmgEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    @SuppressLint("StaticFieldLeak")
    private var headLessWebView: WebView? = null
    @Volatile private var isInitialized = false

    @Volatile private var tokenRndLatch: CountDownLatch? = null
    @Volatile private var tokenRndVal: String = ""

    @Volatile private var sig2Latch: CountDownLatch? = null
    @Volatile private var sig2Val: String = ""

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return

        mainHandler.post {
            try {
                LogManager.log("[CmgEngine] 启动后台计算内核...")
                headLessWebView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.userAgentString = AuthSigner.Ua
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun postMessage(jsonStr: String) {
                            try {
                                val obj = JSONObject(jsonStr)
                                if (obj.has("tokenRnd")) {
                                    tokenRndVal = obj.getString("tokenRnd")
                                    tokenRndLatch?.countDown()
                                }
                                if (obj.has("signature")) {
                                    sig2Val = obj.getString("signature")
                                    sig2Latch?.countDown()
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val polyfill = """
                                if (!window.chrome) window.chrome = {};
                                if (!window.chrome.webview) {
                                    window.chrome.webview = {
                                        postMessage: function(msg) {
                                            var s = (typeof msg === 'string') ? msg : JSON.stringify(msg);
                                            AndroidBridge.postMessage(s);
                                        }
                                    };
                                }
                            """.trimIndent()
                            evaluateJavascript(polyfill, null)
                            isInitialized = true
                            LogManager.log("[CmgEngine] 内核就绪 (18888/player)")
                        }
                    }

                    loadUrl("http://127.0.0.1:18888/player")
                }
            } catch (e: Exception) {
                LogManager.log("[CmgEngine] 初始化异常: ${e.message}")
            }
        }
    }

    fun isReady(): Boolean = isInitialized

    // 生成 tokenRnd
    fun genTokenRnd(guid: String, token: String, ts: String): String {
        tokenRndVal = ""
        tokenRndLatch = CountDownLatch(1)
        mainHandler.post {
            headLessWebView?.evaluateJavascript("window.__genTokenRnd('$guid', '$token', '$ts');", null)
        }
        tokenRndLatch?.await(5, TimeUnit.SECONDS)
        return tokenRndVal
    }

    // 生成 324位 cKey
    fun generateCKey(cnlId: String, tsSec: String, pid: String): String {
        val latch = CountDownLatch(1)
        var res = ""
        val safeUrl = "https://yangshipin.cn/tv/home?pid=$pid"
        mainHandler.post {
            headLessWebView?.evaluateJavascript("window.__genCKey('$cnlId', '$tsSec', 'V1.0.0', '${AuthSigner.Guid}', '5910204', '$safeUrl');") { v ->
                res = v?.trim('"', '\'', ' ') ?: ""
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return res
    }

    // 生成 yspticket
    fun generateYspTicket(pid: String, authTs: String, cnlId: String): String {
        val latch = CountDownLatch(1)
        var res = ""
        mainHandler.post {
            headLessWebView?.evaluateJavascript("window.__genYspTicket('$pid', '$authTs', '$cnlId', '${AuthSigner.Guid}', '${AuthSigner.YspAppId}', 'V1.0.0');") { v ->
                res = v?.trim('"', '\'', ' ') ?: ""
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return res
    }

    // 生成 sig2
    fun generateSig2(pid: String, guid: String, seqId: String, reqId: String, sessionToken: String, ts: String, yspsdkinput: String): String {
        sig2Val = ""
        sig2Latch = CountDownLatch(1)
        mainHandler.post {
            headLessWebView?.evaluateJavascript("window.__generateSignature('$pid','$guid','$seqId','$reqId','$sessionToken','$ts','$yspsdkinput');", null)
        }
        sig2Latch?.await(5, TimeUnit.SECONDS)
        return sig2Val
    }

    fun decryptTsInPlace(tsData: ByteArray): ByteArray {
        return tsData
    }

    @Synchronized
    fun destroy() {
        mainHandler.post {
            headLessWebView?.destroy()
            headLessWebView = null
            isInitialized = false
        }
    }
}
