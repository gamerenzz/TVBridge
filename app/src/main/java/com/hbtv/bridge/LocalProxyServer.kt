package com.hbtv.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val chromeUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parameters

        // 处理 OPTIONS 跨域预检
        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        return try {
            val response = when {
                // 1. 播放主页面
                uri == "/" || uri == "/player" -> {
                    servePlayerHtml()
                }

                // 2. ★★★ 解决有声无画核心：返回 CMGPlayer.json 解密配置开关 ★★★
                uri == "/Library/CMGPlayer.json" -> {
                    serveAssetOrMockJson("CMGPlayer.json", "{\"code\":0,\"data\":{\"switch\":1}}")
                }

                // 3. ★★★ 解决有声无画核心：完全复刻 main.go 的 /media 媒体转发与 M3U8 改写 ★★★
                uri == "/media" -> {
                    handleMediaProxy(session)
                }

                // 4. ★★★ 解决有声无画核心：完全复刻 main.go 的 /sapi/ 脚本注入与 IndexedDB 绕过 ★★★
                uri.startsWith("/sapi/") -> {
                    handleSapiWithPatch(uri.removePrefix("/sapi/"))
                }

                // 5. 官方 /auth 接口
                uri == "/auth" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/auth", postData, "application/x-www-form-urlencoded", session.headers)
                }

                // 6. 官方 /open-token 接口
                uri == "/open-token" && method == Method.GET -> {
                    val query = session.queryParameterString ?: ""
                    proxyGet("https://h5access.yangshipin.cn/web/open/token?$query")
                }

                // 7. 官方 /get-live-info 接口
                uri == "/get-live-info" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/get_live_info", postData, "application/json; charset=utf-8", session.headers)
                }

                // 8. EPG 节目单
                uri.startsWith("/capi/") -> {
                    proxyGet("https://capi.yangshipin.cn" + uri.removePrefix("/capi"))
                }

                // 9. 湖北台分片转发
                uri == "/hbtv/ts" -> {
                    handleHbtvTs(params)
                }

                // 10. 湖北台 M3U8 动态改写
                uri.startsWith("/hbtv/") && uri.endsWith(".m3u8") -> {
                    handleHbtvM3u8(uri)
                }

                // 11. IPTV M3U 订阅源列表
                uri == "/live.m3u" -> {
                    handleLiveM3u(session)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
            addCorsHeaders(response)
            response
        } catch (e: Exception) {
            LogManager.log("[代理异常] ${e.message}")
            val errRes = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
            addCorsHeaders(errRes)
            errRes
        }
    }

    private fun addCorsHeaders(res: Response) {
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "*")
    }

    // 复刻 main.go: 托管 player.served.html 并加入时间戳防缓存
    private fun servePlayerHtml(): Response {
        return try {
            val html = context.assets.open("player.served.html").bufferedReader().use { it.readText() }
            val patchedHtml = html.replace("ver=250813", "ver=${System.nanoTime()}")
            val res = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", patchedHtml)
            res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            res.addHeader("Pragma", "no-cache")
            res
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "player.served.html not found")
        }
    }

    // 复刻 main.go 的 /media 转发: 下载 TS 分片、Key、并改写相对 URI 的 M3U8
    private fun handleMediaProxy(session: IHTTPSession): Response {
        val u = session.parameters["u"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing u")

        val reqBuilder = Request.Builder().url(u)
            .header("User-Agent", chromeUA)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")

        for (h in listOf("Range", "Accept", "Accept-Language", "Cookie")) {
            val v = session.headers[h.lowercase()]
            if (!v.isNullOrEmpty()) reqBuilder.header(h, v)
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        val ct = resp.header("Content-Type") ?: "video/mp2t"
        val isM3U8 = ct.contains("mpegurl") || ct.contains("vnd.apple") || u.lowercase().contains(".m3u8")

        return if (isM3U8) {
            val raw = resp.body?.string() ?: ""
            val rewritten = rewriteM3u8ToAbsolute(raw, u)
            newFixedLengthResponse(Response.Status.OK, ct, rewritten)
        } else {
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            newFixedLengthResponse(Response.Status.OK, ct, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    // 复刻 main.go 的 rewriteM3U8: 把相对路径换算成绝对 CDN URL，保证页面二次改写走 /media
    private fun rewriteM3u8ToAbsolute(body: String, m3u8Url: String): String {
        val base = if (m3u8Url.contains("/")) m3u8Url.substringBeforeLast("/") + "/" else ""
        val resolve = { p: String ->
            when {
                p.isEmpty() || p.startsWith("http://") || p.startsWith("https://") || p.startsWith("data:") -> p
                p.startsWith("/") -> {
                    val parsed = URL(m3u8Url)
                    "${parsed.protocol}://${parsed.host}$p"
                }
                else -> base + p
            }
        }

        // 1. 独立行
        val lines = body.lines().map { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) line else resolve(t)
        }
        var out = lines.joinToString("\n")

        // 2. URI="..." 标签
        val p = Pattern.compile("URI=\"([^\"]*)\"")
        val m = p.matcher(out)
        val sb = StringBuffer()
        while (m.find()) {
            val rel = m.group(1) ?: ""
            m.appendReplacement(sb, "URI=\"${resolve(rel)}\"")
        }
        m.appendTail(sb)
        return sb.toString()
    }

    // 复刻 main.go 的 /sapi/ 注入：绕过 IndexedDB 门控，强行走 XHR
    private fun handleSapiWithPatch(filename: String): Response {
        return try {
            val assetPath = "sapi_cache/$filename"
            val isStream: InputStream = context.assets.open(assetPath)
            var text = isStream.bufferedReader().use { it.readText() }

            // 替换所有绝对 sapi URL 为本地同源
            text = text.replace("https://sapi.yangshipin.cn", "/sapi")

            // 注入 IndexedDB 绕过补丁 (main.go 核心代码)
            if (text.contains("EM_IDB_STORE")) {
                val fetchGateOld = "if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                val fetchGateNew = "var __cmgRW=function(p){try{var u=UTF8ToString(p);if(/yangshipin\\.cn|cctv\\.cn/.test(u)&&u.indexOf('127.0.0.1')<0){var nu='http://127.0.0.1:18888/media?u='+encodeURIComponent(u);var b=[];for(var i=0;i<nu.length;i++)b.push(nu.charCodeAt(i));b.push(0);var np=_malloc(b.length);if(np){for(var j=0;j<b.length;j++)HEAPU8[np+j]=b[j];HEAPU32[p>>2]=np;return nu;}}}catch(e){}return null;};if(\"EM_IDB_STORE\"!==r&&\"EM_IDB_DELETE\"!==r){try{__cmgRW(HEAPU32[A+8>>2]);}catch(_e){}__emscripten_fetch_xhr(A,o,C,E,Q);return A;}if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                text = text.replace(fetchGateOld, fetchGateNew)
            }

            val mime = if (filename.endsWith(".js")) "application/javascript; charset=utf-8" else "application/octet-stream"
            val res = newFixedLengthResponse(Response.Status.OK, mime, text)
            res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            res
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $filename")
        }
    }

    private fun serveAssetOrMockJson(assetName: String, fallbackJson: String): Response {
        return try {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", fallbackJson)
        }
    }

    private fun handleHbtvTs(params: Map<String, List<String>>): Response {
        val upstreamUrl = params["u"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
        val req = Request.Builder().url(upstreamUrl)
            .header("Referer", hbtvReferer)
            .header("User-Agent", chromeUA)
            .build()
        val resp = client.newCall(req).execute()
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        return newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun handleHbtvM3u8(uri: String): Response {
        val cid = uri.removePrefix("/hbtv/").removeSuffix(".m3u8")
        val liveUrl = WebViewKeeper.getUrl(cid)
        if (liveUrl.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not ready")
        }
        val req = Request.Builder().url(liveUrl)
            .header("Referer", hbtvReferer)
            .header("User-Agent", chromeUA)
            .build()
        val resp = client.newCall(req).execute()
        val rawM3u8 = resp.body?.string() ?: ""
        val baseUrl = liveUrl.substringBeforeLast("/") + "/"
        val modified = rawM3u8.lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) {
                val abs = if (t.startsWith("http")) t else baseUrl + t
                "http://127.0.0.1:18888/hbtv/ts?u=" + URLEncoder.encode(abs, "UTF-8")
            } else {
                line
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", modified)
    }

    private fun handleLiveM3u(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: "127.0.0.1:18888"
        val m3u = StringBuilder("#EXTM3U\n")
        for (ch in ChannelRepository.channels) {
            m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
            if (ch.group == ChannelGroup.LOCAL) {
                m3u.append("http://$host/hbtv/${ch.id}.m3u8\n")
            } else {
                m3u.append("http://$host/media?u=${ch.id}\n")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
    }

    private fun proxyGet(url: String): Response {
        val req = Request.Builder().url(url)
            .header("User-Agent", chromeUA)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
    }

    private fun proxyPost(url: String, postBody: String, contentType: String, headers: Map<String, String>): Response {
        val body = postBody.toRequestBody(contentType.toMediaType())
        val builder = Request.Builder().url(url).post(body)
        builder.header("User-Agent", chromeUA)
        builder.header("Referer", "https://yangshipin.cn/")
        builder.header("Origin", "https://yangshipin.cn")
        for ((k, v) in headers) {
            if (k.startsWith("ysp") || k == "seqid" || k == "request-id" || k == "cookie") {
                builder.addHeader(k, v)
            }
        }
        val resp = client.newCall(builder.build()).execute()
        val resStr = resp.body?.string() ?: ""
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", resStr)
    }
}
