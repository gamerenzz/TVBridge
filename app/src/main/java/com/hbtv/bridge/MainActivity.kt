package com.hbtv.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

    private lateinit var tvServerStatus: TextView
    private lateinit var tvConsoleLogs: TextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs)
        logScrollView = findViewById(R.id.logScrollView)

        val btnCopyM3u = findViewById<Button>(R.id.btnCopyM3u)
        val btnMinimize = findViewById<Button>(R.id.btnMinimize)
        val btnClearLogs = findViewById<Button>(R.id.btnClearLogs)

        tvConsoleLogs.setTextIsSelectable(true)

        LogManager.onLogListener = {
            runOnUiThread {
                tvConsoleLogs.text = LogManager.getAllLogs()
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        // 1. 初始化 C 语言解密管线
        CmgNative.init(this)

        // 2. 启动本地 18888 中继服务
        ServerManager.start(this)

        // 3. 启动前台保活服务
        try {
            val intent = Intent(this, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {}

        // 4. 挂载湖北台抓流池
        val hiddenContainer = findViewById<ViewGroup>(R.id.hiddenWebContainer)
        WebViewKeeper.init(this, hiddenContainer)

        val lanIp = getLanIpAddress()
        tvServerStatus.text = "中继运行中:\n本机地址: http://127.0.0.1:18888/live.m3u\n局域网源: http://${if (lanIp.isNotEmpty()) lanIp else "未连WiFi"}:18888/live.m3u"

        btnCopyM3u.setOnClickListener {
            showM3uCopyDialog()
        }

        btnMinimize.setOnClickListener {
            Toast.makeText(this, "掌上电视中继已转入后台运行", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }

        btnClearLogs.setOnClickListener {
            LogManager.clear()
            tvConsoleLogs.text = ""
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
            .setTitle("选择要复制的 M3U 订阅源")
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
