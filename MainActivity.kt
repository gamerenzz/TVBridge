package com.hbtv.bridge

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
        super.onCreate(savedInstanceState)
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
    }
}
