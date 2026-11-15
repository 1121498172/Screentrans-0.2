package com.screentrans

import android.content.Context
import android.content.SharedPreferences

class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("screentrans", Context.MODE_PRIVATE)

    val raw: SharedPreferences get() = sp

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** hybrid = 离线先出 + LLM 精修；offline = 只用离线；llm = 只用大模型 */
    var mode: String
        get() = sp.getString(KEY_MODE, MODE_HYBRID) ?: MODE_HYBRID
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = sp.edit().putString(KEY_BASE_URL, value).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value).apply()

    var fontScale: Float
        get() = sp.getFloat(KEY_FONT_SCALE, 1f)
        set(value) = sp.edit().putFloat(KEY_FONT_SCALE, value).apply()

    var backgroundAlpha: Int
        get() = sp.getInt(KEY_BG_ALPHA, 255)
        set(value) = sp.edit().putInt(KEY_BG_ALPHA, value).apply()

    /** 覆盖层纵向微调（dp）。个别 ROM 的悬浮窗坐标会有状态栏级偏移，用它校准。 */
    var offsetY: Int
        get() = sp.getInt(KEY_OFFSET_Y, 0)
        set(value) = sp.edit().putInt(KEY_OFFSET_Y, value).apply()

    /** auto / dark / light */
    var theme: String
        get() = sp.getString(KEY_THEME, "auto") ?: "auto"
        set(value) = sp.edit().putString(KEY_THEME, value).apply()

    var extraGlossary: String
        get() = sp.getString(KEY_GLOSSARY, "") ?: ""
        set(value) = sp.edit().putString(KEY_GLOSSARY, value).apply()

    /** 最近一次 LLM 调用失败的原因，用来判断到底有没有走 DeepSeek */
    var lastLlmError: String
        get() = sp.getString(KEY_LAST_LLM_ERROR, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_LLM_ERROR, value).apply()

    var whitelist: Set<String>
        get() = sp.getStringSet(KEY_WHITELIST, DEFAULT_WHITELIST)?.toSet() ?: DEFAULT_WHITELIST
        set(value) = sp.edit().putStringSet(KEY_WHITELIST, value).apply()

    val offlineEnabled: Boolean get() = mode != MODE_LLM
    val llmEnabled: Boolean get() = mode != MODE_OFFLINE && apiKey.isNotBlank()

    companion object {
        const val MODE_HYBRID = "hybrid"
        const val MODE_OFFLINE = "offline"
        const val MODE_LLM = "llm"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"

        val DEFAULT_WHITELIST = setOf("com.discord")

        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODE = "mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_BG_ALPHA = "bg_alpha"
        private const val KEY_OFFSET_Y = "offset_y"
        private const val KEY_THEME = "theme"
        private const val KEY_GLOSSARY = "glossary"
        private const val KEY_LAST_LLM_ERROR = "last_llm_error"
        private const val KEY_WHITELIST = "whitelist"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
