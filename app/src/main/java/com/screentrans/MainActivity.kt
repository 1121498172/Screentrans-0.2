package com.screentrans

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var offline: OfflineTranslator

    private lateinit var tvA11yStatus: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvTest: TextView
    private lateinit var tvWhitelist: TextView
    private lateinit var tvGlossary: TextView
    private lateinit var tvCache: TextView
    private lateinit var tvFont: TextView
    private lateinit var tvAlpha: TextView
    private lateinit var tvOffset: TextView
    private lateinit var tvLastError: TextView

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var rgMode: RadioGroup
    private lateinit var rgTheme: RadioGroup
    private lateinit var sbFont: SeekBar
    private lateinit var sbAlpha: SeekBar
    private lateinit var sbOffset: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs.get(this)
        offline = OfflineTranslator(applicationContext)

        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        tvModel = findViewById(R.id.tvModel)
        tvTest = findViewById(R.id.tvTest)
        tvWhitelist = findViewById(R.id.tvWhitelist)
        tvGlossary = findViewById(R.id.tvGlossary)
        tvCache = findViewById(R.id.tvCache)
        tvFont = findViewById(R.id.tvFont)
        tvAlpha = findViewById(R.id.tvAlpha)
        tvOffset = findViewById(R.id.tvOffset)
        tvLastError = findViewById(R.id.tvLastError)
        switchEnabled = findViewById(R.id.switchEnabled)
        rgMode = findViewById(R.id.rgMode)
        rgTheme = findViewById(R.id.rgTheme)
        sbFont = findViewById(R.id.sbFont)
        sbAlpha = findViewById(R.id.sbAlpha)
        sbOffset = findViewById(R.id.sbOffset)

        setupPermission()
        setupMode()
        setupLlm()
        setupAppearance()
        setupWhitelist()
        setupGlossary()
        setupCache()

        refreshStatus()
        refreshWhitelist()
        refreshGlossary()
        refreshCache()
        refreshLlmStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshCache()
        refreshLlmStatus()
    }

    private fun refreshLlmStatus() {
        if (!::tvLastError.isInitialized) return
        tvLastError.text = when {
            prefs.mode == Prefs.MODE_OFFLINE ->
                "当前是「仅离线」模式，翻出来的一定是 ML Kit 直译（要质量请换混合模式）"
            !prefs.llmEnabled ->
                "LLM 未启用：API Key 是空的，现在会退回 ML Kit 直译"
            prefs.lastLlmError.isNotBlank() ->
                "上次 LLM 错误：${prefs.lastLlmError.take(160)}"
            else -> "LLM 状态：正常"
        }
    }

    // ---------- 权限 ----------

    private fun setupPermission() {
        switchEnabled.isChecked = prefs.enabled
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            if (checked && !isA11yEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnA11y).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (t: Throwable) {
                Toast.makeText(this, "无法打开系统设置，请手动进入：设置 → 无障碍", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isA11yEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun refreshStatus() {
        val enabled = isA11yEnabled()
        tvA11yStatus.text = if (enabled) "无障碍服务：已开启 ✓" else "无障碍服务：未开启"
        val btn = findViewById<MaterialButton>(R.id.btnA11y)
        btn.text = if (enabled) "无障碍已开启，去系统设置" else "去开启无障碍"
    }

    // ---------- 引擎 ----------

    private fun setupMode() {
        when (prefs.mode) {
            Prefs.MODE_OFFLINE -> rgMode.check(R.id.rbOffline)
            Prefs.MODE_LLM -> rgMode.check(R.id.rbLlm)
            else -> rgMode.check(R.id.rbHybrid)
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            prefs.mode = when (checkedId) {
                R.id.rbOffline -> Prefs.MODE_OFFLINE
                R.id.rbLlm -> Prefs.MODE_LLM
                else -> Prefs.MODE_HYBRID
            }
        }
        findViewById<MaterialButton>(R.id.btnModel).setOnClickListener {
            it.isEnabled = false
            tvModel.text = "正在检查 / 下载离线模型…"
            lifecycleScope.launch {
                val ok = offline.ensureReady()
                tvModel.text = if (ok) {
                    "离线模型已就绪，断网也能秒翻。"
                } else {
                    "下载失败：国内网络通常访问不了 Google 的模型服务。先用「仅 LLM」模式即可，或挂梯子重试。"
                }
                it.isEnabled = true
            }
        }
    }

    private fun setupLlm() {
        val etBaseUrl = findViewById<TextInputEditText>(R.id.etBaseUrl)
        val etApiKey = findViewById<TextInputEditText>(R.id.etApiKey)
        val etModel = findViewById<TextInputEditText>(R.id.etModel)

        etBaseUrl.setText(prefs.baseUrl)
        etApiKey.setText(prefs.apiKey)
        etModel.setText(prefs.model)

        etBaseUrl.doAfterTextChanged { prefs.baseUrl = it?.toString().orEmpty() }
        etApiKey.doAfterTextChanged { prefs.apiKey = it?.toString().orEmpty() }
        etModel.doAfterTextChanged { prefs.model = it?.toString().orEmpty() }

        val btnTest = findViewById<MaterialButton>(R.id.btnTest)
        btnTest.setOnClickListener {
            btnTest.isEnabled = false
            tvTest.text = "测试中…"
            lifecycleScope.launch {
                val samples = listOf("gg wp, that clutch was insane", "ngl this patch is mid")
                val translator = LlmTranslator(prefs)
                val result = translator.translateBatch(
                    samples,
                    Glossary.matched(samples.joinToString(" "))
                )
                tvTest.text = if (result != null && result.size == samples.size) {
                    "✅ " + result.joinToString("  /  ")
                } else {
                    "❌ " + (translator.lastError ?: "调用失败")
                }
                btnTest.isEnabled = true
            }
        }
    }

    // ---------- 外观 ----------

    private fun setupAppearance() {
        sbFont.progress = ((prefs.fontScale - 0.8f) * 100f).toInt().coerceIn(0, 60)
        sbFont.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 0.8f + progress / 100f
                prefs.fontScale = scale
                tvFont.text = "字号缩放：${(scale * 100).toInt()}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        tvFont.text = "字号缩放：${(prefs.fontScale * 100).toInt()}%"

        sbAlpha.progress = (prefs.backgroundAlpha - 80).coerceIn(0, 175)
        sbAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = 80 + progress
                prefs.backgroundAlpha = alpha
                tvAlpha.text = "背景不透明度：${alpha * 100 / 255}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        tvAlpha.text = "背景不透明度：${prefs.backgroundAlpha * 100 / 255}%"

        sbOffset.progress = (prefs.offsetY + 30).coerceIn(0, 60)
        sbOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = progress - 30
                prefs.offsetY = offset
                tvOffset.text = "纵向微调：$offset dp（若整屏译文整体偏上/偏下再动它）"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        tvOffset.text = "纵向微调：${prefs.offsetY} dp（若整屏译文整体偏上/偏下再动它）"

        when (prefs.theme) {
            "dark" -> rgTheme.check(R.id.rbDark)
            "light" -> rgTheme.check(R.id.rbLight)
            else -> rgTheme.check(R.id.rbAuto)
        }
        rgTheme.setOnCheckedChangeListener { _, checkedId ->
            prefs.theme = when (checkedId) {
                R.id.rbDark -> "dark"
                R.id.rbLight -> "light"
                else -> "auto"
            }
        }
    }

    // ---------- 白名单 ----------

    private fun setupWhitelist() {
        findViewById<MaterialButton>(R.id.btnWhitelist).setOnClickListener {
            showWhitelistDialog()
        }
    }

    private fun refreshWhitelist() {
        val names = prefs.whitelist.joinToString("\n")
        tvWhitelist.text = if (names.isBlank()) "（未选择）" else names
    }

    private fun showWhitelistDialog() {
        val presets = linkedMapOf(
            "com.discord" to "Discord",
            "com.patreon.android" to "Patreon",
            "com.twitter.android" to "X / Twitter",
            "com.reddit.frontpage" to "Reddit",
            "com.google.android.youtube" to "YouTube",
            "com.instagram.android" to "Instagram",
            "com.valvesoftware.android.steam.community" to "Steam",
            "com.tencent.tmgp.sgame" to "王者荣耀"
        )
        val all = LinkedHashMap(presets)
        prefs.whitelist.forEach { if (!all.containsKey(it)) all[it] = it }

        val keys = all.keys.toList()
        val labels = keys.map { all[it] ?: it }.toTypedArray()
        val checked = BooleanArray(keys.size) { keys[it] in prefs.whitelist }

        AlertDialog.Builder(this)
            .setTitle("翻译哪些应用")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selected = keys.filterIndexed { index, _ -> checked[index] }.toSet()
                prefs.whitelist = selected.ifEmpty { setOf("com.discord") }
                refreshWhitelist()
            }
            .setNeutralButton("添加包名") { _, _ -> showAddPackageDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddPackageDialog() {
        val input = EditText(this).apply {
            hint = "例如 com.patreon.android"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle("添加应用包名")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty() && pkg.contains('.')) {
                    prefs.whitelist = prefs.whitelist + pkg
                    refreshWhitelist()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 术语表 ----------

    private fun setupGlossary() {
        findViewById<MaterialButton>(R.id.btnGlossary).setOnClickListener {
            val input = EditText(this).apply {
                setText(prefs.extraGlossary)
                hint = "一行一条，例如：\nwaifu=老婆\nspeedrun=速通"
                gravity = Gravity.TOP or Gravity.START
                minLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            val container = FrameLayout(this).apply {
                setPadding(dp(24), dp(12), dp(24), 0)
                addView(
                    input,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            AlertDialog.Builder(this)
                .setTitle("自定义术语表")
                .setView(container)
                .setPositiveButton("保存") { _, _ ->
                    prefs.extraGlossary = input.text.toString()
                    refreshGlossary()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun refreshGlossary() {
        val count = Glossary.extra(prefs.extraGlossary).size
        tvGlossary.text = if (count == 0) {
            "内置了 Discord / Patreon / 游戏常用译法。这里可追加你自己的，一行一条：english=中文"
        } else {
            "已追加 $count 条自定义术语（内置术语仍然生效）"
        }
    }

    // ---------- 缓存 ----------

    private fun setupCache() {
        findViewById<MaterialButton>(R.id.btnClearCache).setOnClickListener {
            CacheStore.cache.clear()
            refreshCache()
            Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCache() {
        if (!::tvCache.isInitialized) return
        tvCache.text = "已缓存 ${CacheStore.cache.size()} 条译文"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
