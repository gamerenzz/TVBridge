package com.hbtv.bridge

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ChannelAuthTokens(
    val authToken: String,
    val sessionToken: String,
    val authTs: String,
    val expireAt: Long
)

object StreamResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val seqCounter = AtomicLong(System.currentTimeMillis() / 1000)
    private val tokenCache = ConcurrentHashMap<String, ChannelAuthTokens>()
    private val m3u8Cache = ConcurrentHashMap<String, Pair<String, Long>>()

    fun resolveCctvStream(ch: TvChannel): String? {
        val now = System.currentTimeMillis()
        val cached = m3u8Cache[ch.id]
        if (cached != null && now - cached.second < 120000) {
            return cached.first
        }

        return try {
            val tokens = getChannelTokens(ch) ?: return null
            val tsSec = (now / 1000).toString()

            // 构造合法的 cKey (324位) 与 yspticket (124位)
            val cKey = generateMockCKey(ch.cnlId, tsSec, ch.pid)
            val yspticket = generateMockYspTicket(ch.pid, tokens.authTs, ch.cnlId)

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

            val sig2 = AuthSigner.computeAuthSignature(ch.pid, AuthSigner.Guid, yspsdkinput)

            val bodyJson = JSONObject().apply {
                for ((k, v) in liveFields) put(k, v)
                put("signature", bodySig)
                put("adjust", 1)
            }.toString()

            val liveReqBuilder = Request.Builder()
                .url("https://player-api.yangshipin.cn/v1/player/get_live_info")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("yspplayertoken", tokens.authToken)
                .header("yspsdkinput", yspsdkinput)
                .header("yspsdksign", "$sig2-$yspsdkinput-${AuthSigner.Guid}-$liveSeqId-$liveReqId")
                .header("yspticket", yspticket)

            val liveReq = applyBrowserHeaders(liveReqBuilder, liveSeqId, liveReqId).build()
            val liveResp = client.newCall(liveReq).execute()
            val liveRes = liveResp.body?.string() ?: ""

            val data = JSONObject(liveRes).optJSONObject("data") ?: return null
            val playUrl = data.optString("playurl")
            val ext = data.optString("extended_param", "")
            val finalUrl = if (playUrl.isNotEmpty()) playUrl + ext else null

            if (finalUrl != null) {
                m3u8Cache[ch.id] = Pair(finalUrl, now)
                LogManager.log("[取流成功] ${ch.name} 获取到官方切片流")
            }
            finalUrl
        } catch (e: Exception) {
            LogManager.log("[取流异常] ${ch.name}: ${e.message}")
            null
        }
    }

    private fun getChannelTokens(ch: TvChannel): ChannelAuthTokens? {
        val now = System.currentTimeMillis()
        val cached = tokenCache[ch.pid]
        if (cached != null && now < cached.expireAt) return cached

        return try {
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

            val authRes = client.newCall(authReq).execute().body?.string() ?: return null
            val authJson = JSONObject(authRes)
            val token = authJson.optJSONObject("data")?.optString("token") ?: return null
            val authTs = authJson.optJSONObject("data")?.optString("ts") ?: (now / 1000).toString()

            val rndVal = AuthSigner.randStr(32)
            val openUrl = "https://h5access.yangshipin.cn/web/open/token?yspappid=${AuthSigner.YspAppId}&guid=${AuthSigner.Guid}&vappid=${AuthSigner.VappId}&vsecret=${AuthSigner.Vsecret}&raw=1&version=v1&ts=$ts&rnd=$rndVal"
            val openReq = Request.Builder().url(openUrl)
                .header("User-Agent", AuthSigner.Ua)
                .header("Referer", "https://yangshipin.cn/")
                .header("Origin", "https://yangshipin.cn")
                .header("Accept", "*/*")
                .build()

            val openRes = client.newCall(openReq).execute().body?.string() ?: return null
            val sessionToken = JSONObject(openRes).optJSONObject("data")?.optString("token") ?: return null

            val result = ChannelAuthTokens(token, sessionToken, authTs, now + 15 * 60 * 1000L)
            tokenCache[ch.pid] = result
            result
        } catch (e: Exception) {
            null
        }
    }

    private fun generateMockCKey(cnlId: String, tsSec: String, pid: String): String {
        return "--01" + AuthSigner.randStr(320)
    }

    private fun generateMockYspTicket(pid: String, authTs: String, cnlId: String): String {
        return AuthSigner.randStr(124)
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
