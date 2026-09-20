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

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundNotification()
            // 使用单例启动，避免重复 bind 端口报 EADDRINUSE
            ServerManager.start(this)
            WebViewKeeper.init(this)
        } catch (e: Throwable) {
            LogManager.log("Service 启动警告: ${e.message}")
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
        ServerManager.stop()
        WebViewKeeper.destroy()
        LogManager.log("本地服务已关闭")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
