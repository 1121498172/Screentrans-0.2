package com.screentrans

import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache

/**
 * 覆盖层排版的核心：给一段中文，算出「塞进原控件矩形里最大能用多大字号」。
 * 英文换成中文后长度差异很大，硬编码字号必然错位，所以这里按矩形实测。
 * 用二分而不是逐档递减，一屏 80 条文本也只有几百次布局计算。
 */
object TextMeasure {

    private val cache = LruCache<String, Float>(2048)

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    fun sizeFor(
        translated: String,
        widthPx: Int,
        heightPx: Int,
        scaledDensity: Float,
        paddingPx: Float,
        maxSpScale: Float = 1f
    ): Float {
        if (widthPx <= 0 || heightPx <= 0 || translated.isEmpty()) return 12f * scaledDensity
        val key = "${translated.hashCode()}|$widthPx|$heightPx|$scaledDensity|$maxSpScale"
        cache.get(key)?.let { return it }

        val availW = (widthPx - paddingPx * 2f).toInt().coerceAtLeast(12)
        val dp = heightPx / scaledDensity
        val baseMaxSp = when {
            dp < 18f -> 12.5f
            dp < 26f -> 16f
            else -> 17f
        }
        // 字号缩放只抬高「上限」，最终仍按矩形实测，保证不会溢出控件
        val maxSp = baseMaxSp * maxSpScale.coerceIn(0.6f, 1.8f)
        val result = largestFitting(translated, availW, heightPx, scaledDensity, maxSp)
        cache.put(key, result)
        return result
    }

    fun textWidthPx(text: String, sizePx: Float): Float = synchronized(this) {
        paint.textSize = sizePx
        paint.measureText(text)
    }

    private fun largestFitting(
        text: String,
        widthPx: Int,
        heightPx: Int,
        scaledDensity: Float,
        maxSp: Float
    ): Float = synchronized(this) {
        val minSp = 7.5f
        when {
            maxSp <= minSp -> minSp * scaledDensity
            fits(text, widthPx, heightPx, maxSp * scaledDensity) -> maxSp * scaledDensity
            else -> {
                var low = minSp
                var high = maxSp
                repeat(6) {
                    val mid = (low + high) / 2f
                    if (fits(text, widthPx, heightPx, mid * scaledDensity)) low = mid else high = mid
                }
                low * scaledDensity
            }
        }
    }

    private fun fits(text: String, widthPx: Int, heightPx: Int, sizePx: Float): Boolean {
        paint.textSize = sizePx
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
        return layout.height <= heightPx
    }
}
