package com.screentrans

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容接口（默认 DeepSeek）。一次请求翻一整屏文本，保持顺序，
 * 顺带把命中的术语表塞进 prompt。
 */
class LlmTranslator(private val prefs: Prefs) {

    @Volatile
    var lastError: String? = null
        private set

    suspend fun translateBatch(texts: List<String>, glossary: Map<String, String>): List<String>? =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val key = prefs.apiKey.trim()
            if (key.isEmpty()) {
                lastError = "未填写 API Key"
                prefs.lastLlmError = "未填写 API Key"
                return@withContext null
            }
            var attempt = 0
            while (attempt < 2) {
                try {
                    val result = call(texts, glossary, key)
                    if (result != null) {
                        lastError = null
                        prefs.lastLlmError = ""
                        return@withContext result
                    }
                    lastError = "返回格式不合法"
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
                attempt++
                if (attempt < 2) delay(600)
            }
            prefs.lastLlmError = lastError ?: "调用失败"
            null
        }

    private fun call(texts: List<String>, glossary: Map<String, String>, key: String): List<String>? {
        val base = prefs.baseUrl.trim().ifBlank { Prefs.DEFAULT_BASE_URL }.trimEnd('/')
        val url = URL("$base/chat/completions")

        val userPayload = JSONObject().apply {
            if (glossary.isNotEmpty()) {
                val g = JSONObject()
                glossary.forEach { (k, v) -> g.put(k, v) }
                put("glossary", g)
            }
            put("texts", JSONArray().also { arr -> texts.forEach { arr.put(it) } })
        }

        val body = JSONObject().apply {
            put("model", prefs.model.trim().ifBlank { Prefs.DEFAULT_MODEL })
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("stream", false)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", userPayload.toString()))
                }
            )
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                lastError = "HTTP $code ${raw.take(160)}"
                return null
            }
            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val json = extractJson(content)
            val arr = JSONObject(json).optJSONArray("translations") ?: return null
            if (arr.length() != texts.size) return null
            return (0 until arr.length()).map { arr.optString(it, "") }
        } finally {
            conn.disconnect()
        }
    }

    private fun extractJson(content: String): String {
        var t = content.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.dropLast(3).trim()
        }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) t = t.substring(start, end + 1)
        return t
    }

    companion object {
        private val SYSTEM_PROMPT = """
            你是 Discord / Patreon 的中文本地化译者，翻的是玩家和粉丝之间的真实聊天。

            硬规则：
            1. 输出简体中文，口语自然，像中文母语者在群里说话，不要翻译腔、不要书面语。
            2. 用户名、昵称、频道名、服务器名、游戏专名、链接、@提及、代码块、emoji、数字一律原样保留，绝不音译。
            3. 脏话、性暗示、攻击性玩笑保留原来的粗俗程度和语气，不要净化、不要改成文明说法。
            4. 游戏和剧情语境按玩家习惯用词：foreshadowing=伏笔，jacked=肌肉发达，micropenis=屌小，clutch=关键发挥，nerf=削弱，mid=很一般，based=有态度，goat=最强。
            5. 术语表是强约束：命中就必须使用给定译法。
            6. 按整句意思翻，不要逐词对应；可以比原文略长，但要能塞进一行。
            7. 只输出 JSON：{"translations":["...","..."]}，条数和顺序必须和输入完全一致，不要任何解释。

            示例：
            输入 {"texts":["Monster fucking foreshadowing","I'm still skeptical because of micropenis","another disappointment incoming","he's too jacked"]}
            输出 {"translations":["怪物他妈的伏笔","我还是怀疑，因为他屌太小","又要让人失望了","他肌肉太发达了"]}
        """.trimIndent()
    }
}
