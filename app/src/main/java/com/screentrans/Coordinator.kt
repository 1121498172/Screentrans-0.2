package com.screentrans

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * 一次「扫描 → 翻译 → 上屏」的编排。
 *
 * 策略（0.2.1 起）：
 *  - 有缓存用缓存；
 *  - 没缓存先上端侧机翻当占位（淡色显示），保证屏幕上不留空洞；
 *  - 同时把句子丢进 LLM 队列，精修回来后原地替换成实色。
 *
 * 0.2 曾经为了质量取消过占位，结果 LLM 一抖动就整条空白 —— 那是漏抓感的来源。
 */
class Coordinator(
    context: Context,
    private val prefs: Prefs,
    scope: CoroutineScope,
    onRefined: (RawItem, String) -> Unit,
    onProgress: () -> Unit
) {

    private val appContext = context.applicationContext
    private val cache = CacheStore.cache
    private val offline = OfflineTranslator(appContext)
    private val llm = LlmTranslator(prefs)
    private val refiner = LlmRefiner(prefs, llm, cache, scope, onRefined, onProgress)

    private val metrics = appContext.resources.displayMetrics
    private val density = metrics.density
    private val scaledDensity = metrics.scaledDensity

    suspend fun firstPass(items: List<RawItem>): List<RenderItem> {
        Diagnostics.begin(items.size)
        val out = ArrayList<RenderItem>(items.size)
        for (item in items) {
            val cached = cache.get(item.text)
            var zh: String? = null
            var fromLlm = false

            if (cached != null) {
                zh = cached.text
                fromLlm = cached.fromLlm
            } else if (prefs.offlineEnabled) {
                val offlineText = offline.translate(item.text)
                if (offlineText != null) {
                    cache.put(item.text, offlineText, false)
                    zh = offlineText
                }
            }

            if (zh.isNullOrBlank()) {
                Diagnostics.add(item.text, null, "没翻出来")
                continue
            }
            // 用户名、昵称、Discord 这类不需要翻的，翻完还是原样，就别贴块了
            if (sameText(item.text, zh)) {
                Diagnostics.add(item.text, null, "同原文跳过")
                continue
            }

            val provisional = !fromLlm && prefs.llmEnabled
            out.add(build(item, zh, provisional))
            Diagnostics.add(item.text, zh, if (provisional) "临时" else "完成")
        }
        refiner.enqueue(items)
        Diagnostics.markRendered(out.size)

        ScanStats.captured = items.size
        ScanStats.translated = out.size
        ScanStats.provisional = out.count { it.provisional }
        ScanStats.at = System.currentTimeMillis()
        return out
    }

    private fun build(item: RawItem, zh: String, provisional: Boolean): RenderItem {
        val pad = 2f * density
        val size = TextMeasure.sizeFor(
            translated = zh,
            widthPx = item.rect.width(),
            heightPx = item.rect.height(),
            scaledDensity = scaledDensity,
            paddingPx = pad,
            maxSpScale = prefs.fontScale
        )
        return RenderItem(item, zh, size, isCentered(item, size), provisional)
    }

    private fun isCentered(item: RawItem, sizePx: Float): Boolean {
        val cls = item.className.orEmpty()
        if (cls.contains("Button")) return true
        if (!item.clickable) return false
        val textWidth = TextMeasure.textWidthPx(item.text, sizePx)
        return textWidth * 1.35f >= item.rect.width()
    }

    private fun sameText(source: String, translated: String): Boolean =
        source.trim().equals(translated.trim(), ignoreCase = true)

    fun close() {
        refiner.stop()
        offline.close()
    }
}
