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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object LogManager {
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logList = CopyOnWriteArrayList<String>()
    var onLogListener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val entry = "[${sdf.format(Date())}] $msg"
        if (logList.size > 100) logList.removeAt(0)
        logList.add(entry)
        onLogListener?.invoke(entry)
    }

    fun getAllLogs(): String = logList.joinToString("\n")
}

class MainActivity : AppCompatActivity() {

    private lateinit var playerWebView: WebView
    private lateinit var tvCurrentPlaying: TextView
    private lateinit var channelDrawer: View
    private lateinit var rvChannels: RecyclerView

    private var currentGroup = ChannelGroup.CCTV
    private lateinit var adapter: ChannelAdapter
    private var currentChannelIndex = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 跨平台桥接 Future
    private var tokenRndFuture: CompletableFuture<String>? = null
    private var signatureFuture: CompletableFuture<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerWebView = findViewById(R.id.playerWebView)
        tvCurrentPlaying = findViewById(R.id.tvCurrentPlaying)
        channelDrawer = findViewById(R.id.channelDrawer)
        rvChannels = findViewById(R.id.rvChannels)

        val btnToggleDrawer = findViewById<Button>(R.id.btnToggleDrawer)
        val tabCctv = findViewById<Button>(R.id.tabCctv)
        val tabSatellite = findViewById<Button>(R.id.tabSatellite)
        val tabLocal = findViewById<Button>(R.id.tabLocal)

        // 1. 启动后台中继服务 (监听 18888)
        startService(Intent(this, BridgeService::class.java).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(this)
            } else {
                startService(this)
            }
        })
        val hiddenContainer = findViewById<ViewGroup>(R.id.hiddenWebContainer)
        WebViewKeeper.init(this, hiddenContainer)

        // 2. 初始化核心播放器 WebView
        initPlayerWebView()

        // 3. 配置列表与分类
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

        // 注入 Windows WebView2 兼容接口 (window.chrome.webview.postMessage)
        playerWebView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(jsonStr: String) {
                try {
                    val obj = JSONObject(jsonStr)
                    if (obj.has("tokenRnd")) {
                        tokenRndFuture?.complete(obj.getString("tokenRnd"))
                    }
                    if (obj.has("signature")) {
                        signatureFuture?.complete(obj.getString("signature"))
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
                // 注入兼容 polyfill，让 player.served.html 能像在 Windows 下一样发送 postMessage
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

                // 首次加载完毕自动播放第一个台
                playChannel(ChannelRepository.channels.first())
            }
        }

        // 加载本地托管的播放页
        playerWebView.loadUrl("http://127.0.0.1:18888/player")
    }

    private fun getFilteredChannels(): List<TvChannel> {
        return ChannelRepository.channels.filter { it.group == currentGroup }
    }

    // 播放频道调度
    private fun playChannel(ch: TvChannel) {
        tvCurrentPlaying.text = "${ch.name} (加载中...)"

        if (ch.group == ChannelGroup.LOCAL) {
            // 湖北台流直接播放改写后的本地 m3u8
            val localM3u8 = "http://127.0.0.1:18888/hbtv/${ch.id}.m3u8"
            startHlsPlay(localM3u8)
            tvCurrentPlaying.text = "${ch.name} (播放中)"
            return
        }

        // 央视/卫视官方取流全流程 (异步调度)
        Thread {
            try {
                val m3u8 = fetchCctvM3u8(ch)
                runOnUiThread {
                    if (m3u8 != null) {
                        startHlsPlay(m3u8)
                        tvCurrentPlaying.text = "${ch.name} (CMG 解密播放中)"
                    } else {
                        tvCurrentPlaying.text = "${ch.name} (获取播放流失败)"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvCurrentPlaying.text = "${ch.name} 错误: ${e.message}"
                }
            }
        }.start()
    }

    // 官方鉴权全流程：auth -> openToken -> cKey -> yspticket -> get_live_info
    private fun fetchCctvM3u8(ch: TvChannel): String? {
        val randStr = AuthSigner.randStr(10)
        val authSig = AuthSigner.computeAuthSignature(ch.pid, AuthSigner.Guid, randStr)
        val seqId = (System.currentTimeMillis() % 100000000).toString()
        val ts = System.currentTimeMillis().toString()
        val reqId = "999999" + AuthSigner.randStr(10) + ts

        // 1. POST /auth
        val authBody = "pid=${ch.pid}&guid=${AuthSigner.Guid}&appid=ysp_pc&rand_str=$randStr&signature=$authSig"
        val authReq = Request.Builder().url("http://127.0.0.1:18888/auth")
            .post(authBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val authRes = httpClient.newCall(authReq).execute().body?.string() ?: return null
        val authJson = JSONObject(authRes)
        val token = authJson.optJSONObject("data")?.optString("token") ?: return null
        val authTs = authJson.optJSONObject("data")?.optString("ts") ?: (System.currentTimeMillis() / 1000).toString()

        // 2. JS 生成 tokenRnd -> GET /open-token
        tokenRndFuture = CompletableFuture()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genTokenRnd('${AuthSigner.Guid}', '$token', '$ts')", null)
        }
        val rndVal = tokenRndFuture?.get(8, TimeUnit.SECONDS) ?: return null

        val openUrl = "http://127.0.0.1:18888/open-token?yspappid=${AuthSigner.YspAppId}&guid=${AuthSigner.Guid}&vappid=${AuthSigner.VappId}&vsecret=${AuthSigner.Vsecret}&raw=1&version=v1&ts=$ts&rnd=$rndVal"
        val openRes = httpClient.newCall(Request.Builder().url(openUrl).build()).execute().body?.string() ?: return null
        val sessionToken = JSONObject(openRes).optJSONObject("data")?.optString("token") ?: return null

        // 3. 动态生成 cKey (调用页面内 fb15 webpack)
        val cKeyFuture = CompletableFuture<String>()
        val safeUrl = "https://yangshipin.cn/tv/home?pid=${ch.pid}"
        val tsSec = (System.currentTimeMillis() / 1000).toString()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genCKey('${ch.cnlId}', '$tsSec', 'V1.0.0', '${AuthSigner.Guid}', '5910204', '$safeUrl')") { res ->
                val v = res?.trim('"', '\'', ' ') ?: ""
                cKeyFuture.complete(v)
            }
        }
        val cKey = cKeyFuture.get(5, TimeUnit.SECONDS)

        // 4. 动态生成 yspticket (调用页面内 RJq7sO71JF.wasm)
        val ticketFuture = CompletableFuture<String>()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__genYspTicket('${ch.pid}', '$authTs', '${ch.cnlId}', '${AuthSigner.Guid}', '${AuthSigner.YspAppId}', 'V1.0.0')") { res ->
                val v = res?.trim('"', '\'', ' ') ?: ""
                ticketFuture.complete(v)
            }
        }
        val yspticket = ticketFuture.get(5, TimeUnit.SECONDS)

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

        // 6. 生成 sig2 (openapi_signature)
        signatureFuture = CompletableFuture()
        runOnUiThread {
            playerWebView.evaluateJavascript("window.__generateSignature('${ch.pid}','${AuthSigner.Guid}','$seqId','$reqId','$sessionToken','$ts','$yspsdkinput')", null)
        }
        val sig2 = signatureFuture?.get(8, TimeUnit.SECONDS) ?: return null

        // 7. POST /get-live-info
        val bodyJson = JSONObject(liveFields as Map<*, *>).apply {
            put("signature", bodySig)
            put("adjust", 1)
        }.toString()

        val liveReq = Request.Builder().url("http://127.0.0.1:18888/get-live-info")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("yspappid", AuthSigner.YspAppId)
            .header("yspplayertoken", token)
            .header("yspsdkinput", yspsdkinput)
            .header("yspsdksign", "$sig2-$yspsdkinput-${AuthSigner.Guid}-$seqId-$reqId")
            .header("yspticket", yspticket)
            .header("request-id", reqId)
            .header("seqid", seqId)
            .header("Cookie", AuthSigner.Cookie)
            .build()

        val liveRes = httpClient.newCall(liveReq).execute().body?.string() ?: return null
        val data = JSONObject(liveRes).optJSONObject("data") ?: return null
        val playUrl = data.optString("playurl")
        val ext = data.optString("extended_param", "")
        return if (playUrl.isNotEmpty()) playUrl + ext else null
    }

    private fun startHlsPlay(m3u8Url: String) {
        val safe = m3u8Url.replace("'", "\\'")
        playerWebView.evaluateJavascript("window.__startM3u8('$safe');", null)
    }

    // 电视盒子遥控器上下换台与确定键选台支持
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
                if (channelDrawer.visibility == View.VISIBLE) {
                    channelDrawer.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // 频道列表适配器
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

            // TV 遥控器焦点高亮
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
