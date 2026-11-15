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

        private const val MIN_SCAN_INTERVAL = 260L
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
    private var pendingAt = 0L
    private var lastScanAt = 0L
    private var lastScrollAt = 0L
    private var scanning = false
    private var rescanQueued = false
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
            onProgress = { schedule(120) }
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
                // 换窗口/换页面，旧截图作废
                resetShot()
                schedule(180)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule(300)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollAt = SystemClock.uptimeMillis()
                overlay.clear()
                schedule(220)
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
            rescanQueued = false
            if (::overlay.isInitialized) overlay.clear()
        }
    }

    /**
     * 调度原则：
     *  1. 绝不丢事件——被节流时不是 return，而是把扫描推迟到间隔之后；
     *  2. 正在扫描时不再取消它（取消会把翻了一半的结果全丢掉），只记一个「待补扫」；
     *  3. 滚动结束后补扫一次，防止滚动途中扫到的是半屏。
     */
    private fun schedule(delayMs: Long) {
        mainHandler.post {
            if (!prefs.enabled) return@post
            if (scanning) {
                rescanQueued = true
                return@post
            }
            val target = SystemClock.uptimeMillis() + delayMs
            if (pendingAt != 0L && pendingAt <= target) return@post
            pendingAt = target
            scanJob?.cancel()
            scanJob = scope.launch {
                delay(delayMs)
                pendingAt = 0L
                val gap = SystemClock.uptimeMillis() - lastScanAt
                if (gap < MIN_SCAN_INTERVAL) delay(MIN_SCAN_INTERVAL - gap)

                lastScanAt = SystemClock.uptimeMillis()
                scanning = true
                try {
                    scanNow()
                } catch (t: Throwable) {
                    // 单次扫描失败不能把服务拖死
                } finally {
                    scanning = false
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastScrollAt < 900L) {
                    // 刚滚动过，停手后再补一次
                    schedule(420)
                } else if (rescanQueued) {
                    rescanQueued = false
                    schedule(150)
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
