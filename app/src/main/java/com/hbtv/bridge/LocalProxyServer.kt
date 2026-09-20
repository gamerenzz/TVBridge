package com.hbtv.bridge

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

data class ChannelTokens(
    val authToken: String,
    val sessionToken: String,
    val authTs: String,
    val expireAt: Long
)

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val seqCounter = AtomicLong(System.currentTimeMillis() / 1000)
    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    private val upstreamM3u8Cache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val tokenCacheByPid = ConcurrentHashMap<String, ChannelTokens>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters
        val hostHeader = session.headers["host"] ?: "127.0.0.1:18888"
        val baseUrl = "http://$hostHeader"

        return try {
            when {
                // 1. M3U 订阅源列表
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in ChannelRepository.channels) {
                        m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
                        m3u.append("$baseUrl/play/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. 静态资源
                uri.startsWith("/sapi/") -> {
                    val filename = uri.removePrefix("/sapi/")
                    val assetPath = "sapi_cache/$filename"
                    val mime = if (filename.endsWith(".js")) "application/javascript" else "application/octet-stream"
                    serveAsset(assetPath, mime)
                }

                uri == "/player" -> {
                    serveAsset("player.served.html", "text/html; charset=utf-8")
                }

                // 3. 核心：按需取流与 M3U8 深度解包改写
                uri.startsWith("/play/") && uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/play/").removeSuffix(".m3u8")
                    val ch = ChannelRepository.channels.find { it.id == cid }
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Channel not found")

                    LogManager.log("[中继网关] 外部播放器请求: ${ch.name}")

                    val upstreamM3u8Url = if (ch.group == ChannelGroup.LOCAL) {
                        WebViewKeeper.getUrl(ch.id)
                    } else {
                        resolveCctvStreamOnDemand(ch)
                    }

                    if (upstreamM3u8Url.isNullOrEmpty()) {
                        LogManager.log("[中继网关] ${ch.name} 取流中，返回 503")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Resolving stream, please retry")
                    }

                    // 深度解析（如果是 Master Playlist 则递归穿透到真实二级分片列表）
                    val rewrittenM3u8 = fetchAndRewriteM3u8(ch, upstreamM3u8Url, baseUrl)
                    if (rewrittenM3u8.isEmpty()) {
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "M3U8 parse error")
                    }

                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewrittenM3u8)
                }

                // 4. 央视/卫视切片下载
                uri == "/cctv/seg" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("User-Agent", AuthSigner.Ua)
                        .header("Referer", "https://yangshipin.cn/")
                        .header("Origin", "https://yangshipin.cn")
                        .build()

                    val resp = client.newCall(req).execute()
                    val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                    LogManager.log("[分片传输] 央视切片下载完成: ${rawBytes.size / 1024} KB")

                    val isClearStream = upstreamUrl.contains("mobilelive") || upstreamUrl.contains("m3u8_with_time_tag")
                    val outputBytes = if (isClearStream) rawBytes else CmgEngine.decryptTsInPlace(rawBytes)

                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(outputBytes), outputBytes.size.toLong())
                }

                // 5. 湖北台切片下载
                uri == "/hbtv/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", PC_UA)
                        .build()

                    val resp = client.newCall(req).execute()
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    LogManager.log("[分片传输] 湖北切片下载完成: ${bytes.size / 1024} KB")

                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(bytes), bytes.size.toLong())
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            LogManager.log("[中继异常] ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    // 递归穿透 Master Playlist，改写真实分片列表
    private fun fetchAndRewriteM3u8(ch: TvChannel, targetUrl: String, baseUrl: String): String {
        val isLocal = (ch.group == ChannelGroup.LOCAL)
        val referer = if (isLocal) hbtvReferer else "https://yangshipin.cn/"

        val reqBuilder = Request.Builder().url(targetUrl)
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", referer)
            .header("Origin", "https://yangshipin.cn")

        val resp = client.newCall(reqBuilder.build()).execute()
        val rawContent = resp.body?.string() ?: ""

        if (resp.code != 200 || rawContent.isBlank()) {
            LogManager.log("[CDN拉取失败] ${ch.name} HTTP ${resp.code}")
            return ""
        }

        // 识别 Master Playlist 多码率索引
        if (rawContent.contains("#EXT-X-STREAM-INF")) {
            LogManager.log("[索引解析] ${ch.name} 检测到多码率总索引，正在递归提取最高画质流...")
            val subLine = rawContent.lines().firstOrNull {
                val t = it.trim()
                t.isNotEmpty() && !t.startsWith("#") && (t.contains(".m3u8") || !t.contains(".ts"))
            }?.trim()

            if (subLine != null) {
                val subUrl = resolveAbsoluteUrl(targetUrl, subLine)
                return fetchAndRewriteM3u8(ch, subUrl, baseUrl)
            }
        }

        // 真正的切片列表：改写每个 TS 路径为本地中继
        val segRoute = if (isLocal) "/hbtv/ts" else "/cctv/seg"
        var segCount = 0

        val lines = rawContent.lines().map { rawLine ->
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                segCount++
                val absoluteTs = resolveAbsoluteUrl(targetUrl, line)
                "$baseUrl$segRoute?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
            } else {
                rawLine
            }
        }

        LogManager.log("[切片改写] ${ch.name} 改写完成，共输出 $segCount 个分片")
        return lines.joinToString("\n")
    }

    // 标准绝对路径换算算法 (移植自 PalmTV 官方实现)
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

    private fun getTokensForChannel(ch: TvChannel): ChannelTokens? {
        val now = System.currentTimeMillis()
        val cached = tokenCacheByPid[ch.pid]
        if (cached != null && now < cached.expireAt) {
            return cached
        }

        try {
            val randStr = AuthSigner.randStr(10)
            val authSig = AuthSigner.computeAuthSignature(ch.pid, AuthSigner.Guid, randStr)
            val seqId = seqCounter.incrementAndGet().toString()
            val ts = now.toString()
            val reqId = "999999" + AuthSigner.randStr(10) + ts

            val authBody = "pid=${ch.pid}&guid=${AuthSigner.Guid}&appid=ysp_pc&rand_str=$randStr&signature=$authSig"
            val authReq = applyBrowserHeaders(
                Request.Builder().url("https://player-api.yangshipin.cn/v1/player/auth")
                    .post(authBody.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType())),
                seqId, reqId
            ).build()

            val authResp = client.newCall(authReq).execute()
            val authRes = authResp.body?.string() ?: ""
            if (authResp.code != 200 || authRes.isBlank()) {
                LogManager.log("[auth错误] ${ch.name} HTTP ${authResp.code}")
                return null
            }

            val authJson = JSONObject(authRes)
            val token = authJson.optJSONObject("data")?.optString("token") ?: return null
            val authTs = authJson.optJSONObject("data")?.optString("ts") ?: (now / 1000).toString()

            val rndVal = CmgEngine.genTokenRnd(AuthSigner.Guid, token, ts)
            if (rndVal.isEmpty()) {
                LogManager.log("[openToken] tokenRnd 计算超时")
                return null
            }

            val openUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=${AuthSigner.YspAppId}&guid=${AuthSigner.Guid}&vappid=${AuthSigner.VappId}&vsecret=${AuthSigner.Vsecret}&raw=1&version=v1&ts=$ts&rnd=$rndVal"
            val openReq = Request.Builder().url(openUrl)
                .header("User-Agent", AuthSigner.Ua)
                .header("Referer", "https://yangshipin.cn/")
                .header("Origin", "https://yangshipin.cn")
                .header("Accept", "*/*")
                .build()

            val openResp = client.newCall(openReq).execute()
            val openRes = openResp.body?.string() ?: ""
            val sessionToken = JSONObject(openRes).optJSONObject("data")?.optString("token") ?: return null

            val result = ChannelTokens(token, sessionToken, authTs, now + 15 * 60 * 1000L)
            tokenCacheByPid[ch.pid] = result
            LogManager.log("[鉴权成功] ${ch.name} 拿到专属 Token")
            return result
        } catch (e: Exception) {
            LogManager.log("[鉴权异常] ${ch.name}: ${e.message}")
            return null
        }
    }

    private fun resolveCctvStreamOnDemand(ch: TvChannel): String? {
        val cacheEntry = upstreamM3u8Cache[ch.id]
        val now = System.currentTimeMillis()
        if (cacheEntry != null && now - cacheEntry.second < 120000) {
            return cacheEntry.first
        }

        return try {
            val tokens = getTokensForChannel(ch) ?: return null
            val authToken = tokens.authToken
            val sessionToken = tokens.sessionToken
            val authTs = tokens.authTs

            val tsSec = (now / 1000).toString()
            val cKey = CmgEngine.generateCKey(ch.cnlId, tsSec, ch.pid)
            val yspticket = CmgEngine.generateYspTicket(ch.pid, authTs, ch.cnlId)

            if (cKey.isEmpty() || yspticket.isEmpty()) {
                LogManager.log("[取流警告] ${ch.name} cKey 或 yspticket 尚未算出")
                return null
            }

            val randStrLive = AuthSigner.randStr(10)
            val liveFields = mutableMapOf(
                "cnlid" to ch.cnlId, "livepid" to ch.pid, "stream" to "2", "guid" to AuthSigner.Guid,
                "cKey" to cKey, "adjust" to "1", "sphttps" to "1", "platform" to "5910204", "cmd" to "2",
                "encryptVer" to "8.1", "dtype" to "1", "devid" to "devid", "otype" to "ojson",
                "appVer" to "V1.0.0", "app_version" to "V1.0.0", "channel" to "ysp_tx", "defn" to "fhd",
                "rand_str" to randStrLive
            )
            val yspsdkinput = AuthSigner.computeLiveSdkInput(liveFields)
            val bodySig = AuthSigner.computeLiveBodySignature(liveFields)

            val liveSeqId = seqCounter.incrementAndGet().toString()
            val liveReqId = "999999" + AuthSigner.randStr(10) + now.toString()

            val sig2 = CmgEngine.generateSig2(ch.pid, AuthSigner.Guid, liveSeqId, liveReqId, sessionToken, now.toString(), yspsdkinput)
            if (sig2.isEmpty()) {
                LogManager.log("[取流警告] ${ch.name} sig2 尚未算出")
                return null
            }

            val bodyJson = JSONObject().apply {
                for ((k, v) in liveFields) put(k, v)
                put("signature", bodySig)
                put("adjust", 1)
            }.toString()

            val liveReqBuilder = Request.Builder()
                .url("https://player-api.yangshipin.cn/v1/player/get_live_info")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("yspplayertoken", authToken)
                .header("yspsdkinput", yspsdkinput)
                .header("yspsdksign", "$sig2-$yspsdkinput-${AuthSigner.Guid}-$liveSeqId-$liveReqId")
                .header("yspticket", yspticket)

            val liveReq = applyBrowserHeaders(liveReqBuilder, liveSeqId, liveReqId).build()
            val liveResp = client.newCall(liveReq).execute()
            val liveRes = liveResp.body?.string() ?: ""

            if (liveResp.code != 200 || liveRes.isBlank()) {
                LogManager.log("[取流报错] ${ch.name} HTTP ${liveResp.code}")
                return null
            }

            val data = JSONObject(liveRes).optJSONObject("data") ?: return null
            val playUrl = data.optString("playurl")
            val ext = data.optString("extended_param", "")
            val finalUrl = if (playUrl.isNotEmpty()) playUrl + ext else null

            if (finalUrl != null) {
                upstreamM3u8Cache[ch.id] = Pair(finalUrl, now)
                LogManager.log("[取流成功] ${ch.name} 获取到官方流")
            }
            finalUrl
        } catch (e: Exception) {
            LogManager.log("[取流异常] ${ch.name}: ${e.message}")
            null
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

    private fun applyBrowserHeaders(builder: Request.Builder, seqId: String, reqId: String): Request.Builder {
        return builder
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-site")
            .header("yspappid", AuthSigner.YspAppId)
            .header("seqid", seqId)
            .header("request-id", reqId)
            .header("Cookie", "${AuthSigner.Cookie} nseqId=$seqId; nrequest-id=$reqId")
    }
}
