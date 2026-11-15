package com.screentrans

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TranslateService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TranslateService? = null
            private set

        private const val MIN_SCAN_INTERVAL = 260L
        private const val TAG = "ScreenTrans"
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
    private var watchdogJob: Job? = null
    private var listenerRegistered = false
    private var emptyScans = 0

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
        Scanner.TotalScreen.width = resources.displayMetrics.widthPixels
        Scanner.TotalScreen.height = resources.displayMetrics.heightPixels

        if (::overlay.isInitialized) {
            // 系统会重复回调 onServiceConnected。每次新建 OverlayController 都会多挂一个窗口，
            // 旧窗口里的译文没人清理，就会永久留在屏幕上。复用前先清空。
            overlay.clear()
        } else {
            overlay = OverlayController(this, prefs)
            sampler = ScreenSampler(this)
        }
        if (!::coordinator.isInitialized) {
            coordinator = Coordinator(
                context = this,
                prefs = prefs,
                scope = scope,
                onRefined = { item, zh -> mainHandler.post { overlay.update(item.viewKey, zh) } },
                onProgress = { schedule(120) }
            )
        }
        if (!listenerRegistered) {
            prefs.raw.registerOnSharedPreferenceChangeListener(prefListener)
            listenerRegistered = true
        }
        Log.i(TAG, "service connected, enabled=${prefs.enabled}, mode=${prefs.mode}, whitelist=${prefs.whitelist}")
        startWatchdog()
        schedule(300)
    }

    /**
     * 兜底巡检：Discord 切频道时无障碍树会变，但不一定再发事件。
     * 不巡检的话，切页面前画上的频道名译文会一直挂在聊天页上。
     * 内容没变时 show() 的签名一致，不会重绘，所以 1.5 秒一次的代价很小。
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1500)
                if (!prefs.enabled) continue
                val root = rootInActiveWindow ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (pkg in prefs.whitelist) schedule(0)
            }
        }
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
                // 换窗口/换页面：只作废旧截图，不拆覆盖层。
                // 先清屏再截图会让屏幕空白 100~300ms，就是「闪一下」的来源。
                resetShot()
                schedule(180)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> schedule(300)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastScrollAt = SystemClock.uptimeMillis()
                // 滚动时保留旧译文，等新结果一次性替换；清屏会闪
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
        watchdogJob?.cancel()
        listenerRegistered = false
        if (::coordinator.isInitialized) coordinator.close()
        if (::overlay.isInitialized) overlay.clear()
        recycleShot()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun resetShot() {
        // 只回收截图，不动覆盖层
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
            // 切页动画中间会出现瞬时空列表；连续两次为空才真正清屏，
            // 否则每切一次就闪一下。
            emptyScans++
            if (emptyScans >= 2) overlay.clear()
            return
        }
        emptyScans = 0

        // 截图要在覆盖层画上去之前拍，否则会把自己的色块采样进去
        if (shot == null || shotPkg != pkg) {
            shot?.recycle()
            shot = sampler.capture()
            shotPkg = pkg
        }

        val rendered = coordinator.firstPass(items)
        overlay.show(rendered, shot)
        Log.i(TAG, "scan pkg=$pkg captured=${items.size} rendered=${rendered.size}")
    }
}
