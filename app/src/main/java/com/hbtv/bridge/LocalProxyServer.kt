package com.hbtv.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters
        val hostHeader = session.headers["host"] ?: "127.0.0.1:18888"
        val baseUrl = "http://$hostHeader"

        return try {
            when {
                // 1. M3U 订阅源 (供 TiviMate / PotPlayer 一键导入)
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in ChannelRepository.channels) {
                        m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
                        m3u.append("$baseUrl/play/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. 播放入口：ExoPlayer / TiviMate 请求 /play/{cid}.m3u8
                uri.startsWith("/play/") && uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/play/").removeSuffix(".m3u8")
                    val ch = ChannelRepository.channels.find { it.id == cid }
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Channel not found")

                    LogManager.log("[中继网关] 请求频道: ${ch.name}")

                    val targetM3u8Url = if (ch.group == ChannelGroup.LOCAL) {
                        WebViewKeeper.getUrl(ch.id)
                    } else {
                        getCleanCctvBroadcastUrl(ch.id)
                    }

                    if (targetM3u8Url.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready")
                    }

                    val rewritten = fetchAndRewriteM3u8(ch, targetM3u8Url, baseUrl)
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
                }

                // 3. 央视/卫视分片中继
                uri == "/cctv/seg" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("User-Agent", chromeUA)
                        .build()

                    val resp = client.newCall(req).execute()
                    val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(rawBytes), rawBytes.size.toLong())
                }

                // 4. 湖北台分片中继 (防盗链穿透)
                uri == "/hbtv/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", chromeUA)
                        .build()

                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("[中继异常] ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    private fun getCleanCctvBroadcastUrl(cid: String): String {
        return when (cid) {
            "cctv1" -> "http://ivi.bupt.edu.cn/hls/cctv1hd.m3u8"
            "cctv2" -> "http://ivi.bupt.edu.cn/hls/cctv2hd.m3u8"
            "cctv3" -> "http://ivi.bupt.edu.cn/hls/cctv3hd.m3u8"
            "cctv4" -> "http://ivi.bupt.edu.cn/hls/cctv4hd.m3u8"
            "cctv5" -> "http://ivi.bupt.edu.cn/hls/cctv5hd.m3u8"
            "cctv5p" -> "http://ivi.bupt.edu.cn/hls/cctv5phd.m3u8"
            "cctv6" -> "http://ivi.bupt.edu.cn/hls/cctv6hd.m3u8"
            "cctv7" -> "http://ivi.bupt.edu.cn/hls/cctv7hd.m3u8"
            "cctv8" -> "http://ivi.bupt.edu.cn/hls/cctv8hd.m3u8"
            "cctv9" -> "http://ivi.bupt.edu.cn/hls/cctv9hd.m3u8"
            "cctv10" -> "http://ivi.bupt.edu.cn/hls/cctv10hd.m3u8"
            "cctv11" -> "http://ivi.bupt.edu.cn/hls/cctv11hd.m3u8"
            "cctv12" -> "http://ivi.bupt.edu.cn/hls/cctv12hd.m3u8"
            "cctv13" -> "http://ivi.bupt.edu.cn/hls/cctv13hd.m3u8"
            "cctv14" -> "http://ivi.bupt.edu.cn/hls/cctv14hd.m3u8"
            "cctv15" -> "http://ivi.bupt.edu.cn/hls/cctv15hd.m3u8"
            "cctv16" -> "http://ivi.bupt.edu.cn/hls/cctv16hd.m3u8"
            "cctv17" -> "http://ivi.bupt.edu.cn/hls/cctv17hd.m3u8"
            "cctv4k" -> "http://ivi.bupt.edu.cn/hls/cctv4khd.m3u8"
            "hnws" -> "http://ivi.bupt.edu.cn/hls/hunanhd.m3u8"
            "zjws" -> "http://ivi.bupt.edu.cn/hls/zjhd.m3u8"
            "jsws" -> "http://ivi.bupt.edu.cn/hls/jshd.m3u8"
            "dfws" -> "http://ivi.bupt.edu.cn/hls/dfhd.m3u8"
            "bjws" -> "http://ivi.bupt.edu.cn/hls/btv1hd.m3u8"
            "sdws" -> "http://ivi.bupt.edu.cn/hls/sdhd.m3u8"
            "gdws" -> "http://ivi.bupt.edu.cn/hls/gdhd.m3u8"
            "ahws" -> "http://ivi.bupt.edu.cn/hls/ahhd.m3u8"
            else -> "http://ivi.bupt.edu.cn/hls/cctv1hd.m3u8"
        }
    }

    private fun fetchAndRewriteM3u8(ch: TvChannel, targetUrl: String, baseUrl: String): String {
        val isLocal = (ch.group == ChannelGroup.LOCAL)
        val reqBuilder = Request.Builder().url(targetUrl).header("User-Agent", chromeUA)
        if (isLocal) {
            reqBuilder.header("Referer", hbtvReferer)
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        val rawContent = resp.body?.string() ?: ""

        if (rawContent.contains("#EXT-X-STREAM-INF")) {
            val subLine = rawContent.lines().firstOrNull {
                val t = it.trim()
                t.isNotEmpty() && !t.startsWith("#") && (t.contains(".m3u8") || !t.contains(".ts"))
            }?.trim()

            if (subLine != null) {
                val subUrl = resolveAbsoluteUrl(targetUrl, subLine)
                return fetchAndRewriteM3u8(ch, subUrl, baseUrl)
            }
        }

        val segRoute = if (isLocal) "/hbtv/ts" else "/cctv/seg"
        val lines = rawContent.lines().map { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                val absoluteTs = resolveAbsoluteUrl(targetUrl, line)
                "$baseUrl$segRoute?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
            } else {
                rawLine
            }
        }

        return lines.joinToString("\n")
    }

    private fun resolveAbsoluteUrl(base: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        val schemeEnd = base.indexOf("://")
        val hostEnd = base.indexOf('/', schemeEnd + 3)
        val origin = if (hostEnd < 0) base else base.substring(0, hostEnd)
        if (rel.startsWith("/")) return origin + rel
        val lastSlash = base.lastIndexOf('/')
        val prefix = if (lastSlash < schemeEnd + 3) "$origin/" else base.substring(0, lastSlash + 1)
        return prefix + rel
    }
}
