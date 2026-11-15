package com.screentrans

import android.graphics.Rect

data class RawItem(
    val pkg: String,
    val viewKey: String,
    val text: String,
    val rect: Rect,
    val className: String?,
    val clickable: Boolean
)

data class RenderItem(
    val raw: RawItem,
    val translated: String,
    val textSizePx: Float,
    val centered: Boolean,
    /** true = 端侧机翻占位，等 LLM 覆盖；界面上画淡一点 */
    val provisional: Boolean
)

/** 最近一次扫描的统计，用来判断「没抓到」还是「抓到了没翻出来」 */
object ScanStats {
    @Volatile var captured: Int = 0
    @Volatile var translated: Int = 0
    @Volatile var provisional: Int = 0
    @Volatile var at: Long = 0L
}
