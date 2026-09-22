package com.hbtv.bridge

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.hbtv.bridge.R
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// ==========================================
// 1. 频道数据定义
// ==========================================
data class Channel(val id: String, val code: String, val name: String)

val CHANNELS = listOf(
    Channel("431", "hbws", "湖北卫视"),
    Channel("432", "hbjs", "湖北经视"),
    Channel("433", "hbzh", "湖北综合"),
    Channel("435", "hbys", "湖北影视"),
    Channel("437", "hbjy", "湖北教育"),
    Channel("438", "hbls", "垄上频道")
)

const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// ==========================================
// 2. 全局日志管理器
// ==========================================
object LogManager {
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logList = CopyOnWriteArrayList<String>()
    var onLogListener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val entry = "[${sdf.format(Date())}] $msg"
        if (logList.size > 300) logList.removeAt(0)
        logList.add(entry)
        onLogListener?.invoke(entry)
    }

    fun getAllLogs(): String = logList.joinToString("\n")
    fun clear() = logList.clear()
}

// ==========================================
// 3. 6个 WebView 常驻管理器 (彻底断音版)
// ==========================================
@SuppressLint("SetJavaScriptEnabled")
object WebViewKeeper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewMap = mutableMapOf<String, WebView>()
    private val liveUrls = ConcurrentHashMap<String, String>()

    // 网页级静音拦截代码，直接锁定所有音量为 0
    private const val MUTE_SCRIPT = """
        (function() {
            try {
                // 1. 强制锁死媒体标签
                var fixMedia = function(m) {
                    m.muted = true;
                    m.volume = 0;
                };
                document.querySelectorAll('video, audio').forEach(fixMedia);
                
                // 2. 覆盖原型，网页代码调用 volume=1 或 muted=false 将直接无效
                Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                    get: function() { return true; },
                    set: function() {},
                    configurable: true
                });
                Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                    get: function() { return 0; },
                    set: function() {},
                    configurable: true
                });
            } catch(e) {}
        })();
    """

    fun getUrl(cid: String): String? = liveUrls[cid]

    fun init(context: Context, container: ViewGroup?) {
        mainHandler.post {
            try {
                CookieManager.getInstance().setAcceptCookie(true)

                for (ch in CHANNELS) {
                    if (webViewMap.containsKey(ch.id)) continue

                    val wv = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // 关键：必须手势才能发声
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = PC_UA
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                view?.evaluateJavascript(MUTE_SCRIPT, null)
                            }

                            // 关键拦截：WebView 仅仅需要执行 JS 得到 m3u8 地址，
                            // 坚决不给 WebView 自身下载媒体流/切片的机会，从根源掐死声音数据传输！
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val path = request?.url?.toString()?.lowercase() ?: ""
                                if (path.endsWith(".ts") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".mp3")) {
                                    // 直接阻断网页内部的音视频切片拉取
                                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(MUTE_SCRIPT, null)
                                if (url != null && url.contains("/app/tv/")) {
                                    LogManager.log("[${ch.name}] 页面就绪，嗅探直播流...")
                                    scheduleCheck(ch.id, 1)
                                }
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
                LogManager.log("WebView初始化异常: ${e.message}")
            }
        }
    }

    private fun scheduleCheck(cid: String, attempts: Int) {
        if (attempts > 20) {
            val name = CHANNELS.find { it.id == cid }?.name ?: cid
            LogManager.log("[$name] 抓取超时，重载中...")
            webViewMap[cid]?.loadUrl("https://news.hbtv.com.cn/app/tv/$cid")
            return
        }

        mainHandler.postDelayed({
            val wv = webViewMap[cid] ?: return@postDelayed

            val js = """
                (function(){
                    try {
                        var v = document.querySelector('video');
                        if (v) {
                            v.muted = true;
                            v.volume = 0;
                            if (v.currentSrc && v.currentSrc.length > 5) return v.currentSrc;
                            if (v.src && v.src.length > 5) return v.src;
                            try { v.play(); } catch(e){}
                        }
                    } catch(e){}
                    return '';
                })()
            """.trimIndent()

            wv.evaluateJavascript(MUTE_SCRIPT, null)
            wv.evaluateJavascript(js) { res ->
                val cleaned = res?.trim('"', '\'', ' ') ?: ""
                val name = CHANNELS.find { it.id == cid }?.name ?: cid

                if (cleaned.isNotEmpty() && cleaned.startsWith("http")) {
                    liveUrls[cid] = cleaned
                    LogManager.log("[$name] 抓取成功: ${cleaned.take(45)}...")

                    // 拿到地址，立即暂停视频并清空，防止网页端持续播放
                    wv.evaluateJavascript("""
                        (function(){
                            try {
                                var v = document.querySelector('video');
                                if (v) {
                                    v.pause();
                                    v.src = '';
                                }
                            }catch(e){}
                        })()
                    """.trimIndent(), null)
                    wv.stopLoading()

                } else {
                    if (attempts % 4 == 0) {
                        LogManager.log("[$name] 正在解析播放流 (尝试 $attempts/20)...")
                    }
                    scheduleCheck(cid, attempts + 1)
                }
            }
        }, 2000)
    }

    private fun schedulePeriodicReload() {
        mainHandler.postDelayed({
            LogManager.log("执行 20 分钟定时换新续期...")
            for ((cid, wv) in webViewMap) {
                wv.loadUrl("https://news.hbtv.com.cn/app/tv/$cid")
            }
            schedulePeriodicReload()
        }, 20 * 60 * 1000L)
    }

    fun destroy() {
        mainHandler.post {
            for ((_, wv) in webViewMap) {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
            webViewMap.clear()
            liveUrls.clear()
        }
    }
}

// ==========================================
// 4. 内置轻量 HTTP 代理服务器 (补 Referer + 重写分片)
// ==========================================
class LocalProxyServer(port: Int) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val referer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters

        return try {
            when {
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in CHANNELS) {
                        m3u.append("#EXTINF:-1 group-title=\"湖北电视\",${ch.name}\n")
                        m3u.append("http://127.0.0.1:8899/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                uri == "/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    if (!tsPattern.matcher(upstreamUrl).find()) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Bad host")
                    }
                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", referer)
                        .header("User-Agent", PC_UA)
                        .build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/").removeSuffix(".m3u8")
                    val liveUrl = WebViewKeeper.getUrl(cid)
                    if (liveUrl.isNullOrEmpty()) {
                        LogManager.log("[$cid] 尚未就绪，重试中...")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready yet")
                    }

                    val req = Request.Builder().url(liveUrl)
                        .header("Referer", referer)
                        .header("User-Agent", PC_UA)
                        .build()
                    val resp = client.newCall(req).execute()
                    val rawM3u8 = resp.body?.string() ?: ""

                    val baseUrl = liveUrl.substringBeforeLast("/") + "/"
                    val modifiedM3u8 = rawM3u8.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val absoluteTs = if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                            "/ts?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
                        } else {
                            line
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedM3u8)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("代理请求异常: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
}

// ==========================================
// 5. 后台前台保活服务 (纯净中继，绝不包含任何 WebView 逻辑)
// ==========================================
class BridgeService : Service() {

    private var server: LocalProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundNotification()
            server = LocalProxyServer(8899).apply { start() }
            LogManager.log("本地中继服务已在 127.0.0.1:8899 启动")
            // 彻底移除此处的 WebViewKeeper.init(this)！杜绝无界面后台漏音！
        } catch (e: Throwable) {
            LogManager.log("Service启动异常: ${e.message}")
        }
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "hbtv_bridge_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(channelId, "HBTV直播服务", NotificationManager.IMPORTANCE_LOW)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(chan)
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("长江云直播助手运行中")
                .setContentText("127.0.0.1:8899 正在中继")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(1, notification)
        } catch (e: Throwable) {
            LogManager.log("通知栏初始化警告: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        LogManager.log("服务已关闭")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ==========================================
// 6. 主界面 (唯一负责 WebViewKeeper 挂载与销毁的地方)
// ==========================================
class MainActivity : AppCompatActivity() {

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            val btnToggle = findViewById<Button>(R.id.btnToggle)
            val btnCopy = findViewById<Button>(R.id.btnCopy)
            val btnClear = findViewById<Button>(R.id.btnClear)
            val tvLogs = findViewById<TextView>(R.id.tvLogs)
            val scrollView = findViewById<ScrollView>(R.id.scrollView)
            val hiddenContainer = findViewById<ViewGroup>(R.id.hiddenWebContainer)

            LogManager.onLogListener = {
                runOnUiThread {
                    tvLogs.text = LogManager.getAllLogs()
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }

            btnToggle.setOnClickListener {
                val intent = Intent(this, BridgeService::class.java)
                try {
                    if (!isRunning) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        btnToggle.text = "停止服务"
                        btnToggle.setBackgroundColor(0xFFD32F2F.toInt())
                        isRunning = true
                        LogManager.log("正在唤醒 6 个后台标签页并抓流...")
                        // 唯一入口：必须在此处注入，并受 hiddenContainer 强力控制
                        WebViewKeeper.init(this, hiddenContainer)
                    } else {
                        stopService(intent)
                        WebViewKeeper.destroy()
                        btnToggle.text = "启动服务"
                        btnToggle.setBackgroundColor(0xFF2196F3.toInt())
                        isRunning = false
                        LogManager.log("服务已停止")
                    }
                } catch (e: Throwable) {
                    LogManager.log("启动服务捕获错误: ${e.message}")
                }
            }

            btnCopy.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Logs", LogManager.getAllLogs())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }

            btnClear.setOnClickListener {
                LogManager.clear()
                tvLogs.text = ""
            }

            LogManager.log("软件就绪，点击【启动服务】开始。")

        } catch (e: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("界面报错")
                .setMessage(e.stackTraceToString())
                .setPositiveButton("确定", null)
                .show()
        }
    }

    override fun onDestroy() {
        // 界面完全退出时清理 WebView 资源
        WebViewKeeper.destroy()
        super.onDestroy()
    }
}
