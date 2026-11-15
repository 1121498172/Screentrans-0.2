package com.screentrans

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 截一帧屏幕，只用来做两件事：
 *  1. 取原控件的背景色（深色/浅色主题、嵌入卡片、代码块各不相同）；
 *  2. 判断该用什么颜色的字。
 *
 * 关键：截图必须发生在覆盖层绘制之前，否则会把自己的白块采样进去，
 * 形成「白块越画越白」的死循环。
 */
class ScreenSampler(private val service: AccessibilityService) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "screentrans-shot").apply { isDaemon = true }
    }

    private var lastFailAt = 0L

    suspend fun capture(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFailAt != 0L && now - lastFailAt < 3000L) return null

        val bitmap = withTimeoutOrNull(1500) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                try {
                    val displayId = service.display?.displayId ?: Display.DEFAULT_DISPLAY
                    service.takeScreenshot(
                        displayId,
                        executor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                if (!cont.isActive) return
                                cont.resume(readBitmap(result))
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (bitmap == null) {
            lastFailAt = android.os.SystemClock.elapsedRealtime()
            return null
        }
        // 只取色，不需要原图，缩到 1/3 省内存
        return try {
            val small = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width / 3).coerceAtLeast(1),
                (bitmap.height / 3).coerceAtLeast(1),
                true
            )
            if (small != bitmap) bitmap.recycle()
            small
        } catch (t: Throwable) {
            bitmap
        }
    }

    private fun readBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
            wrapped.recycle()
            copy
        } catch (t: Throwable) {
            null
        } finally {
            try {
                buffer.close()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }
}
