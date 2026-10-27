package com.monib.receivercontrol.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ReceiverAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: ReceiverAccessibilityService? = null

        fun isEnabled(): Boolean = active != null

        fun perform(action: String): Boolean {
            val service = active ?: return false
            val globalAction = when (action) {
                "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
                "LOCK" -> if (android.os.Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return false
                "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return false
            }
            return service.performGlobalAction(globalAction)
        }

        fun dispatchTouch(action: String): Boolean {
            val service = active ?: return false
            val metrics = service.resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val centerY = metrics.heightPixels / 2f
            val distance = (min(metrics.widthPixels, metrics.heightPixels) * 0.075f).coerceAtLeast(48f)
            val (dx, dy) = when (action) {
                "UP" -> 0f to -distance
                "DOWN" -> 0f to distance
                "LEFT" -> -distance to 0f
                "RIGHT" -> distance to 0f
                "ENTER" -> 1f to 1f
                else -> return false
            }
            val path = Path().apply {
                moveTo(centerX, centerY)
                lineTo(centerX + dx, centerY + dy)
            }
            val duration = if (action == "ENTER") 80L else 180L
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            val accepted = AtomicBoolean(false)
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    accepted.set(service.dispatchGesture(gesture, null, null))
                } catch (_: Exception) {
                    accepted.set(false)
                } finally {
                    latch.countDown()
                }
            }
            return runCatching { latch.await(1200L, TimeUnit.MILLISECONDS) && accepted.get() }.getOrDefault(false)
        }

        fun captureScreenshot(onResult: (ByteArray?) -> Unit): Boolean {
            val service = active ?: return false
            if (Build.VERSION.SDK_INT < 30) return false
            Handler(Looper.getMainLooper()).post {
                try {
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        Executor { callback -> Thread(callback, "receiver-screen-capture").start() },
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val buffer = result.hardwareBuffer
                                try {
                                    val source = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: run {
                                        onResult(null)
                                        return
                                    }
                                    val scale = minOf(1f, 1280f / source.width, 720f / source.height)
                                    val width = (source.width * scale).toInt().coerceAtLeast(1)
                                    val height = (source.height * scale).toInt().coerceAtLeast(1)
                                    val scaled = Bitmap.createScaledBitmap(source, width, height, true)
                                    val output = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 72, output)
                                    if (scaled !== source) scaled.recycle()
                                    source.recycle()
                                    onResult(output.toByteArray())
                                } catch (_: Exception) {
                                    onResult(null)
                                } finally {
                                    buffer.close()
                                }
                            }

                            override fun onFailure(errorCode: Int) = onResult(null)
                        }
                    )
                } catch (_: Exception) {
                    onResult(null)
                }
            }
            return true
        }

        fun openSettings(context: android.content.Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
