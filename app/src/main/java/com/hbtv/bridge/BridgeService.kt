package com.hbtv.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BridgeService : Service() {

    private var server: LocalProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundNotification()
            server = LocalProxyServer(this, 18888).apply { start() }
            LogManager.log("本地中继服务器在 127.0.0.1:18888 启动")
            WebViewKeeper.init(this)
        } catch (e: Throwable) {
            LogManager.log("Service 启动异常: ${e.message}")
        }
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "palmtv_bridge_service"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(channelId, "掌上电视服务", NotificationManager.IMPORTANCE_LOW)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(chan)
            }
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("掌上电视运行中")
                .setContentText("本地中继端口: 18888")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(1, notification)
        } catch (e: Throwable) {
            LogManager.log("通知初始化失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        WebViewKeeper.destroy()
        LogManager.log("本地服务已关闭")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
