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
    private val occupied = ArrayList<Rect>()
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

    fun show(items: List<RenderItem>, shot: Bitmap?) {
        if (items.isEmpty()) {
            clear()
            return
        }
        val signature = items.joinToString("|") {
            it.raw.viewKey + ">" + it.translated
        } + "#" + System.identityHashCode(shot)
        if (attached && signature == lastSignature) return
        lastSignature = signature

        val container = ensureRoot()
        container.removeAllViews()
        views.clear()
        rendered.clear()
        occupied.clear()
        updateBounds()

        val night = isNight()
        for (item in items) {
            addItem(container, item, night, shot)
        }
        if (views.isEmpty()) {
            container.removeAllViews()
            detach()
            return
        }

        if (!attached) {
            val lp = params ?: return
            try {
                wm.addView(container, lp)
                attached = true
            } catch (t: Throwable) {
                attached = false
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
        tv.text = translation
    }

    fun clear() {
        detach()
        root?.removeAllViews()
        views.clear()
        rendered.clear()
        occupied.clear()
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

        // 同一块区域只画一次：父子节点文本不同但矩形重叠时，后画的会盖住先画的
        for (used in occupied) {
            if (used.contains(r)) return
        }
        occupied.add(Rect(r))

        val fallback = AppProfile.styleFor(item.raw.pkg, prefs.theme, night)
        val sampled = sampleBackground(shot, r)
        val surface = sampled ?: fallback.surface
        val textColor = if (sampled != null) readableText(sampled) else fallback.text

        val textSize = item.textSizePx
        val tall = r.height() > textSize * 2.4f

        val tv = TextView(service).apply {
            text = item.translated
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            setTextColor(textColor)
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
     * 在控件矩形内部网格采样，取出现次数最多的颜色当背景。
     * 文字像素是少数，背景是多数，所以这个众数很稳。
     */
    private fun sampleBackground(shot: Bitmap?, rect: Rect): Int? {
        if (shot == null || shot.width == 0 || shot.height == 0) return null
        val sx = shot.width.toFloat() / screenW
        val sy = shot.height.toFloat() / screenH
        val left = (rect.left * sx).toInt().coerceIn(0, shot.width - 1)
        val right = (rect.right * sx).toInt().coerceIn(left + 1, shot.width)
        val top = (rect.top * sy).toInt().coerceIn(0, shot.height - 1)
        val bottom = (rect.bottom * sy).toInt().coerceIn(top + 1, shot.height)

        val stepX = ((right - left) / 12).coerceAtLeast(1)
        val stepY = ((bottom - top) / 8).coerceAtLeast(1)
        val counts = HashMap<Int, Int>(64)

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = shot.getPixel(x, y) and 0x00FFFFFF
                counts[c] = (counts[c] ?: 0) + 1
                x += stepX
            }
            y += stepY
        }
        if (counts.isEmpty()) return null

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

    private fun readableText(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return if (luminance > 150) 0xFF101114.toInt() else 0xFFEDEFF2.toInt()
    }

    private fun isNight(): Boolean {
        val mode = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }
}
