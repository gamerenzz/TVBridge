package com.hbtv.bridge

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
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

// ==========================================
// 2. 全局日志管理器 (支持界面刷新)
// ==========================================
object LogManager {
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logList = CopyOnWriteArrayList<String>()
    var onLogListener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val entry = "[${sdf.format(Date())}] $msg"
        if (logList.size > 200) logList.removeAt(0)
        logList.add(entry)
        onLogListener?.invoke(entry)
    }

    fun getAllLogs(): String = logList.joinToString("\n")
    fun clear() = logList.clear()
}

// ==========================================
// 3. 6个 WebView 常驻心跳与定时续期池
// ==========================================
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
            schedulePeriodicReload()
        }
    }

    private fun scheduleCheck(cid: String, attempts: Int = 0) {
        if (attempts > 12) return
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
            LogManager.log("定时任务: 执行 20 分钟换新续期...")
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
                // 播放列表接口
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in CHANNELS) {
                        m3u.append("#EXTINF:-1 group-title=\"湖北电视\",${ch.name}\n")
                        m3u.append("http://127.0.0.1:8899/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // TS 分片中继
                uri == "/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    if (!tsPattern.matcher(upstreamUrl).find()) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Bad host")
                    }
                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", referer)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                // M3U8 列表转发与重写
                uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/").removeSuffix(".m3u8")
                    val liveUrl = WebViewKeeper.getUrl(cid)
                    if (liveUrl.isNullOrEmpty()) {
                        LogManager.log("[$cid] 尚未就绪或地址获取中")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready yet")
                    }

                    val req = Request.Builder().url(liveUrl)
                        .header("Referer", referer)
                        .header("User-Agent", "Mozilla/5.0")
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
            LogManager.log("代理处理异常: ${e.message}")
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
        startForegroundNotification()
        try {
            server = LocalProxyServer(8899).apply { start() }
            LogManager.log("本地中继服务已在 127.0.0.1:8899 启动")
        } catch (e: Exception) {
            LogManager.log("启动 8899 端口失败: ${e.message}")
        }
        WebViewKeeper.init(this)
    }

    private fun startForegroundNotification() {
        val channelId = "hbtv_bridge_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "HBTV直播服务", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("长江云直播助手运行中")
            .setContentText("端口: 8899 正在中继")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, notification)
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
// 6. 主界面 (带闪退保护与日志滚动)
// ==========================================
class MainActivity : AppCompatActivity() {

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 注册全局异常捕获
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle("软件启动/运行异常")
                        .setMessage(throwable.stackTraceToString())
                        .setPositiveButton("确定", null)
                        .show()
                } catch (_: Exception) {}
            }
        }

        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_main)

            val btnToggle = findViewById<Button>(R.id.btnToggle)
            val btnClear = findViewById<Button>(R.id.btnClear)
            val tvLogs = findViewById<TextView>(R.id.tvLogs)
            val scrollView = findViewById<ScrollView>(R.id.scrollView)

            LogManager.onLogListener = {
                runOnUiThread {
                    tvLogs.text = LogManager.getAllLogs()
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }

            btnToggle.setOnClickListener {
                val intent = Intent(this, BridgeService::class.java)
                if (!isRunning) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    btnToggle.text = "停止服务"
                    btnToggle.setBackgroundColor(0xFFD32F2F.toInt())
                    isRunning = true
                    LogManager.log("正在唤醒 6 个后台标签页并启动服务...")
                } else {
                    stopService(intent)
                    btnToggle.text = "启动服务"
                    btnToggle.setBackgroundColor(0xFF2196F3.toInt())
                    isRunning = false
                }
            }

            btnClear.setOnClickListener {
                LogManager.clear()
                tvLogs.text = ""
            }

            LogManager.log("长江云直播助手就绪，点击【启动服务】即可开始。")

        } catch (e: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("界面初始化失败")
                .setMessage(e.stackTraceToString())
                .setPositiveButton("退出", null)
                .show()
        }
    }
}
