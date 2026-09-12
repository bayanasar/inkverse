package com.bayanasar.inkverse

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executor

const val TAG = "Inkverse"

/**
 * Night inversion built on the accessibility APIs.
 *
 * MediaProjection cannot do this here. An opaque fullscreen overlay is part of the
 * display it captures, so the loop feeds on its own output and converges to flat
 * grey; FLAG_SECURE breaks the loop but blanks the capture to black; and this
 * firmware's capture dialog offers no single-app option.
 *
 * takeScreenshotOfWindow() captures one window rather than the display, so the
 * overlay cannot appear in its own input. No recursion by construction, and no
 * consent dialog either.
 *
 * The window also has to be owned by an accessibility service for a second reason:
 * TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE has its alpha clamped to 0.80 as
 * tapjacking protection, which blends the inverted image back into the original
 * underneath. A trusted window is exempt, so it can be opaque and still let the pen
 * through.
 */
class InvertAccessibilityService : AccessibilityService() {

    companion object {
        /** Platform floor is ~333ms between screenshots; stay above it. */
        private const val MIN_INTERVAL_MS = 400L

        @Volatile
        private var instance: InvertAccessibilityService? = null

        fun get(): InvertAccessibilityService? = instance
        val isRunning: Boolean get() = instance != null
    }

    private val ui = Handler(Looper.getMainLooper())
    private val executor = Executor { ui.post(it) }

    private var windowManager: WindowManager? = null
    private var overlay: OverlayView? = null

    @Volatile var isActive = false; private set
    /** Package to mirror, or null to follow whatever is in front. */
    @Volatile private var targetPackage: String? = null
    private var hidden = false
    private var lastShotMs = 0L
    private var shotPending = false
    private var debounceMs = 250L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected; screenshot-of-window path available")
    }

    // ------------------------------------------------------------------ control

    fun setTarget(pkg: String?) {
        targetPackage = pkg?.takeUnless { it.isEmpty() || it == "*" }
        Log.i(TAG, "target -> ${targetPackage ?: "AUTO (follow foreground)"}")
    }

    fun setMode(mode: Int) = ui.post { overlay?.mode = mode }

    fun setLevels(black: Float, white: Float) = ui.post { overlay?.setLevels(black, white) }

    fun probe() = ui.post {
        Log.i(TAG, "PROBE ${overlay?.probe() ?: "no overlay"} active=$isActive")
    }

    fun start() = ui.post {
        if (overlay != null) return@post
        val wm = getSystemService(WindowManager::class.java).also { windowManager = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1.0f
        }

        overlay = OverlayView(this).also { wm.addView(it, params) }
        isActive = true
        Log.i(TAG, "overlay added (trusted, opaque)")
        requestShot()
    }

    fun stop() = ui.post {
        isActive = false
        overlay?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removeView: $it") }
        }
        overlay = null
        hidden = false
        Log.i(TAG, "overlay removed")
    }

    /** Our own UI is under an opaque overlay, so take it down while the user is in it. */
    private fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        overlay?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        Log.i(TAG, if (hide) "overlay hidden (own UI in front)" else "overlay shown")
    }

    // ------------------------------------------------------------------ capture

    /** Accessibility window id to mirror, or -1 when there is nothing to show. */
    private fun findTargetWindowId(): Int {
        val windows = windows ?: run { Log.w(TAG, "getWindows() null"); return -1 }
        var match = -1
        var activeFallback = -1
        var selfInFront = false

        for (window in windows) {
            val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull()

            if (pkg == packageName) {
                if (window.isActive) selfInFront = true
                continue
            }
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue

            val wanted = targetPackage?.let { it == pkg } ?: window.isActive
            if (wanted && match < 0) match = window.id
            if (window.isActive && activeFallback < 0) activeFallback = window.id
        }

        setHidden(selfInFront)
        if (selfInFront) return -1
        return if (match >= 0) match else activeFallback
    }

    private fun requestShot() {
        if (!isActive) return

        val now = SystemClock.uptimeMillis()
        val since = now - lastShotMs
        if (since < MIN_INTERVAL_MS) {
            if (!shotPending) {
                shotPending = true
                ui.postDelayed({ shotPending = false; requestShot() }, MIN_INTERVAL_MS - since)
            }
            return
        }
        lastShotMs = now

        val windowId = findTargetWindowId()
        if (windowId < 0) return

        runCatching {
            takeScreenshotOfWindow(windowId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        // Copy off the hardware buffer so it can be released, and so
                        // probe()'s getPixel() works.
                        wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            ?.let { overlay?.setFrame(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "wrap failed: $e")
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed, code=$errorCode")
                }
            })
        }.onFailure { Log.e(TAG, "takeScreenshotOfWindow threw: $it") }
    }

    // ------------------------------------------------------------------- events

    private val shotTask = Runnable { requestShot() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isActive || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                ui.removeCallbacks(shotTask)
                ui.postDelayed(shotTask, debounceMs)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stop()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
