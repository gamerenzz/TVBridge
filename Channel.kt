package com.hbtv.bridge

data class Channel(val id: String, val code: String, val name: String)

val CHANNELS = listOf(
    Channel("431", "hbws", "湖北卫视"),
    Channel("432", "hbjs", "湖北经视"),
    Channel("433", "hbzh", "湖北综合"),
    Channel("435", "hbys", "湖北影视"),
    Channel("437", "hbjy", "湖北教育"),
    Channel("438", "hbls", "垄上频道")
)
