package com.hbtv.bridge

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class SyncValue<T> {
    private val latch = CountDownLatch(1)
    @Volatile private var value: T? = null

    fun set(v: T) {
        value = v
        latch.countDown()
    }

    fun get(timeout: Long, unit: TimeUnit): T? {
        latch.await(timeout, unit)
        return value
    }
}

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

class MainActivity : AppCompatActivity() {

    private lateinit var playerWebView: WebView
    private lateinit var tvCurrentPlaying: TextView
    private lateinit var channelDrawer: View
    private lateinit var rvChannels: RecyclerView
    private lateinit var logOverlay: View
    private lateinit var tvConsoleLogs: TextView
    private lateinit var logScrollView: ScrollView

    private var currentGroup = ChannelGroup.CCTV
    private lateinit var adapter: ChannelAdapter
    private var currentChannelIndex = 0

    // 单调递增的序号，防止被服务器风控拒绝
    private val seqCounter = AtomicLong(System.currentTimeMillis() / 1000)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var tokenRndFuture: SyncValue<String>? = null
    private var signatureFuture: SyncValue<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ServerManager.start(this)

        playerWebView = findViewById(R.id.playerWebView)
        tvCurrentPlaying = findViewById(R.id.tvCurrentPlaying)
        channelDrawer = findViewById(R.id.channelDrawer)
        rvChannels = findViewById(R.id.rvChannels)
        logOverlay = findViewById(R.id.logOverlay)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
        logScrollView = findViewById(R.id.logScrollView)

        val btnToggleDrawer = findViewById<Button>(R.id.btnToggleDrawer)
        val btnToggleLogs = findViewById<Button>(R.id.btnToggleLogs)
        val btnClearLogs = findViewById<TextView>(R.id.btnClearLogs)
        val btnCloseLogs = findViewById<TextView>(R.id.btnCloseLogs)

        val tabCctv = findViewById<Button>(R.id.tabCctv)
        val tabSatellite = findViewById<Button>(R.id.tabSatellite)
        val tabLocal = findViewById<Button>(R.id.tabLocal)

        LogManager.onLogListener = {
            runOnUiThread {
                tvConsoleLogs.text = LogManager.getAllLogs()
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        btnToggleLogs.setOnClickListener {
            logOverlay.visibility = if (logOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnCloseLogs.setOnClickListener { logOverlay.visibility = View.GONE }
        btnClearLogs.setOnClickListener {
            LogManager.clear()
            tvConsoleLogs.text = ""
        }

        try {
            val intent = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            // ignore
        }

        val hiddenContainer = findViewById<ViewGroup>(R.id.hiddenWebContainer)
        WebViewKeeper.init(this, hiddenContainer)

        initPlayerWebView()

        rvChannels.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter(getFilteredChannels()) { ch ->
            playChannel(ch)
            channelDrawer.visibility = View.GONE
        }
        rvChannels.adapter = adapter

        btnToggleDrawer.setOnClickListener {
            channelDrawer.visibility = if (channelDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val updateTabs = {
            tabCctv.setTextColor(if (currentGroup == ChannelGroup.CCTV) 0xFF2F6BFF.toInt() else 0xFF888888.toInt())
            tabSatellite.setTextColor(if (currentGroup == ChannelGroup.SATELLITE) 0xFF2F6BFF.toInt() else 0xFF888888.toInt())
            tabLocal.setTextColor(if (currentGroup == ChannelGroup.LOCAL) 0xFF2F6BFF.toInt() else 0xFF888888.toInt())
            adapter.updateData(getFilteredChannels())
        }

        tabCctv.setOnClickListener { currentGroup = ChannelGroup.CCTV; updateTabs() }
        tabSatellite.setOnClickListener { currentGroup = ChannelGroup.SATELLITE; updateTabs() }
        tabLocal.setOnClickListener { currentGroup = ChannelGroup.LOCAL; updateTabs() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initPlayerWebView() {
        playerWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = AuthSigner.Ua
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        playerWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    LogManager.log("[JS] ${it.message()}")
                }
                return true
            }
        }

        playerWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(jsonStr: String) {
                try {
                    val obj = JSONObject(jsonStr)
                    if (obj.has("log")) {
                        LogManager.log("[CMG] ${obj.getString("log")}")
                    }
                    if (obj.has("tokenRnd")) {
                        tokenRndFuture?.set(obj.getString("tokenRnd"))
                    }
                    if (obj.has("signature")) {
                        signatureFuture?.set(obj.getString("signature"))
                    }
                    if (obj.has("dblclick") || obj.has("menu")) {
                        runOnUiThread {
                            channelDrawer.visibility = if (channelDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }, "AndroidBridge")

        playerWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.contains("18888/player")) {
                    LogManager.log("player.served.html 加载成功")
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
                    playerWebView.evaluateJavascript(polyfill, null)
                    playChannel(ChannelRepository.channels.first())
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    playerWebView.postDelayed({
                        playerWebView.loadUrl("http://127.0.0.1:18888/player")
                    }, 500)
                }
            }
        }

        playerWebView.postDelayed({
            playerWebView.loadUrl("http://127.0.0.1:18888/player")
        }, 150)
    }

    private fun getFilteredChannels(): List<TvChannel> {
        return ChannelRepository.channels.filter { it.group == currentGroup }
    }

    private fun playChannel(ch: TvChannel) {
        tvCurrentPlaying.text = "${ch.name} (鉴权取流中...)"
        LogManager.log("▶ 开始取流: ${ch.name} (pid=${ch.pid})")

        if (ch.group == ChannelGroup.LOCAL) {
            val localM3u8 = "http://127.0.0.1:18888/hbtv/${ch.id}.m3u8"
            startHlsPlay(localM3u8)
            tvCurrentPlaying.text = "${ch.name} (播放中)"
            return
        }

        Thread {
            try {
                val m3u8 = fetchCctvM3u8(ch)
                runOnUiThread {
                    if (m3u8 != null) {
                        LogManager.log("取流成功，喂入播放器")
                        startHlsPlay(m3u8)
                        tvCurrentPlaying.text = "${ch.name} (解密播放中)"
                    } else {
                        tvCurrentPlaying.text = "${ch.name} (未获取到地址)"
                        LogManager.log("获取播放地址失败")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvCurrentPlaying.text = "${ch.name} 错误: ${e.message}"
                    LogManager.log("异常报错: ${e.message}")
                }
            }
        }.start()
    }

    // 注入与官方 APISIX 网关完全匹配的浏览器级请求头
    private fun applyBrowserHeaders(builder: Request.Builder, seqId: String, reqId: String): Request.Builder {
        return builder
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-site")
            .header("yspappid", AuthSigner.YspAppId)
            .header("seqid", seqId)
            .header("request-id", reqId)
            .header("Cookie", "${AuthSigner.Cookie} nseqId=$seqId; nrequest-id=$reqId")
    }

    private fun fetchCctvM3u8(ch: TvChannel): String? {
        val randStr = AuthSigner.randStr(10)
        val authSig = AuthSigner.computeAuthSignature(ch.pid, AuthSigner.Guid, randStr)
        val seqId = seqCounter.incrementAndGet().toString()
        val ts = System.currentTimeMillis().toString()
        val reqId = "999999" + AuthSigner.randStr(10) + ts

        // 1. POST /v1/player/auth (带全套网关指纹头)
        LogManager.log("1. 请求 /auth 鉴权...")
        val authBody = "pid=${ch.pid}&guid=${AuthSigner.Guid}&appid=ysp_pc&rand_str=$randStr&signature=$authSig"
        val authReq = applyBrowserHeaders(
            Request.Builder()
                .url("https://player-api.yangshipin.cn/v1/player/auth")
                .post(authBody.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType())),
            seqId, reqId
        ).build()

        val authResp = httpClient.newCall(authReq).execute()
        val authRes = authResp.body?.string() ?: ""
        LogManager.log("auth 返回 [HTTP ${authResp.code}]: ${authRes.take(70)}...")
        if (authResp.code != 200 || authRes.isEmpty()) return null

        val authJson = JSONObject(authRes)
        val token = authJson.optJSONObject("data")?.optString("token") ?: return null
        val authTs = authJson.optJSONObject("data")?.optString("ts") ?: (System.currentTimeMillis() / 1000).toString()

        // 2. JS 生成 tokenRnd -> GET /web/open/token
        LogManager.log("2. 计算 tokenRnd 并换取 sessionToken...")
        tokenRndFuture = SyncValue()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genTokenRnd('${AuthSigner.Guid}', '$token', '$ts')", null)
        }
        val rndVal = tokenRndFuture?.get(8, TimeUnit.SECONDS) ?: run {
            LogManager.log("tokenRnd 计算超时")
            return null
        }

        val openUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=${AuthSigner.YspAppId}&guid=${AuthSigner.Guid}&vappid=${AuthSigner.VappId}&vsecret=${AuthSigner.Vsecret}&raw=1&version=v1&ts=$ts&rnd=$rndVal"
        val openReq = Request.Builder().url(openUrl)
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .header("Accept", "*/*")
            .build()
        val openResp = httpClient.newCall(openReq).execute()
        val openRes = openResp.body?.string() ?: ""
        LogManager.log("openToken 返回 [HTTP ${openResp.code}]: ${openRes.take(70)}...")
        val sessionToken = JSONObject(openRes).optJSONObject("data")?.optString("token") ?: return null

        // 3. 动态 cKey (调用页面内 fb15 webpack 模块)
        LogManager.log("3. 生成 324位 cKey...")
        val cKeyFuture = SyncValue<String>()
        val safeUrl = "https://yangshipin.cn/tv/home?pid=${ch.pid}"
        val tsSec = (System.currentTimeMillis() / 1000).toString()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genCKey('${ch.cnlId}', '$tsSec', 'V1.0.0', '${AuthSigner.Guid}', '5910204', '$safeUrl')") { res ->
                val v = res?.trim('"', '\'', ' ') ?: ""
                cKeyFuture.set(v)
            }
        }
        val cKey = cKeyFuture.get(5, TimeUnit.SECONDS) ?: ""

        // 4. 动态 yspticket (调用页面内 RJq7sO71JF.wasm)
        LogManager.log("4. 生成 yspticket...")
        val ticketFuture = SyncValue<String>()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genYspTicket('${ch.pid}', '$authTs', '${ch.cnlId}', '${AuthSigner.Guid}', '${AuthSigner.YspAppId}', 'V1.0.0')") { res ->
                val v = res?.trim('"', '\'', ' ') ?: ""
                ticketFuture.set(v)
            }
        }
        val yspticket = ticketFuture.get(5, TimeUnit.SECONDS) ?: ""

        // 5. 计算 sdkInput 与 bodySig
        val randStrLive = AuthSigner.randStr(10)
        val liveFields = mutableMapOf(
            "cnlid" to ch.cnlId, "livepid" to ch.pid, "stream" to "2", "guid" to AuthSigner.Guid,
            "cKey" to cKey, "adjust" to "1", "sphttps" to "1", "platform" to "5910204", "cmd" to "2",
            "encryptVer" to "8.1", "dtype" to "1", "devid" to "devid", "otype" to "ojson",
            "appVer" to "V1.0.0", "app_version" to "V1.0.0", "channel" to "ysp_tx", "defn" to "fhd",
            "rand_str" to randStrLive
        )
        val yspsdkinput = AuthSigner.computeLiveSdkInput(liveFields)
        val bodySig = AuthSigner.computeLiveBodySignature(liveFields)

        // 6. 算 sig2
        LogManager.log("5. 计算 sig2 签名...")
        signatureFuture = SyncValue()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__generateSignature('${ch.pid}','${AuthSigner.Guid}','$seqId','$reqId','$sessionToken','$ts','$yspsdkinput')", null)
        }
        val sig2 = signatureFuture?.get(8, TimeUnit.SECONDS) ?: run {
            LogManager.log("sig2 计算超时")
            return null
        }

        // 7. POST /v1/player/get_live_info
        LogManager.log("6. 请求 get_live_info 获取直播流...")
        val bodyJson = JSONObject().apply {
            for ((k, v) in liveFields) {
                put(k, v)
            }
            put("signature", bodySig)
            put("adjust", 1)
        }.toString()

        val seqId2 = seqCounter.incrementAndGet().toString()
        val reqId2 = "999999" + AuthSigner.randStr(10) + System.currentTimeMillis().toString()

        val liveReqBuilder = Request.Builder()
            .url("https://player-api.yangshipin.cn/v1/player/get_live_info")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("yspplayertoken", token)
            .header("yspsdkinput", yspsdkinput)
            .header("yspsdksign", "$sig2-$yspsdkinput-${AuthSigner.Guid}-$seqId2-$reqId2")
            .header("yspticket", yspticket)

        val liveReq = applyBrowserHeaders(liveReqBuilder, seqId2, reqId2).build()

        val liveResp = httpClient.newCall(liveReq).execute()
        val liveRes = liveResp.body?.string() ?: ""
        LogManager.log("get_live_info 返回 [HTTP ${liveResp.code}]: ${liveRes.take(70)}...")

        val data = JSONObject(liveRes).optJSONObject("data") ?: return null
        val playUrl = data.optString("playurl")
        val ext = data.optString("extended_param", "")
        return if (playUrl.isNotEmpty()) playUrl + ext else null
    }

    private fun startHlsPlay(m3u8Url: String) {
        val safe = m3u8Url.replace("'", "\\'")
        playerWebView.evaluateJavascript("window.__startM3u8('$safe');", null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentList = getFilteredChannels()
        if (currentList.isEmpty()) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                currentChannelIndex = (currentChannelIndex - 1 + currentList.size) % currentList.size
                playChannel(currentList[currentChannelIndex])
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                currentChannelIndex = (currentChannelIndex + 1) % currentList.size
                playChannel(currentList[currentChannelIndex])
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                channelDrawer.visibility = if (channelDrawer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (logOverlay.visibility == View.VISIBLE) {
                    logOverlay.visibility = View.GONE
                    return true
                }
                if (channelDrawer.visibility == View.VISIBLE) {
                    channelDrawer.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    class ChannelAdapter(
        private var list: List<TvChannel>,
        private val onClick: (TvChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvChannelName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ch = list[position]
            holder.tvName.text = ch.name
            holder.itemView.setOnClickListener { onClick(ch) }

            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                view.setBackgroundColor(if (hasFocus) 0xFF2F6BFF.toInt() else 0xFF21213E.toInt())
            }
        }

        override fun getItemCount(): Int = list.size

        fun updateData(newList: List<TvChannel>) {
            list = newList
            notifyDataSetChanged()
        }
    }
}
