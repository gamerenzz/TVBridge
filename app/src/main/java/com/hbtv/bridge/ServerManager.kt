package com.hbtv.bridge

import android.content.Context
import android.util.Log

object ServerManager {
    private var server: LocalProxyServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server == null) {
            try {
                server = LocalProxyServer(context.applicationContext, 18888).apply {
                    start()
                }
                Log.i("ServerManager", "本地代理服务在 127.0.0.1:18888 启动成功")
                LogManager.log("本地代理在 127.0.0.1:18888 启动成功")
            } catch (e: Exception) {
                Log.e("ServerManager", "启动本地代理失败: ${e.message}")
                LogManager.log("启动本地代理失败: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            server?.stop()
            server = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
