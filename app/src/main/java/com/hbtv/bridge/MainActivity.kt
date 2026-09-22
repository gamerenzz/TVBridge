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
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

// 纯静音脚本：保持播放器正常工作、正常向服务器维持心跳，但物理音量强制为 0
const val ALWAYS_MUTE_JS = """
    (function() {
        try {
            // 劫持所有 audio/video，强制静音，但绝不阻止 play() 流程
            Object.defineProperty(HTMLMediaElement.prototype, 'volume', {
                set: function() {},
                get: function() { return 0; }
            });
            Object.defineProperty(HTMLMediaElement.prototype, 'muted', {
                set: function() {},
                get: function() { return true; }
            });
            var mediaList = document.querySelectorAll('video, audio');
            for(var i = 0; i < mediaList.length; i++) {
                mediaList[i].muted = true;
                mediaList[i].volume = 0;
            }
        } catch(e) {}
    })();
"""

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
// 3. WebView 管理器 (保持播放拉流心跳 + 强制静音)
// ==========================================
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

                for (ch in CHANNELS) {
                    if (webViewMap.containsKey(ch.id)) continue
                    val wv = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // 允许页面播放，否则 Hls.js 不拉流不更新 Token
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = PC_UA
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE

                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // 在最早阶段注入静音，防止出声
                                view?.evaluateJavascript(ALWAYS_MUTE_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(ALWAYS_MUTE_JS, null)
                                LogManager.log("[${ch.name}] 页面就绪，准备取流...")
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
                LogManager.log("WebView初始化异常: ${e.message}")
            }
        }
    }

    private fun scheduleCheck(cid: String, attempts: Int) {
        if (attempts > 30) {
            val name = CHANNELS.find { it.id == cid }?.name ?: cid
            LogManager.log("[$name] 抓取超时，重试中...")
            webViewMap[cid]?.reload()
            return
        }

        mainHandler.postDelayed({
            val wv = webViewMap[cid] ?: return@postDelayed

            // 保持正常播放（维持 CDN 连接），但强制设置 muted=true，绝不 pause
            val js = """
                (function(){
                    try {
                        var v = document.querySelector('video');
                        if (v) {
                            v.muted = true;
                            v.volume = 0;
                            try { v.play(); } catch(e){} // 保持播放器拉取切片
                            var s = v.currentSrc || v.src || '';
                            if (s && s.length > 5 && s.indexOf('http') === 0) {
                                return s;
                            }
                        }
                    } catch(e) {}
                    return '';
                })()
            """.trimIndent()

            wv.evaluateJavascript(js) { res ->
                val cleaned = res?.trim('"', '\'', ' ') ?: ""
                val name = CHANNELS.find { it.id == cid }?.name ?: cid
                if (cleaned.isNotEmpty() && cleaned.startsWith("http")) {
                    liveUrls[cid] = cleaned
                    LogManager.log("[$name] 抓取成功: ${cleaned.take(45)}...")
                } else {
                    if (attempts % 5 == 0) {
                        LogManager.log("[$name] 解析中 (尝试 $attempts/30)...")
                    }
                    scheduleCheck(cid, attempts + 1)
                }
            }
        }, 1200)
    }

    private fun schedulePeriodicReload() {
        mainHandler.postDelayed({
            LogManager.log("执行 20 分钟定时换新续期...")
            for ((_, wv) in webViewMap) {
                wv.reload()
            }
            schedulePeriodicReload()
        }, 20 * 60 * 1000L)
    }

    fun destroy() {
        mainHandler.post {
            for ((_, wv) in webViewMap) {
                try {
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (e: Exception) {}
            }
            webViewMap.clear()
            liveUrls.clear()
        }
    }
}

// ==========================================
// 4. 内置轻量 HTTP 代理服务器 (流式传输，修复播放阻断)
// ==========================================
class LocalProxyServer(port: Int) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val referer = "https://news.hbtv.com.cn/"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters

        return try {
            when {
                // 1. 播放列表文件
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in CHANNELS) {
                        m3u.append("#EXTINF:-1 group-title=\"湖北电视\",${ch.name}\n")
                        m3u.append("http://127.0.0.1:8899/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. TS 切片透传代理 (优化内存，改为输入流转发)
                uri == "/ts" -> {
                    val rawUrl = params["u"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    val upstreamUrl = URLDecoder.decode(rawUrl, "UTF-8")

                    // 兼容湖北所有官方 CDN 域名校验
                    if (!upstreamUrl.contains("hbtv.com.cn")) {
                        LogManager.log("拦截非法TS请求: $upstreamUrl")
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden Host")
                    }

                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", referer)
                        .header("User-Agent", PC_UA)
                        .build()

                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) {
                        LogManager.log("拉取分片失败 [HTTP ${resp.code}]: $upstreamUrl")
                        return newFixedLengthResponse(Response.Status.lookup(resp.code), "text/plain", "Upstream Error")
                    }

                    val body = resp.body ?: return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "No Body")
                    val inputStream = body.byteStream()
                    val contentLength = body.contentLength()

                    // 使用 Chunked / 固定长度响应
                    if (contentLength > 0) {
                        newFixedLengthResponse(Response.Status.OK, "video/mp2t", inputStream, contentLength)
                    } else {
                        newChunkedResponse(Response.Status.OK, "video/mp2t", inputStream)
                    }
                }

                // 3. m3u8 实时改写与下发
                uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/").removeSuffix(".m3u8")
                    val liveUrl = WebViewKeeper.getUrl(cid)
                    if (liveUrl.isNullOrEmpty()) {
                        LogManager.log("[$cid] 频道流尚未就绪，请稍候重试")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready yet")
                    }

                    val req = Request.Builder().url(liveUrl)
                        .header("Referer", referer)
                        .header("User-Agent", PC_UA)
                        .build()
                    val resp = client.newCall(req).execute()
                    val rawM3u8 = resp.body?.string() ?: ""

                    if (rawM3u8.isEmpty()) {
                        LogManager.log("[$cid] 上游返回空 m3u8")
                        return newFixedLengthResponse(Response.Status.BAD_GATEWAY, "text/plain", "Empty Upstream M3U8")
                    }

                    // 取上级基础 URL 用于补全切片的相对路径
                    val baseUrl = liveUrl.substringBeforeLast("/") + "/"

                    val modifiedM3u8 = rawM3u8.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            // 处理相对路径与绝对路径
                            val absoluteTs = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                trimmed
                            } else {
                                baseUrl + trimmed
                            }
                            // 代理转接到本地 /ts
                            "http://127.0.0.1:8899/ts?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
                        } else {
                            line
                        }
                    }

                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedM3u8)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("中继错误 [${e.javaClass.simpleName}]: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
}

// ==========================================
// 5. 后台前台保活服务
// ==========================================
class BridgeService : Service() {

    private var server: LocalProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundNotification()
            server = LocalProxyServer(8899).apply { start() }
            LogManager.log("本地中继服务已在 127.0.0.1:8899 启动")
            WebViewKeeper.init(this)
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
                .setContentText("127.0.0.1:8899 正在中继 (静音就绪)")
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
        WebViewKeeper.destroy()
        LogManager.log("服务已关闭")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ==========================================
// 6. 主界面
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
                        LogManager.log("正在唤醒 6 个后台静音标签页并抓流...")
                        WebViewKeeper.init(this, hiddenContainer)
                    } else {
                        stopService(intent)
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
}
