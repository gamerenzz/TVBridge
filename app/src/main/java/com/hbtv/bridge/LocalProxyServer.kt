package com.hbtv.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parameters

        return try {
            when {
                // 1. 播放主页面
                uri == "/" || uri == "/player" -> {
                    serveAsset("player.served.html", "text/html; charset=utf-8")
                }

                // 2. 静态解密资产文件托管
                uri.startsWith("/sapi/") -> {
                    val filename = uri.removePrefix("/sapi/")
                    val assetPath = "sapi_cache/$filename"
                    val mime = if (filename.endsWith(".js")) "application/javascript" else "application/octet-stream"
                    serveAsset(assetPath, mime)
                }

                // 3. /auth 鉴权代理
                uri == "/auth" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/auth", postData, "application/x-www-form-urlencoded")
                }

                // 4. /open-token 代理
                uri == "/open-token" && method == Method.GET -> {
                    val query = session.queryParameterString ?: ""
                    proxyGet("https://h5access.yangshipin.cn/web/open/token?$query")
                }

                // 5. /get-live-info 代理
                uri == "/get-live-info" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPostWithHeaders("https://player-api.yangshipin.cn/v1/player/get_live_info", postData, session.headers)
                }

                // 6. EPG 节目单反向代理
                uri.startsWith("/capi/") -> {
                    val targetUrl = "https://capi.yangshipin.cn" + uri.removePrefix("/capi")
                    proxyGet(targetUrl)
                }

                // 7. 湖北台 TS 分片代理
                uri == "/hbtv/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    if (!tsPattern.matcher(upstreamUrl).find()) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Bad host")
                    }
                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", PC_UA)
                        .build()
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                // 8. 湖北台 M3U8 列表动态改写
                uri.startsWith("/hbtv/") && uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/hbtv/").removeSuffix(".m3u8")
                    val liveUrl = WebViewKeeper.getUrl(cid)
                    if (liveUrl.isNullOrEmpty()) {
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready yet")
                    }
                    val req = Request.Builder().url(liveUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", PC_UA)
                        .build()
                    val resp = client.newCall(req).execute()
                    val rawM3u8 = resp.body?.string() ?: ""
                    val baseUrl = liveUrl.substringBeforeLast("/") + "/"
                    val modifiedM3u8 = rawM3u8.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val absoluteTs = if (trimmed.startsWith("http")) trimmed else baseUrl + trimmed
                            "http://127.0.0.1:18888/hbtv/ts?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
                        } else {
                            line
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modifiedM3u8)
                }

                // 9. 导出给第三方 IPTV 播放器的标准 M3U 订阅源
                uri == "/live.m3u" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in ChannelRepository.channels) {
                        m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
                        if (ch.group == ChannelGroup.LOCAL) {
                            m3u.append("http://127.0.0.1:18888/hbtv/${ch.id}.m3u8\n")
                        } else {
                            m3u.append("http://127.0.0.1:18888/play/${ch.id}\n")
                        }
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("代理异常: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    private fun serveAsset(assetPath: String, mime: String): Response {
        return try {
            val isStream: InputStream = context.assets.open(assetPath)
            val resp = newChunkedResponse(Response.Status.OK, mime, isStream)
            resp.addHeader("Access-Control-Allow-Origin", "*")
            resp.addHeader("Cache-Control", "no-store")
            resp
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $assetPath")
        }
    }

    private fun makeStatus(code: Int, desc: String): Response.IStatus {
        return object : Response.IStatus {
            override fun getRequestStatus(): Int = code
            override fun getDescription(): String = "$code $desc"
        }
    }

    private fun proxyGet(url: String): Response {
        val req = Request.Builder().url(url)
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        val contentType = resp.header("Content-Type") ?: "application/json"
        val r = newFixedLengthResponse(makeStatus(resp.code, resp.message), contentType, body)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun proxyPost(url: String, postBody: String, contentType: String): Response {
        val body = postBody.toRequestBody(contentType.toMediaType())
        val req = Request.Builder().url(url)
            .post(body)
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .header("Cookie", AuthSigner.Cookie)
            .build()
        val resp = client.newCall(req).execute()
        val resStr = resp.body?.string() ?: ""
        val r = newFixedLengthResponse(makeStatus(resp.code, resp.message), "application/json; charset=utf-8", resStr)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun proxyPostWithHeaders(url: String, postBody: String, headers: Map<String, String>): Response {
        val body = postBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        for ((k, v) in headers) {
            if (k.startsWith("ysp") || k == "seqid" || k == "request-id" || k == "cookie") {
                builder.addHeader(k, v)
            }
        }
        builder.header("User-Agent", AuthSigner.Ua)
        builder.header("Referer", "https://yangshipin.cn/")
        builder.header("Origin", "https://yangshipin.cn")
        val resp = client.newCall(builder.build()).execute()
        val resStr = resp.body?.string() ?: ""
        val r = newFixedLengthResponse(makeStatus(resp.code, resp.message), "application/json; charset=utf-8", resStr)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }
}
