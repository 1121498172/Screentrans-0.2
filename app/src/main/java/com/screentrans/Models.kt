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
    val centered: Boolean
)
