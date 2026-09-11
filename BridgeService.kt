package com.hbtv.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BridgeService : Service() {

    private var server: LocalProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        try {
            server = LocalProxyServer(8899).apply { start() }
            LogManager.log("本地代理服务已在 127.0.0.1:8899 启动")
        } catch (e: Exception) {
            LogManager.log("启动本地服务失败: ${e.message}")
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
            .setContentText("端口: 8899 正在服务")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        server?.stop()
        WebViewKeeper.destroy()
        LogManager.log("服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
