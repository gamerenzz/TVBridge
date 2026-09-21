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

                // 2. CMGPlayer.json 解密配置
                uri == "/Library/CMGPlayer.json" -> {
                    serveAssetOrMockJson("CMGPlayer.json", "{\"code\":0,\"data\":{\"switch\":1}}")
                }

                // 3. /media 媒体代理 (下载 TS 切片与 Key)
                uri == "/media" -> {
                    handleMediaProxy(session)
                }

                // 4. ★★★ 核心修复：SAPI 路径斜杠与下划线智能换算，彻底解决 hls.cmg.js 404 ★★★
                uri.startsWith("/sapi") -> {
                    handleSapiSmart(uri)
                }

                // 5. /auth 接口
                uri == "/auth" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/auth", postData, "application/x-www-form-urlencoded", session.headers)
                }

                // 6. /open-token 接口
                uri == "/open-token" && method == Method.GET -> {
                    val query = session.queryParameterString ?: ""
                    proxyGet("https://h5access.yangshipin.cn/web/open/token?$query")
                }

                // 7. /get-live-info 接口
                uri == "/get-live-info" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/get_live_info", postData, "application/json; charset=utf-8", session.headers)
                }

                // 8. EPG
                uri.startsWith("/capi/") -> {
                    proxyGet("https://capi.yangshipin.cn" + uri.removePrefix("/capi"))
                }

                // 9. 湖北台 TS 切片
                uri == "/hbtv/ts" -> {
                    handleHbtvTs(params)
                }

                // 10. 湖北台 M3U8
                uri.startsWith("/hbtv/") && uri.endsWith(".m3u8") -> {
                    handleHbtvM3u8(uri)
                }

                // 11. M3U 列表
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

    private fun handleMediaProxy(session: IHTTPSession): Response {
        val u = session.parameters["u"]?.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "missing u")

        LogManager.log("[Media代理] 抓取: ${u.take(55)}...")

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
            LogManager.log("[Media代理] 成功输出 M3U8")
            val res = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
            res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            res
        } else {
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            LogManager.log("[Media代理] 输出切片: ${bytes.size / 1024} KB")
            newFixedLengthResponse(Response.Status.OK, ct, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

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

        val lines = body.lines().map { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) line else resolve(t)
        }
        var out = lines.joinToString("\n")

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

    // ★★★ 核心算法还原：对齐 main.go 把 / 自动换算为 _，杜绝 404 ★★★
    private fun handleSapiSmart(uri: String): Response {
        val pathOnly = if (uri.contains('?')) uri.substringBefore('?') else uri
        val rawSub = pathOnly.removePrefix("/sapi").trim('/')
        val cacheKey = rawSub.replace('/', '_') // 如 assets/2025/wasm/hls.cmg.js -> assets_2025_wasm_hls.cmg.js

        val candidates = listOf(
            "sapi_cache/$cacheKey",
            "sapi_cache/$rawSub",
            cacheKey,
            rawSub
        )

        for (assetPath in candidates) {
            try {
                if (assetPath.endsWith(".bin")) {
                    val bytes = context.assets.open(assetPath).readBytes()
                    LogManager.log("[SAPI] 命中二进制: $assetPath (${bytes.size}B)")
                    val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(bytes), bytes.size.toLong())
                    res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    return res
                } else {
                    var text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                    LogManager.log("[SAPI] 命中脚本: $assetPath")
                    text = text.replace("https://sapi.yangshipin.cn", "/sapi")

                    if (text.contains("EM_IDB_STORE")) {
                        val fetchGateOld = "if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                        val fetchGateNew = "var __cmgRW=function(p){try{var u=UTF8ToString(p);if(/yangshipin\\.cn|cctv\\.cn/.test(u)&&u.indexOf('127.0.0.1')<0){var nu='http://127.0.0.1:18888/media?u='+encodeURIComponent(u);var b=[];for(var i=0;i<nu.length;i++)b.push(nu.charCodeAt(i));b.push(0);var np=_malloc(b.length);if(np){for(var j=0;j<b.length;j++)HEAPU8[np+j]=b[j];HEAPU32[p>>2]=np;return nu;}}}catch(e){}return null;};if(\"EM_IDB_STORE\"!==r&&\"EM_IDB_DELETE\"!==r){try{__cmgRW(HEAPU32[A+8>>2]);}catch(_e){}__emscripten_fetch_xhr(A,o,C,E,Q);return A;}if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                        text = text.replace(fetchGateOld, fetchGateNew)
                    }

                    val mime = if (assetPath.endsWith(".js")) "application/javascript; charset=utf-8" else "application/octet-stream"
                    val res = newFixedLengthResponse(Response.Status.OK, mime, text)
                    res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    return res
                }
            } catch (e: Exception) {
                // 尝试下一个候选路径
            }
        }

        LogManager.log("[SAPI 404] 缺失: $rawSub (寻找 key: $cacheKey)")
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $rawSub")
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
        LogManager.log("[湖北代理] 传输切片: ${bytes.size / 1024} KB")
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
        LogManager.log("[湖北代理] 成功改写 M3U8 ($cid)")
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
