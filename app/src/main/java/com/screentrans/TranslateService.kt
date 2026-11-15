package com.screentrans

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TranslateService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TranslateService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var coordinator: Coordinator
    private lateinit var sampler: ScreenSampler

    private var shot: Bitmap? = null
    private var shotPkg: String? = null

    private var scanJob: Job? = null
    private var lastScanAt = 0L
    private var pendingAt = 0L
    private var lastPkg: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (::prefs.isInitialized) {
            when (key) {
                "enabled" -> if (prefs.enabled) schedule(150) else clearNow()
                "whitelist" -> schedule(200)
                "mode" -> schedule(150)
                "theme" -> schedule(150)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs.get(this)
        Scanner.TotalScreen.height = resources.displayMetrics.heightPixels
        overlay = OverlayController(this, prefs)
        sampler = ScreenSampler(this)
        coordinator = Coordinator(
            context = this,
            prefs = prefs,
            scope = scope,
            onRefined = { item, zh -> mainHandler.post { overlay.update(item.viewKey, zh) } },
            onProgress = { schedule(200) }
        )
        prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
        schedule(300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!::overlay.isInitialized) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (!prefs.enabled) {
            if (overlay.hasContent()) clearNow()
            return
        }
        if (pkg !in prefs.whitelist) {
            if (lastPkg != pkg) {
                overlay.clear()
                lastPkg = pkg
            }
            return
        }

        lastPkg = pkg
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 换窗口/换页面，旧截图作废，否则会把上一屏的颜色套到新页面上
                resetShot()
                schedule(180)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule(420)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 滚动时先撤掉旧译文，避免文字「贴」在错误位置
                overlay.clear()
                schedule(260)
            }
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) {
            prefs.raw.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        scanJob?.cancel()
        if (::coordinator.isInitialized) coordinator.close()
        if (::overlay.isInitialized) overlay.clear()
        recycleShot()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun resetShot() {
        overlay.clear()
        recycleShot()
    }

    private fun recycleShot() {
        try {
            shot?.recycle()
        } catch (t: Throwable) {
            // ignore
        }
        shot = null
        shotPkg = null
    }

    private fun clearNow() {
        mainHandler.post {
            scanJob?.cancel()
            pendingAt = 0L
            if (::overlay.isInitialized) overlay.clear()
        }
    }

    private fun schedule(delayMs: Long) {
        mainHandler.post {
            if (prefs.enabled) {
                val target = SystemClock.uptimeMillis() + delayMs
                // 已经有更早的扫描排队时不要往后推：Discord 的内容变化事件很密，
                // 纯防抖会导致扫描永远被推迟、译文一直不出现。
                if (pendingAt == 0L || pendingAt > target) {
                    pendingAt = target
                    scanJob?.cancel()
                    scanJob = scope.launch {
                        delay(delayMs)
                        pendingAt = 0L
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScanAt >= 200L) {
                            lastScanAt = now
                            scanNow()
                        }
                    }
                }
            }
        }
    }

    private suspend fun scanNow() {
        if (!prefs.enabled) {
            overlay.clear()
            return
        }
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName || pkg !in prefs.whitelist) {
            overlay.clear()
            return
        }
        val items = Scanner.collect(
            root = root,
            selfPackage = packageName,
            pkg = pkg,
            density = resources.displayMetrics.density
        )
        if (items.isEmpty()) {
            overlay.clear()
            return
        }

        // 截图要在覆盖层画上去之前拍，否则会把自己的色块采样进去
        if (shot == null || shotPkg != pkg) {
            overlay.clear()
            shot?.recycle()
            shot = sampler.capture()
            shotPkg = pkg
        }

        val rendered = coordinator.firstPass(items)
        overlay.show(rendered, shot)
    }
}
