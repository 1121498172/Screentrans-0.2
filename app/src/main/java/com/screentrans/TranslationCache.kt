package com.screentrans

import android.util.LruCache

/**
 * 翻译结果缓存。Discord 消息列表会不断回收复用控件，
 * 同一句话经常反复出现，缓存命中率很高。
 */
class TranslationCache(maxSize: Int = 4000) {

    class Entry(val text: String, val fromLlm: Boolean)

    private val lru = object : LruCache<String, Entry>(maxSize) {}

    @Synchronized
    fun get(source: String): Entry? = lru.get(source)

    @Synchronized
    fun put(source: String, translation: String, fromLlm: Boolean) {
        val old = lru.get(source)
        // 已经有大模型译文时，不要被离线结果覆盖回去
        if (old != null && old.fromLlm && !fromLlm) return
        lru.put(source, Entry(translation, fromLlm))
    }

    @Synchronized
    fun clear() {
        lru.evictAll()
    }

    @Synchronized
    fun size(): Int = lru.size()
}

object CacheStore {
    val cache = TranslationCache()
}
