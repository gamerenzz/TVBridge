package com.hbtv.bridge

enum class ChannelGroup(val title: String) {
    CCTV("央视频道"),
    SATELLITE("各大卫视"),
    LOCAL("湖北地方")
}

data class TvChannel(
    val id: String,
    val name: String,
    val pid: String,
    val cnlId: String,
    val group: ChannelGroup
)

object ChannelRepository {
    val channels = listOf(
        // ===== 央视频道组 =====
        TvChannel("cctv1", "CCTV-1 综合", "600001859", "2024078203", ChannelGroup.CCTV),
        TvChannel("cctv2", "CCTV-2 财经", "600001800", "2024075403", ChannelGroup.CCTV),
        TvChannel("cctv3", "CCTV-3 综艺", "600001801", "2024068503", ChannelGroup.CCTV),
        TvChannel("cctv4", "CCTV-4 中文国际", "600001814", "2029797103", ChannelGroup.CCTV),
        TvChannel("cctv5", "CCTV-5 体育", "600001818", "2024078403", ChannelGroup.CCTV),
        TvChannel("cctv5p", "CCTV-5+ 赛事", "600001817", "2024078003", ChannelGroup.CCTV),
        TvChannel("cctv6", "CCTV-6 电影", "600108442", "2013693901", ChannelGroup.CCTV),
        TvChannel("cctv7", "CCTV-7 军事", "600004092", "2024072003", ChannelGroup.CCTV),
        TvChannel("cctv8", "CCTV-8 电视剧", "600001803", "2029793003", ChannelGroup.CCTV),
        TvChannel("cctv9", "CCTV-9 纪录", "600004078", "2024078603", ChannelGroup.CCTV),
        TvChannel("cctv10", "CCTV-10 科教", "600001805", "2024078703", ChannelGroup.CCTV),
        TvChannel("cctv11", "CCTV-11 戏曲", "600001806", "2027248703", ChannelGroup.CCTV),
        TvChannel("cctv12", "CCTV-12 社会与法", "600001807", "2027248803", ChannelGroup.CCTV),
        TvChannel("cctv13", "CCTV-13 新闻", "600001811", "2029797203", ChannelGroup.CCTV),
        TvChannel("cctv14", "CCTV-14 少儿", "600001809", "2027248903", ChannelGroup.CCTV),
        TvChannel("cctv15", "CCTV-15 音乐", "600001815", "2027249003", ChannelGroup.CCTV),
        TvChannel("cctv16", "CCTV-16 奥林匹克", "600098637", "2027249103", ChannelGroup.CCTV),
        TvChannel("cctv17", "CCTV-17 农业农村", "600001810", "2027249403", ChannelGroup.CCTV),
        TvChannel("cctv4k", "CCTV-4K 超高清", "600002264", "2029810303", ChannelGroup.CCTV),

        // ===== 卫视频道组 =====
        TvChannel("hnws", "湖南卫视", "600002475", "2024054803", ChannelGroup.SATELLITE),
        TvChannel("zjws", "浙江卫视", "600002520", "2024054703", ChannelGroup.SATELLITE),
        TvChannel("jsws", "江苏卫视", "600002521", "2024171103", ChannelGroup.SATELLITE),
        TvChannel("dfws", "东方卫视", "600002483", "2024054503", ChannelGroup.SATELLITE),
        TvChannel("bjws", "北京卫视", "600002309", "2024052703", ChannelGroup.SATELLITE),
        TvChannel("sdws", "山东卫视", "600002513", "2029787903", ChannelGroup.SATELLITE),
        TvChannel("gdws", "广东卫视", "600002485", "2024060903", ChannelGroup.SATELLITE),
        TvChannel("ahws", "安徽卫视", "600002532", "2024171403", ChannelGroup.SATELLITE),

        // ===== 湖北地方台 =====
        TvChannel("431", "湖北卫视", "431", "hbws", ChannelGroup.LOCAL),
        TvChannel("432", "湖北经视", "432", "hbjs", ChannelGroup.LOCAL),
        TvChannel("433", "湖北综合", "433", "hbzh", ChannelGroup.LOCAL),
        TvChannel("435", "湖北影视", "435", "hbys", ChannelGroup.LOCAL),
        TvChannel("437", "湖北教育", "437", "hbjy", ChannelGroup.LOCAL),
        TvChannel("438", "垄上频道", "438", "hbls", ChannelGroup.LOCAL)
    )
}
