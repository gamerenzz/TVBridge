package com.hbtv.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

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

    private lateinit var nativePlayerView: PlayerView
    private lateinit var bufferingProgress: ProgressBar
    private lateinit var tvCurrentPlaying: TextView
    private lateinit var channelDrawer: View
    private lateinit var rvChannels: RecyclerView
    private lateinit var logOverlay: View
    private lateinit var tvConsoleLogs: TextView
    private lateinit var logScrollView: ScrollView

    private var exoPlayer: ExoPlayer? = null
    private var currentGroup = ChannelGroup.CCTV
    private lateinit var adapter: ChannelAdapter
    private var currentChannelIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemUI()

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        } catch (e: Exception) {}

        setContentView(R.layout.activity_main)

        ServerManager.start(this)

        nativePlayerView = findViewById(R.id.nativePlayerView)
        bufferingProgress = findViewById(R.id.bufferingProgress)
        tvCurrentPlaying = findViewById(R.id.tvCurrentPlaying)
        channelDrawer = findViewById(R.id.channelDrawer)
        rvChannels = findViewById(R.id.rvChannels)
        logOverlay = findViewById(R.id.logOverlay)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
        logScrollView = findViewById(R.id.logScrollView)

        val btnToggleDrawer = findViewById<Button>(R.id.btnToggleDrawer)
        val btnToggleLogs = findViewById<Button>(R.id.btnToggleLogs)
        val btnCopyM3u = findViewById<Button>(R.id.btnCopyM3u)
        val btnMinimize = findViewById<Button>(R.id.btnMinimize)
        val btnCloseLogs = findViewById<TextView>(R.id.btnCloseLogs)
        val btnCopyAllLogs = findViewById<TextView>(R.id.btnCopyAllLogs)
        val btnClearLogs = findViewById<TextView>(R.id.btnClearLogs)

        val tabCctv = findViewById<Button>(R.id.tabCctv)
        val tabSatellite = findViewById<Button>(R.id.tabSatellite)
        val tabLocal = findViewById<Button>(R.id.tabLocal)

        // 1. 初始化 Google ExoPlayer 原生播放器
        initExoPlayer()

        // 2. 复制 M3U 订阅源
        btnCopyM3u.setOnClickListener {
            showM3uCopyDialog()
        }

        // 3. 最小化后台运行
        btnMinimize.setOnClickListener {
            Toast.makeText(this, "已转入后台运行，服务不中断", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        btnCopyAllLogs.setOnClickListener {
            val allLogs = LogManager.getAllLogs()
            if (allLogs.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("PalmTV_Logs", allLogs)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "全部日志已复制", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearLogs.setOnClickListener {
            LogManager.clear()
            tvConsoleLogs.text = ""
        }

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

        try {
            val intent = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {}

        val hiddenContainer = findViewById<ViewGroup>(R.id.hiddenWebContainer)
        WebViewKeeper.init(this, hiddenContainer)

        rvChannels.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter(getFilteredChannels()) { ch ->
            playChannel(ch)
            channelDrawer.visibility = View.GONE
            hideSystemUI()
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

    private fun initExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            nativePlayerView.player = this
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    bufferingProgress.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    bufferingProgress.visibility = View.GONE
                    LogManager.log("[播放器错误] ${error.errorCodeName}: ${error.message}")
                }
            })
        }
    }

    private fun playChannel(ch: TvChannel) {
        tvCurrentPlaying.text = "${ch.name} (播放中)"
        LogManager.log("▶ 选台: ${ch.name}")
        bufferingProgress.visibility = View.VISIBLE

        val playUrl = "http://127.0.0.1:18888/play/${ch.id}.m3u8"
        exoPlayer?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(playUrl))
            prepare()
            play()
        }
    }

    private fun showM3uCopyDialog() {
        val lanIp = getLanIpAddress()
        val localUrl = "http://127.0.0.1:18888/live.m3u"
        val lanUrl = if (lanIp.isNotEmpty()) "http://$lanIp:18888/live.m3u" else "未连接 WiFi"

        val options = arrayOf(
            "本机使用 (TiviMate同机播放)\n$localUrl",
            "局域网共享 (供家庭其它电视/电脑播放)\n$lanUrl"
        )

        AlertDialog.Builder(this)
            .setTitle("复制 IPTV 订阅源地址")
            .setItems(options) { _, which ->
                val copyUrl = if (which == 0) localUrl else (if (lanIp.isNotEmpty()) lanUrl else localUrl)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("IPTV_M3U", copyUrl))
                Toast.makeText(this, "已复制到剪贴板:\n$copyUrl", Toast.LENGTH_LONG).show()
                LogManager.log("已复制订阅源: $copyUrl")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getLanIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("eth")) {
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return ""
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun getFilteredChannels(): List<TvChannel> {
        return ChannelRepository.channels.filter { it.group == currentGroup }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentList = getFilteredChannels()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentList.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex - 1 + currentList.size) % currentList.size
                    playChannel(currentList[currentChannelIndex])
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentList.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex + 1) % currentList.size
                    playChannel(currentList[currentChannelIndex])
                }
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
                moveTaskToBack(true)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
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
