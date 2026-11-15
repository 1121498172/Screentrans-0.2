package com.screentrans

import android.content.Context
import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 端侧英中翻译，免费、离线、几十毫秒出结果 —— 用来「先贴上」，
 * 再由 LLM 覆盖成更自然的译文。
 *
 * 注意：模型首次下载走 Google 的服务器，国行机没梯子会失败。
 * 失败后 5 分钟内不再重试，直接退化为纯 LLM 模式。
 */
class OfflineTranslator(context: Context) {

    private val mutex = Mutex()
    private var ready = false
    private var lastFailureAt = 0L

    private val translator: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
        )
    }

    suspend fun ensureReady(): Boolean {
        if (ready) return true
        val now = SystemClock.elapsedRealtime()
        if (lastFailureAt != 0L && now - lastFailureAt < 5 * 60 * 1000L) return false
        return mutex.withLock {
            if (ready) return@withLock true
            try {
                translator
                    .downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .await()
                ready = true
                true
            } catch (t: Throwable) {
                lastFailureAt = SystemClock.elapsedRealtime()
                false
            }
        }
    }

    suspend fun translate(text: String): String? {
        if (!ensureReady()) return null
        return try {
            translator.translate(text).await()
        } catch (t: Throwable) {
            null
        }
    }

    fun close() {
        if (ready) runCatching { translator.close() }
    }
}
