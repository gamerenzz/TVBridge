package com.hbtv.bridge

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LocalProxyServer(port: Int) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val referer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters

        return try {
            when {
                // 1. 播放列表
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in CHANNELS) {
                        m3u.append("#EXTINF:-1 group-title=\"湖北电视\",${ch.name}\n")
                        m3u.append("http://127.0.0.1:8899/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. TS 分片中继
                uri == "/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull() ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    if (!tsPattern.matcher(upstreamUrl).find()) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Bad host")
                    }
                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", referer)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                // 3. M3U8 请求转发与改写
                uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/").removeSuffix(".m3u8")
                    val liveUrl = WebViewKeeper.getUrl(cid)
                    if (liveUrl.isNullOrEmpty()) {
                        LogManager.log("[$cid] 尚未就绪或地址为空")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready yet")
                    }

                    val req = Request.Builder().url(liveUrl)
                        .header("Referer", referer)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val resp = client.newCall(req).execute()
                    val rawM3u8 = resp.body?.string() ?: ""

                    // 将相对/绝对 TS 路径重写为指向本地 /ts?u=...
                    val baseUrl = liveUrl.substringBeforeLast("/") + "/"
                    val modifiedM3u8 = rawM3u8.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val absoluteTs = if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                            "/ts?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
                        } else {
                            line
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedM3u8)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("代理请求异常: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }
}
