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
                uri == "/" || uri == "/player" -> {
                    servePlayerHtml()
                }

                uri == "/Library/CMGPlayer.json" -> {
                    serveAssetOrMockJson("CMGPlayer.json", "{\"code\":0,\"data\":{\"switch\":1}}")
                }

                uri == "/media" -> {
                    handleMediaProxy(session)
                }

                // ★ 完整注入 main.go 的 P/B 帧原地解密与内存热修补 ★
                uri.startsWith("/sapi") -> {
                    handleSapiWithFullMainGoPatches(uri)
                }

                uri == "/auth" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/auth", postData, "application/x-www-form-urlencoded", session.headers)
                }

                uri == "/open-token" && method == Method.GET -> {
                    val query = session.queryParameterString ?: ""
                    proxyGet("https://h5access.yangshipin.cn/web/open/token?$query")
                }

                uri == "/get-live-info" && method == Method.POST -> {
                    val map = HashMap<String, String>()
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    proxyPost("https://player-api.yangshipin.cn/v1/player/get_live_info", postData, "application/json; charset=utf-8", session.headers)
                }

                uri.startsWith("/capi/") -> {
                    proxyGet("https://capi.yangshipin.cn" + uri.removePrefix("/capi"))
                }

                uri == "/hbtv/ts" -> {
                    handleHbtvTs(params)
                }

                uri.startsWith("/hbtv/") && uri.endsWith(".m3u8") -> {
                    handleHbtvM3u8(uri)
                }

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
            LogManager.log("[Media代理] 成功解析 M3U8")
            val res = newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", rewritten)
            res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            res
        } else {
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            LogManager.log("[Media代理] 传输切片: ${bytes.size / 1024} KB")
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

    // ★★★ 核心：完整还原 main.go 中对 hls.cmg.js 的全部原地补解密代码 ★★★
    private fun handleSapiWithFullMainGoPatches(uri: String): Response {
        val pathOnly = if (uri.contains('?')) uri.substringBefore('?') else uri
        val rawSub = pathOnly.removePrefix("/sapi").trim('/')
        val cacheKey = rawSub.replace('/', '_')

        val candidates = listOf("sapi_cache/$cacheKey", "sapi_cache/$rawSub", cacheKey, rawSub)

        for (assetPath in candidates) {
            try {
                if (assetPath.endsWith(".bin")) {
                    val bytes = context.assets.open(assetPath).readBytes()
                    val res = newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(bytes), bytes.size.toLong())
                    res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    return res
                } else {
                    var text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
                    text = text.replace("https://sapi.yangshipin.cn", "/sapi")

                    // 1. IndexedDB 门控补丁
                    if (text.contains("EM_IDB_STORE")) {
                        val fetchGateOld = "if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                        val fetchGateNew = "var __cmgRW=function(p){try{var u=UTF8ToString(p);if(/yangshipin\\.cn|cctv\\.cn/.test(u)&&u.indexOf('127.0.0.1')<0){var nu='http://127.0.0.1:18888/media?u='+encodeURIComponent(u);var b=[];for(var i=0;i<nu.length;i++)b.push(nu.charCodeAt(i));b.push(0);var np=_malloc(b.length);if(np){for(var j=0;j<b.length;j++)HEAPU8[np+j]=b[j];HEAPU32[p>>2]=np;return nu;}}}catch(e){}return null;};if(\"EM_IDB_STORE\"!==r&&\"EM_IDB_DELETE\"!==r){try{__cmgRW(HEAPU32[A+8>>2]);}catch(_e){}__emscripten_fetch_xhr(A,o,C,E,Q);return A;}if((!c||\"EM_IDB_STORE\"===r||\"EM_IDB_DELETE\"===r)&&!Fetch.dbInstance)return C(A),0;"
                        text = text.replace(fetchGateOld, fetchGateNew)
                    }

                    // 2. ★ 完整无损注入 main.go 的 earlyWrap 与 cmgDecNew (含 NALU 1 / 5 原地覆写逻辑) ★
                    if (assetPath.contains("hls.cmg.js")) {
                        val earlyWrap = """
                            (function(){
                              if(window.__cmgEarlyInstalled) return;
                              window.__cmgEarlyInstalled = true;
                              var __vmBlocks={},__vmReady=false;
                              setTimeout(function(){
                                try{
                                  var mod=window.CNTVH5PlayerModule;
                                  var u8=mod&&mod.HEAPU8||(mod&&mod.asm&&mod.asm.memory&&new Uint8Array(mod.asm.memory.buffer));
                                  if(!u8)return;
                                  var cap=Math.min(u8.length,6700000);
                                  for(var off=6300000;off<cap;off+=4096){
                                    var nz=0;
                                    for(var k=off;k<off+4096&&k<u8.length;k++){if(u8[k]!==0)nz++;}
                                    if(nz>0){__vmBlocks[off]=new Uint8Array(u8.slice(off,off+4096));}
                                  }
                                  __vmReady=true;
                                  if(window.chrome&&window.chrome.webview)window.chrome.webview.postMessage({log:"[VMPATCH3] 内存快照就绪"});
                                }catch(e){}
                              },6000);
                              setInterval(function(){
                                try{
                                  var mod=window.CNTVH5PlayerModule;
                                  var u8=mod&&mod.HEAPU8||(mod&&mod.asm&&mod.asm.memory&&new Uint8Array(mod.asm.memory.buffer));
                                  if(!u8||!__vmReady)return;
                                  for(var off in __vmBlocks){
                                    var saved=__vmBlocks[off];
                                    var o=Number(off);
                                    var diff=0;
                                    for(var k=0;k<4096&&o+k<u8.length;k++){if(u8[o+k]!==saved[k])diff++;}
                                    if(diff>0&&diff<=2048){
                                      for(var k=0;k<4096&&o+k<u8.length;k++){if(u8[o+k]!==saved[k])u8[o+k]=saved[k];}
                                    }
                                  }
                                }catch(e){}
                              },2000);
                            })();
                        """.trimIndent()

                        text = earlyWrap + "\n" + text

                        val cmgDecOld = "fG[wz(0x6bf)](jJ[wz(0x97f)],jJ['config'][wz(0x291)],jN[wz(0x944)],fG[wz(0x9d2)])"
                        val cmgDecOld2 = "fG[wz(0x6bf)](jJ[wz(0x97f)],jJ[wz(0xb0b)][wz(0x291)],jN[wz(0x944)],fG[wz(0x22f)])"

                        // ★ 完整注入 main.go 的真实原地覆写逻辑，确保 P/B/IDR 帧全部解密写回内存！
                        val cmgDecNew = """
                            (function(__in){
                              var __m=jJ[wz(0x97f)],__ts=jJ['config'][wz(0x291)],__k=fG[wz(0x9d2)],__mt=(jJ[wz(0xb0b)]&&jJ[wz(0xb0b)]['mediaTagId'])!=null?jJ[wz(0xb0b)]['mediaTagId']:'NULL';
                              var __out=fG[wz(0x6bf)](__m,__ts,__in,__k);
                              try{
                                if(jN['type']===0x1){
                                  var __id=true;
                                  if(__out&&__in&&__out.length===__in.length){
                                    for(var __z=0;__z<0x100&&__z<__out.length;__z++){if(__out[__z]!==__in[__z]){__id=false;break;}}
                                  } else {__id=false;}
                                  if(__id){
                                    (window.__cmgPB=window.__cmgPB||{})[__mt]={jN:jN,__in:__in,__m:__m,__ts:__ts,__k:__k};
                                  } else {
                                    var __bf=(window.__cmgPB=window.__cmgPB||{})[__mt];
                                    if(__bf&&__bf.jN){
                                      try{
                                        var __fx=fG[wz(0x6bf)](__bf.__m,__bf.__ts,__bf.__in,__bf.__k);
                                        if(__fx&&__fx.length===__bf.__in.length){
                                          if(__bf.jN[0x944]&&typeof __bf.jN[0x944].set==='function'){__bf.jN[0x944].set(__fx);}
                                          if(__bf.jN['data']&&typeof __bf.jN['data'].set==='function'){__bf.jN['data'].set(__fx);}
                                        }
                                        delete (window.__cmgPB)[__mt];
                                      }catch(e){}
                                    }
                                  }
                                } else if(jN['type']===0x5){
                                  try{
                                    var __wd=(jN[0x944]&&jN[0x944].slice)?jN[0x944].slice(0x0):new Uint8Array([0x65,0x01,0x00,0x00,0x00,0x00,0x00,0x00]);
                                    __wd[0x0]=0x41;
                                    fG[wz(0x6bf)](__m,__ts,__wd,__k);
                                  }catch(e){}
                                  var __bf2=(window.__cmgPB=window.__cmgPB||{})[__mt];
                                  if(__bf2&&__bf2.jN){
                                    try{
                                      var __fx2=fG[wz(0x6bf)](__bf2.__m,__bf2.__ts,__bf2.__in,__bf2.__k);
                                      if(__fx2&&__fx2.length===__bf2.__in.length){
                                        if(__bf2.jN[0x944]&&typeof __bf2.jN[0x944].set==='function'){__bf2.jN[0x944].set(__fx2);}
                                        if(__bf2.jN['data']&&typeof __bf2.jN['data'].set==='function'){__bf2.jN['data'].set(__fx2);}
                                      }
                                      delete (window.__cmgPB)[__mt];
                                    }catch(e){}
                                  }
                                }
                              }catch(e){}
                              return __out;
                            })(jN[wz(0x944)])
                        """.trimIndent()

                        val cmgDecNew2 = """
                            (function(__in){
                              var __m=jJ[wz(0x97f)],__lvl=jJ[wz(0xb0b)]||{},__ts=__lvl[wz(0x291)],__k=fG[wz(0x22f)],__mt=(jJ[wz(0xb0b)]&&jJ[wz(0xb0b)]['mediaTagId'])!=null?jJ[wz(0xb0b)]['mediaTagId']:'NULL';
                              var __out=fG[wz(0x6bf)](__m,__ts,__in,__k);
                              try{
                                if(jN['type']===0x1){
                                  var __id=true;
                                  if(__out&&__in&&__out.length===__in.length){
                                    for(var __z=0;__z<0x100&&__z<__out.length;__z++){if(__out[__z]!==__in[__z]){__id=false;break;}}
                                  } else {__id=false;}
                                  if(__id){
                                    (window.__cmgPB=window.__cmgPB||{})[__mt]={jN:jN,__in:__in,__m:__m,__ts:__ts,__k:__k};
                                  } else {
                                    var __bf=(window.__cmgPB=window.__cmgPB||{})[__mt];
                                    if(__bf&&__bf.jN){
                                      try{
                                        var __fx=fG[wz(0x6bf)](__bf.__m,__bf.__ts,__bf.__in,__bf.__k);
                                        if(__fx&&__fx.length===__bf.__in.length){
                                          if(__bf.jN[0x944]&&typeof __bf.jN[0x944].set==='function'){__bf.jN[0x944].set(__fx);}
                                          if(__bf.jN['data']&&typeof __bf.jN['data'].set==='function'){__bf.jN['data'].set(__fx);}
                                        }
                                        delete (window.__cmgPB)[__mt];
                                      }catch(e){}
                                    }
                                  }
                                } else if(jN['type']===0x5){
                                  try{
                                    var __wd=(jN[0x944]&&jN[0x944].slice)?jN[0x944].slice(0x0):new Uint8Array([0x65,0x01,0x00,0x00,0x00,0x00,0x00,0x00]);
                                    __wd[0x0]=0x41;
                                    fG[wz(0x6bf)](__m,__ts,__wd,__k);
                                  }catch(e){}
                                  var __bf2=(window.__cmgPB=window.__cmgPB||{})[__mt];
                                  if(__bf2&&__bf2.jN){
                                    try{
                                      var __fx2=fG[wz(0x6bf)](__bf2.__m,__bf2.__ts,__bf2.__in,__bf2.__k);
                                      if(__fx2&&__fx2.length===__bf2.__in.length){
                                        if(__bf2.jN[0x944]&&typeof __bf2.jN[0x944].set==='function'){__bf2.jN[0x944].set(__fx2);}
                                        if(__bf2.jN['data']&&typeof __bf2.jN['data'].set==='function'){__bf2.jN['data'].set(__fx2);}
                                      }
                                      delete (window.__cmgPB)[__mt];
                                    }catch(e){}
                                  }
                                }
                              }catch(e){}
                              return __out;
                            })(jN[wz(0x944)])
                        """.trimIndent()

                        text = text.replace(cmgDecOld, cmgDecNew)
                        text = text.replace(cmgDecOld2, cmgDecNew2)
                        LogManager.log("[SAPI] hls.cmg.js 全量补解密补丁已注入完成")
                    }

                    val mime = if (assetPath.endsWith(".js")) "application/javascript; charset=utf-8" else "application/octet-stream"
                    val res = newFixedLengthResponse(Response.Status.OK, mime, text)
                    res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                    return res
                }
            } catch (e: Exception) {
                // ignore
            }
        }

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
