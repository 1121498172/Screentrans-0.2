package com.screentrans

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * LLM 精修队列：把屏幕上的文本攒成一批（最多 24 条）再请求，
 * 失败过的句子 60 秒内不重试，避免死循环烧钱。
 */
class LlmRefiner(
    private val prefs: Prefs,
    private val llm: LlmTranslator,
    private val cache: TranslationCache,
    scope: CoroutineScope,
    private val onRefined: (RawItem, String) -> Unit,
    private val onProgress: () -> Unit
) {

    private val queue = ArrayDeque<RawItem>()
    private val queuedTexts = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val lock = Any()

    private val job: Job = scope.launch(Dispatchers.IO) { loop() }

    fun enqueue(items: List<RawItem>) {
        if (!prefs.llmEnabled) return
        synchronized(lock) {
            for (item in items) {
                if (queue.size >= 80) break
                if (item.text in queuedTexts) continue
                val cached = cache.get(item.text)
                if (cached != null && cached.fromLlm) continue
                val failed = failedAt[item.text]
                if (failed != null && SystemClock.elapsedRealtime() - failed < 60_000L) continue
                queue.addLast(item)
                queuedTexts.add(item.text)
            }
        }
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val batch = synchronized(lock) {
                val list = ArrayList<RawItem>(24)
                while (queue.isNotEmpty() && list.size < 24) {
                    val item = queue.removeFirst()
                    queuedTexts.remove(item.text)
                    if (list.none { it.text == item.text }) list.add(item)
                }
                list
            }

            if (batch.isEmpty()) {
                delay(400)
                continue
            }

            val glossary = Glossary.matched(
                batch.joinToString("\n") { it.text },
                Glossary.extra(prefs.extraGlossary)
            )
            val result = llm.translateBatch(batch.map { it.text }, glossary)

            var produced = 0
            if (result == null) {
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) { batch.forEach { failedAt[it.text] = now } }
            } else {
                batch.forEachIndexed { index, item ->
                    val zh = result.getOrNull(index)?.trim().orEmpty()
                    if (zh.isNotEmpty()) {
                        cache.put(item.text, zh, true)
                        onRefined(item, zh)
                        produced++
                    }
                }
            }
            if (produced > 0) onProgress()
            delay(300)
        }
    }

    fun stop() {
        job.cancel()
    }
}
