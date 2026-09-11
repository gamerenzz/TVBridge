package com.hbtv.bridge

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. 注册全局崩溃捕获器，彻底告别无声闪退
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle("软件启动/运行报错")
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
                    LogManager.log("正在启动后台保活与抓流服务...")
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

            LogManager.log("长江云直播助手就绪，点击【启动服务】开始。")

        } catch (e: Throwable) {
            // 捕获界面初始化崩溃
            AlertDialog.Builder(this)
                .setTitle("界面初始化失败")
                .setMessage(e.stackTraceToString())
                .setPositiveButton("退出", null)
                .show()
        }
    }
}
