package com.screentrans

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * 用 TYPE_ACCESSIBILITY_OVERLAY 在原控件位置画中文。
 *
 * 排版的三条规矩：
 *  1. 背景色从截图里取，不再瞎猜深色/浅色 —— 这是「白纸条」问题的根因；
 *  2. 只覆盖文本节点的矩形，且跳过完全被别的块包含的节点，避免叠罗汉；
 *  3. 字号按矩形实测，宁可缩字也不溢出。
 */
class OverlayController(
    private val service: AccessibilityService,
    private val prefs: Prefs
) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val scaledDensity = service.resources.displayMetrics.scaledDensity
    private var screenW = 1080
    private var screenH = 2400

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private val views = HashMap<String, TextView>()
    private val rendered = HashMap<String, RenderItem>()
    private var lastSignature: String? = null

    init {
        updateBounds()
    }

    private fun updateBounds() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                screenW = b.width()
                screenH = b.height()
            } else {
                val dm = service.resources.displayMetrics
                screenW = dm.widthPixels
                screenH = dm.heightPixels
            }
        } catch (t: Throwable) {
            val dm = service.resources.displayMetrics
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    fun hasContent(): Boolean = views.isNotEmpty()

    /**
     * 不能只信 attached 标志：HyperOS 会在切前后台/全屏时把无障碍悬浮窗收走，
     * 而标志位还是 true，之后所有渲染都塞进一个脱离屏幕的容器里 —— 症状就是
     * 「诊断说画出了，屏幕上却没有」。每次都实测一下。
     */
    private fun isReallyAttached(): Boolean {
        val container = root ?: return false
        return attached && container.isAttachedToWindow
    }

    fun show(items: List<RenderItem>, shot: Bitmap?) {
        // 空列表不再清屏：切页动画中间会出现瞬时空列表，清掉就会「闪一下」。
        // 真正的清屏由 Service 在连续多次为空后决定。
        if (items.isEmpty()) return
        updateBounds()

        val drawable = items.filter { item ->
            val r = item.raw.rect
            r.bottom > 0 && r.top < screenH && r.right > 0 && r.left < screenW &&
                r.width() >= 2 * density && r.height() >= 2 * density
        }
        if (drawable.isEmpty()) return

        val signature = drawable.joinToString("|") {
            it.raw.viewKey + ">" + it.translated + ":" + it.provisional
        } + "#" + System.identityHashCode(shot)

        if (!isReallyAttached()) attached = false
        if (attached && signature == lastSignature) return
        lastSignature = signature

        val container = ensureRoot()
        // removeAllViews 与 addView 在同一帧内完成，不会闪；只要不 detach 窗口
        container.removeAllViews()
        views.clear()
        rendered.clear()

        val night = isNight()
        for (item in drawable) {
            addItem(container, item, night, shot)
        }
        if (views.isEmpty()) return

        if (!attached) {
            val lp = params ?: return
            try {
                if (container.parent != null) {
                    wm.removeView(container)
                }
                wm.addView(container, lp)
                attached = true
                android.util.Log.i("ScreenTrans", "overlay attached, views=${views.size}")
            } catch (t: Throwable) {
                attached = false
                android.util.Log.e("ScreenTrans", "overlay attach failed: ${t.message}")
            }
        }
    }

    fun update(viewKey: String, translation: String) {
        val old = rendered[viewKey] ?: return
        val tv = views[viewKey] ?: return
        val size = TextMeasure.sizeFor(
            translated = translation,
            widthPx = old.raw.rect.width(),
            heightPx = old.raw.rect.height(),
            scaledDensity = scaledDensity,
            paddingPx = density,
            maxSpScale = prefs.fontScale
        )
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        tv.setTextColor(ColorUtils.setAlphaComponent(tv.currentTextColor, 255))
        tv.text = translation
    }

    fun clear() {
        detach()
        root?.removeAllViews()
        views.clear()
        rendered.clear()
        lastSignature = null
    }

    private fun detach() {
        if (attached) {
            try {
                root?.let { wm.removeView(it) }
            } catch (t: Throwable) {
                // ignore
            }
            attached = false
        }
    }

    private fun ensureRoot(): FrameLayout {
        root?.let { return it }
        val container = FrameLayout(service).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }
        }
        root = container
        params = p
        return container
    }

    private fun addItem(container: FrameLayout, item: RenderItem, night: Boolean, shot: Bitmap?) {
        val r = item.raw.rect
        if (r.bottom <= 0 || r.top >= screenH || r.right <= 0 || r.left >= screenW) return
        if (r.width() < 2 * density || r.height() < 2 * density) return

        val fallback = AppProfile.styleFor(item.raw.pkg, prefs.theme, night)
        val sampled = sampleBackground(shot, r)
        val surface = sampled ?: fallback.surface
        val textColor = if (sampled != null) readableText(sampled) else fallback.text

        val textSize = item.textSizePx
        val tall = r.height() > textSize * 2.4f

        val tv = TextView(service).apply {
            text = item.translated
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            // 机翻占位画淡一点，LLM 回来后 update() 会调回不透明
            setTextColor(
                if (item.provisional) ColorUtils.setAlphaComponent(textColor, 165) else textColor
            )
            gravity = when {
                item.centered -> Gravity.CENTER
                tall -> Gravity.START or Gravity.TOP
                else -> Gravity.START or Gravity.CENTER_VERTICAL
            }
            setPadding(density.toInt(), 0, density.toInt(), 0)
            maxLines = maxOf(1, (r.height() / (textSize * 1.2f)).toInt())
            ellipsize = TextUtils.TruncateAt.END
            setIncludeFontPadding(false)
            background = GradientDrawable().apply {
                cornerRadius = 3f * density
                setColor(ColorUtils.setAlphaComponent(surface, prefs.backgroundAlpha))
            }
        }

        val lp = FrameLayout.LayoutParams(r.width(), r.height()).apply {
            leftMargin = r.left
            topMargin = r.top + (prefs.offsetY * density).toInt()
        }
        container.addView(tv, lp)
        views[item.raw.viewKey] = tv
        rendered[item.raw.viewKey] = item
    }

    /**
     * 取背景色，优先级：
     *  1. 矩形**外侧**一圈 —— 文本块外面通常就是页面底色；
     *  2. 矩形内部网格 —— 万一外侧取不到（贴边、被裁）。
     *
     * 只取内部会有个致命问题：消息行里常有头像、徽章、图片缩略图，
     * 白色像素一旦成为众数，整块就变成白纸条。
     */
    private fun sampleBackground(shot: Bitmap?, rect: Rect): Int? {
        if (shot == null || shot.width == 0 || shot.height == 0) return null
        val sx = shot.width.toFloat() / screenW
        val sy = shot.height.toFloat() / screenH
        val inner = Rect(
            (rect.left * sx).toInt().coerceIn(0, shot.width - 1),
            (rect.top * sy).toInt().coerceIn(0, shot.height - 1),
            (rect.right * sx).toInt().coerceIn(1, shot.width),
            (rect.bottom * sy).toInt().coerceIn(1, shot.height)
        )
        if (inner.right <= inner.left || inner.bottom <= inner.top) return null

        val gap = ((4 * density) * ((sx + sy) / 2f)).toInt().coerceAtLeast(2)
        ringColor(shot, inner, gap)?.let { return it }
        return areaColor(shot, inner)
    }

    /** 沿矩形外扩一圈采样 */
    private fun ringColor(shot: Bitmap, r: Rect, distance: Int): Int? {
        val counts = HashMap<Int, Int>(64)
        var samples = 0
        fun put(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= shot.width || y >= shot.height) return
            val c = shot.getPixel(x, y) and 0x00FFFFFF
            counts[c] = (counts[c] ?: 0) + 1
            samples++
        }

        val left = r.left - distance
        val right = r.right + distance
        val top = r.top - distance
        val bottom = r.bottom + distance
        val stepX = ((right - left) / 18).coerceAtLeast(1)
        val stepY = ((bottom - top) / 12).coerceAtLeast(1)

        var x = left
        while (x <= right) {
            put(x, top)
            put(x, bottom)
            x += stepX
        }
        var y = top
        while (y <= bottom) {
            put(left, y)
            put(right, y)
            y += stepY
        }
        if (samples < 12) return null
        return modeColor(counts)
    }

    /** 矩形内部网格采样（保底） */
    private fun areaColor(shot: Bitmap, r: Rect): Int? {
        val counts = HashMap<Int, Int>(64)
        val stepX = ((r.right - r.left) / 12).coerceAtLeast(1)
        val stepY = ((r.bottom - r.top) / 8).coerceAtLeast(1)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                val c = shot.getPixel(x, y) and 0x00FFFFFF
                counts[c] = (counts[c] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        if (counts.isEmpty()) return null
        return modeColor(counts)
    }

    private fun modeColor(counts: Map<Int, Int>): Int {
        var best = 0
        var bestCount = -1
        for ((color, count) in counts) {
            if (count > bestCount) {
                best = color
                bestCount = count
            }
        }
        return best or 0xFF000000.toInt()
    }

    /**
     * 按 WCAG 对比度在纯黑/纯白里挑更清楚的一个，
     * 不用亮度阈值一刀切 —— 浅灰底上原来的实现会给出同样浅的字。
     */
    private fun readableText(bg: Int): Int {
        val light = 0xFFF2F3F5.toInt()
        val dark = 0xFF101114.toInt()
        return if (contrastRatio(bg, dark) >= contrastRatio(bg, light)) dark else light
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)
    }

    private fun isNight(): Boolean {
        val mode = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
