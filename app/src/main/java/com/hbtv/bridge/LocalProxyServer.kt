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

class LocalProxyServer(private val context: Context, port: Int = 18888) : NanoHTTPD(port) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val seqCounter = AtomicLong(System.currentTimeMillis() / 1000)
    private val hbtvReferer = "https://news.hbtv.com.cn/"
    private val tsPattern = Pattern.compile("^https://live\\d+-cjy\\.hbtv\\.com\\.cn/")

    // m3u8 地址缓存 (有效期 150 秒)
    private val upstreamM3u8Cache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ★ 会话级 Token 缓存（有效期 20 分钟），大幅消除重复 /auth 往返耗时
    @Volatile private var cachedAuthToken: String = ""
    @Volatile private var cachedSessionToken: String = ""
    @Volatile private var tokenExpireAt: Long = 0L

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val params = session.parameters
        val hostHeader = session.headers["host"] ?: "127.0.0.1:18888"
        val baseUrl = "http://$hostHeader"

        return try {
            when {
                // 1. M3U 播放列表
                uri == "/live.m3u" || uri == "/" -> {
                    val m3u = StringBuilder("#EXTM3U\n")
                    for (ch in ChannelRepository.channels) {
                        m3u.append("#EXTINF:-1 group-title=\"${ch.group.title}\",${ch.name}\n")
                        m3u.append("$baseUrl/play/${ch.id}.m3u8\n")
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", m3u.toString())
                }

                // 2. 静态解密资产文件
                uri.startsWith("/sapi/") -> {
                    val filename = uri.removePrefix("/sapi/")
                    val assetPath = "sapi_cache/$filename"
                    val mime = if (filename.endsWith(".js")) "application/javascript" else "application/octet-stream"
                    serveAsset(assetPath, mime)
                }

                uri == "/player" -> {
                    serveAsset("player.served.html", "text/html; charset=utf-8")
                }

                // 3. 核心：按需取流接口 (TiviMate 调用的入口)
                uri.startsWith("/play/") && uri.endsWith(".m3u8") -> {
                    val cid = uri.removePrefix("/play/").removeSuffix(".m3u8")
                    val ch = ChannelRepository.channels.find { it.id == cid }
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Channel not found")

                    LogManager.log("[中继网关] 外部播放器请求频道: ${ch.name}")

                    val upstreamM3u8Url = if (ch.group == ChannelGroup.LOCAL) {
                        WebViewKeeper.getUrl(ch.id)
                    } else {
                        resolveCctvStreamOnDemand(ch)
                    }

                    if (upstreamM3u8Url.isNullOrEmpty()) {
                        LogManager.log("[中继网关] ${ch.name} 取流中，返回 503 稍候重试")
                        return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Resolving stream, please retry")
                    }

                    // 拉取上游原始 m3u8
                    val reqBuilder = Request.Builder().url(upstreamM3u8Url).header("User-Agent", AuthSigner.Ua)
                    if (ch.group == ChannelGroup.LOCAL) {
                        reqBuilder.header("Referer", hbtvReferer)
                    }
                    val resp = client.newCall(reqBuilder.build()).execute()
                    val rawM3u8 = resp.body?.string() ?: ""

                    val upstreamBase = upstreamM3u8Url.substringBeforeLast("/") + "/"
                    val isLocalChan = (ch.group == ChannelGroup.LOCAL)

                    // 改写切片地址
                    val rewrittenM3u8 = rawM3u8.lines().joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val absoluteTs = if (trimmed.startsWith("http")) trimmed else upstreamBase + trimmed
                            val segRoute = if (isLocalChan) "/hbtv/ts" else "/cctv/seg"
                            "$baseUrl$segRoute?u=" + URLEncoder.encode(absoluteTs, "UTF-8")
                        } else {
                            line
                        }
                    }
                    LogManager.log("[中继网关] ${ch.name} 成功输出 M3U8")
                    newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewrittenM3u8)
                }

                // 4. 央视/卫视分片
                uri == "/cctv/seg" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")

                    val req = Request.Builder().url(upstreamUrl)
                        .header("User-Agent", AuthSigner.Ua)
                        .header("Referer", "https://yangshipin.cn/")
                        .build()
                    val resp = client.newCall(req).execute()
                    val rawBytes = resp.body?.bytes() ?: ByteArray(0)

                    val isCctv6 = upstreamUrl.contains("mobilelive") || upstreamUrl.contains("m3u8_with_time_tag")
                    val clearBytes = if (isCctv6) rawBytes else CmgEngine.decryptTsInPlace(rawBytes)

                    newFixedLengthResponse(Response.Status.OK, "video/mp2t", ByteArrayInputStream(clearBytes), clearBytes.size.toLong())
                }

                // 5. 湖北台分片
                uri == "/hbtv/ts" -> {
                    val upstreamUrl = params["u"]?.firstOrNull()
                        ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing u")
                    val req = Request.Builder().url(upstreamUrl)
                        .header("Referer", hbtvReferer)
                        .header("User-Agent", PC_UA)
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

    // 确保会话 Token 处于激活态（大幅消除冷启动往返等待）
    @Synchronized
    private fun ensureSessionTokens(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        if (cachedAuthToken.isNotEmpty() && cachedSessionToken.isNotEmpty() && now < tokenExpireAt) {
            return Pair(cachedAuthToken, cachedSessionToken)
        }

        try {
            val randStr = AuthSigner.randStr(10)
            val authSig = AuthSigner.computeAuthSignature("600001859", AuthSigner.Guid, randStr)
            val seqId = seqCounter.incrementAndGet().toString()
            val ts = now.toString()
            val reqId = "999999" + AuthSigner.randStr(10) + ts

            // 1. /auth
            val authBody = "pid=600001859&guid=${AuthSigner.Guid}&appid=ysp_pc&rand_str=$randStr&signature=$authSig"
            val authReq = applyHeaders(
                Request.Builder().url("https://player-api.yangshipin.cn/v1/player/auth")
                    .post(authBody.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType())),
                seqId, reqId
            ).build()
            val authRes = client.newCall(authReq).execute().body?.string() ?: return null
            val token = JSONObject(authRes).optJSONObject("data")?.optString("token") ?: return null

            // 2. /open-token
            val rndVal = CmgEngine.genTokenRnd(AuthSigner.Guid, token, ts)
            if (rndVal.isEmpty()) return null

            val openUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=${AuthSigner.YspAppId}&guid=${AuthSigner.Guid}&vappid=${AuthSigner.VappId}&vsecret=${AuthSigner.Vsecret}&raw=1&version=v1&ts=$ts&rnd=$rndVal"
            val openReq = Request.Builder().url(openUrl).header("User-Agent", AuthSigner.Ua).build()
            val openRes = client.newCall(openReq).execute().body?.string() ?: return null
            val sessionToken = JSONObject(openRes).optJSONObject("data")?.optString("token") ?: return null

            cachedAuthToken = token
            cachedSessionToken = sessionToken
            tokenExpireAt = now + 20 * 60 * 1000L // 缓存 20 分钟
            LogManager.log("[会话] 全局鉴权 Token 刷新成功")
            return Pair(token, sessionToken)
        } catch (e: Exception) {
            LogManager.log("[会话异常] ${e.message}")
            return null
        }
    }

    // 后台极速按需取流 (有缓存时仅需约 500ms)
    private fun resolveCctvStreamOnDemand(ch: TvChannel): String? {
        val cacheEntry = upstreamM3u8Cache[ch.id]
        val now = System.currentTimeMillis()
        if (cacheEntry != null && now - cacheEntry.second < 120000) {
            return cacheEntry.first // 2分钟内直接复用
        }

        return try {
            val tokens = ensureSessionTokens() ?: return null
            val authToken = tokens.first
            val sessionToken = tokens.second

            val tsSec = (now / 1000).toString()
            val cKey = CmgEngine.generateCKey(ch.cnlId, tsSec, ch.pid)
            val yspticket = CmgEngine.generateYspTicket(ch.pid, tsSec, ch.cnlId)

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

            // ★ 修复：调用真实 sig2 签名，严防 mock 假值被拒
            val sig2 = CmgEngine.generateSig2(ch.pid, AuthSigner.Guid, liveSeqId, liveReqId, sessionToken, now.toString(), yspsdkinput)
            if (sig2.isEmpty()) {
                LogManager.log("[取流失败] sig2 计算超时")
                return null
            }

            val bodyJson = JSONObject().apply {
                for ((k, v) in liveFields) put(k, v)
                put("signature", bodySig)
                put("adjust", 1)
            }.toString()

            val liveReq = applyHeaders(
                Request.Builder().url("https://player-api.yangshipin.cn/v1/player/get_live_info")
                    .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("yspplayertoken", authToken)
                    .header("yspsdkinput", yspsdkinput)
                    .header("yspsdksign", "$sig2-$yspsdkinput-${AuthSigner.Guid}-$liveSeqId-$liveReqId")
                    .header("yspticket", yspticket),
                liveSeqId, liveReqId
            ).build()

            val liveRes = client.newCall(liveReq).execute().body?.string() ?: return null
            val data = JSONObject(liveRes).optJSONObject("data") ?: return null
            val playUrl = data.optString("playurl")
            val ext = data.optString("extended_param", "")
            val finalUrl = if (playUrl.isNotEmpty()) playUrl + ext else null

            if (finalUrl != null) {
                upstreamM3u8Cache[ch.id] = Pair(finalUrl, now)
                LogManager.log("[取流就绪] ${ch.name} 拿到官方播放流")
            }
            finalUrl
        } catch (e: Exception) {
            LogManager.log("[取流失败] ${ch.name}: ${e.message}")
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

    private fun applyHeaders(builder: Request.Builder, seqId: String, reqId: String): Request.Builder {
        return builder
            .header("User-Agent", AuthSigner.Ua)
            .header("Referer", "https://yangshipin.cn/")
            .header("Origin", "https://yangshipin.cn")
            .header("Accept", "application/json, text/plain, */*")
            .header("yspappid", AuthSigner.YspAppId)
            .header("seqid", seqId)
            .header("request-id", reqId)
            .header("Cookie", "${AuthSigner.Cookie} nseqId=$seqId; nrequest-id=$reqId")
    }
}
