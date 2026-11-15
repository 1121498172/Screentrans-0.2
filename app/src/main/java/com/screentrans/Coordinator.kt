package com.screentrans

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * 一次「扫描 → 翻译 → 上屏」的编排：
 * 先用缓存 / 离线结果立刻出中文，同时把没翻过的句子丢进 LLM 队列，
 * 精修结果回来后由覆盖层原地替换。
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
        val out = ArrayList<RenderItem>(items.size)
        for (item in items) {
            val cached = cache.get(item.text)
            val zh: String? = when {
                cached != null -> cached.text
                prefs.offlineEnabled && allowOffline(item.text) -> offline.translate(item.text)?.also {
                    cache.put(item.text, it, false)
                }
                else -> null
            }
            if (zh.isNullOrBlank()) continue
            // 用户名、昵称、Discord 这类不需要翻的，翻完还是原样，就别贴块了
            if (sameText(item.text, zh)) continue
            out.add(build(item, zh))
        }
        refiner.enqueue(items)
        return out
    }

    /**
     * 混合模式下，端侧直译只用来应付短 UI 标签。
     * 聊天正文（长句、带空格）宁愿空着等 LLM，也不先把「微生物」这种东西糊上去。
     */
    private fun allowOffline(text: String): Boolean {
        if (prefs.mode == Prefs.MODE_OFFLINE) return true
        if (!prefs.llmEnabled) return true
        return text.contains(' ') || text.length >= 12
    }

    private fun sameText(source: String, translated: String): Boolean =
        source.trim().equals(translated.trim(), ignoreCase = true)

    private fun build(item: RawItem, zh: String): RenderItem {
        val pad = 2f * density
        val size = TextMeasure.sizeFor(
            translated = zh,
            widthPx = item.rect.width(),
            heightPx = item.rect.height(),
            scaledDensity = scaledDensity,
            paddingPx = pad,
            maxSpScale = prefs.fontScale
        )
        return RenderItem(item, zh, size, isCentered(item, size))
    }

    private fun isCentered(item: RawItem, sizePx: Float): Boolean {
        val cls = item.className.orEmpty()
        if (cls.contains("Button")) return true
        if (!item.clickable) return false
        val textWidth = TextMeasure.textWidthPx(item.text, sizePx)
        return textWidth * 1.35f >= item.rect.width()
    }

    fun close() {
        refiner.stop()
        offline.close()
    }
}
