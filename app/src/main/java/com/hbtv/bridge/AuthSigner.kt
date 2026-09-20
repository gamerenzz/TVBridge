package com.hbtv.bridge

import java.security.MessageDigest
import java.text.Collator
import java.util.*

object AuthSigner {
    const val YspAppId = "519748109"
    const val Guid = "mra3u75l_jdrj8csvhkk"
    const val VappId = "59306155"
    const val Vsecret = "b42702bf7309a179d102f3d51b1add2fda0bc7ada64cb801"
    const val Cookie = "guid=mra3u75l_jdrj8csvhkk; versionName=99.99.99; versionCode=999999; vplatform=109; platformVersion=Chrome; deviceModel=150; newLogin=1; pc_version=1.1.16; ysp_uinfo_pc=;"
    const val Ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    const val AuthSalt = "n@7QKk%YeSjfw%22"
    const val LiveSaltTc = "0f\$IVHi9Qno?G"

    fun randStr(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = Random()
        return (1..len).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // 官方 su(): MD5( key字母序 + AuthSalt )
    fun computeAuthSignature(pid: String, guid: String, randStr: String): String {
        val map = sortedMapOf(
            "appid" to "ysp_pc",
            "guid" to guid,
            "pid" to pid,
            "rand_str" to randStr
        )
        val sb = StringBuilder()
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append("&") else first = false
            sb.append("$k=$v")
        }
        sb.append(AuthSalt)
        return md5(sb.toString())
    }

    // ★ 关键修复：必须用 Collator 对齐 JS 的 localeCompare 规则，否则 appVer 和 app_version 排序颠倒直接 20401
    fun computeLiveSdkInput(fields: Map<String, String>): String {
        val collator = Collator.getInstance(Locale.US)
        val list = fields.filter { it.key != "rand_str" && it.key != "signature" }.toList()
            .sortedWith { o1, o2 -> collator.compare(o1.first, o2.first) }
        val sb = list.joinToString("&") { "${it.first}=${it.second}" }
        return md5(sb)
    }

    // 官方 au(): 必须用默认 Ordinal 序 + LiveSaltTc 盐
    fun computeLiveBodySignature(fields: Map<String, String>): String {
        val list = fields.filter { it.key != "signature" }.toList()
            .sortedWith { o1, o2 -> o1.first.compareTo(o2.first) }
        val sb = list.joinToString("&") { "${it.first}=${it.second}" } + LiveSaltTc
        return md5(sb)
    }
}
