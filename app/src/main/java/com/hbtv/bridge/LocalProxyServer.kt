package com.hbtv.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters
        val hostHeader = session.headers["host"] ?: "127.0.0.1:18888"
        val baseUrl = "http://$hostHeader"

        return try {
            when {
                // 1. 动态生成 IPTV M3U 播放列表 (包含 61+ 频道)
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in ChannelRepository.channels) {
                        m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
                        m3u.append("$baseUrl/play/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. 按需取流：TiviMate 请求 /play/{cid}.m3u8
                uri.startsWith("/play/") && uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/play/").removeSuffix(".m3u8")
                    val ch = ChannelRepository.channels.find { it.id == cid }
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Channel not found")

                    LogManager.log("[中继网关] 外部播放器请求: ${ch.name}")

                    val upstreamM3u8Url = if (ch.group == ChannelGroup.LOCAL) {
                        WebViewKeeper.getUrl(ch.id)
                    } else {
                        StreamResolver.resolveCctvStream(ch)
                    }

                    if (upstreamM3u8Url.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream resolving...")
                    }

                    val reqBuilder = Request.Builder().url(upstreamM3u8Url).header("User-Agent", AuthSigner.Ua)
                    if (ch.group == ChannelGroup.LOCAL) {
                        reqBuilder.header("Referer", hbtvReferer)
                    } else {
                        reqBuilder.header("Referer", "https://yangshipin.cn/")
                    }

                    val resp = client.newCall(reqBuilder.build()).execute()
                    val rawM3u8 = resp.body?.string() ?: ""

                    // 解析 Master Playlist
                    val rewrittenM3u8 = parseAndRewriteM3u8(ch, rawM3u8, upstreamM3u8Url, baseUrl)
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewrittenM3u8)
                }

                // 3. ★ 核心攻关：央视/卫视切片下载，经由 C 语言 JNI 原地解密还原为明文 TS ★
                uri == "/cctv/seg" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("User-Agent", AuthSigner.Ua)
                        .header("Referer", "https://yangshipin.cn/")
                        .build()

                    val resp = client.newCall(req).execute()
                    val rawBytes = resp.body?.bytes() ?: ByteArray(0)

                    val isClearStream = upstreamUrl.contains("mobilelive") || upstreamUrl.contains("m3u8_with_time_tag")

                    if (!isClearStream && rawBytes.isNotEmpty()) {
                        // 使用 Direct ByteBuffer 零拷贝送入 C 语言解密 NALU
                        val directBuf = ByteBuffer.allocateDirect(rawBytes.size)
                        directBuf.put(rawBytes)
                        directBuf.flip()

                        val count = CmgNative.decryptTsInPlace(directBuf, rawBytes.size)
                        directBuf.get(rawBytes) // 取回解密后的明文字节
                        LogManager.log("[Native解密] 切片还原成功: ${rawBytes.size / 1024} KB (解密 $count 帧)")
                    }

                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(rawBytes), rawBytes.size.toLong())
                }

                // 4. 湖北台分片转发
                uri == "/hbtv/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", AuthSigner.Ua)
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

    private fun parseAndRewriteM3u8(ch: TvChannel, rawContent: String, targetUrl: String, baseUrl: String): String {
        val isLocal = (ch.group == ChannelGroup.LOCAL)

        // 递归处理多码率总索引
        if (rawContent.contains("#EXT-X-STREAM-INF")) {
            val subLine = rawContent.lines().firstOrNull {
                val t = it.trim()
                t.isNotEmpty() && !t.startsWith("#") && (t.contains(".m3u8") || !t.contains(".ts"))
            }?.trim()

            if (subLine != null) {
                val subUrl = resolveAbsoluteUrl(targetUrl, subLine)
                val resp = client.newCall(Request.Builder().url(subUrl).header("User-Agent", AuthSigner.Ua).build()).execute()
                val subRaw = resp.body?.string() ?: ""
                return parseAndRewriteM3u8(ch, subRaw, subUrl, baseUrl)
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
