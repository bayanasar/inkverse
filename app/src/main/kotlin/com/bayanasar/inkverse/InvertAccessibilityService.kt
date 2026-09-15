package com.bayanasar.inkverse

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
 *
 * Capturing per window means the screen has to be reassembled per window too. Every
 * visible window is captured and drawn at its own bounds, back to front, because
 * touch still goes to the real thing underneath: a dialog painted anywhere other
 * than where it actually is, is a dialog nobody can hit.
 */
class InvertAccessibilityService : AccessibilityService() {

    companion object {
        /** Platform floor is ~333ms between screenshots; stay above it. */
        private const val MIN_INTERVAL_MS = 400L

        /** Plenty for an app, its dialog, the bars and an IME. */
        private const val MAX_LAYERS = 6

        @Volatile
        private var instance: InvertAccessibilityService? = null

        fun get(): InvertAccessibilityService? = instance
        val isRunning: Boolean get() = instance != null
    }

    /** One window to mirror: what to capture, and where on screen it really sits. */
    private class Target(val id: Int, val bounds: Rect)

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

    /** Last good image per window id, so a refused capture does not blank a layer. */
    private val cache = HashMap<Int, Bitmap>()
    private var batch = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected; screenshot-of-window path available")

        // Coming back from a kill — BOOX auto-freeze, or plain memory pressure —
        // this callback is the only thing that runs. Without restoring here the
        // user has to open the app and press START again every single time.
        val prefs = Prefs.of(this)
        if (prefs.getBoolean(Prefs.ACTIVE, false)) {
            Log.i(TAG, "previous session was active; restoring overlay")
            setTarget(null)
            start()
            setMode(prefs.getInt(Prefs.MODE, OverlayView.MODE_LUMA_INVERT))
            setLevels(
                Prefs.blackPoint(prefs.getInt(Prefs.BLACK, 0)),
                Prefs.whitePoint(prefs.getInt(Prefs.WHITE, 255)),
            )
        }
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
        remember(true)
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
        remember(false)
        teardown()
    }

    /**
     * Take the overlay down without touching the stored intent.
     *
     * Unbinding is not the user saying stop — it is usually the user being frozen
     * out — so the flag that drives the restore above has to survive it.
     */
    private fun teardown() {
        isActive = false
        overlay?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removeView: $it") }
        }
        overlay = null
        hidden = false
        cache.values.forEach { it.recycle() }
        cache.clear()
        Log.i(TAG, "overlay removed")
    }

    private fun remember(active: Boolean) =
        Prefs.of(this).edit().putBoolean(Prefs.ACTIVE, active).apply()

    /** Our own UI is under an opaque overlay, so take it down while the user is in it. */
    private fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        overlay?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
        Log.i(TAG, if (hide) "overlay hidden (own UI in front)" else "overlay shown")
    }

    // ------------------------------------------------------------------ capture

    /**
     * Every window worth mirroring, back to front.
     *
     * Ordered by layer rather than picked by "is active" because a dialog does not
     * replace the app behind it, it sits on top of it — and each one has to be
     * drawn where it really is.
     */
    private fun collectTargets(): List<Target> {
        val windows = windows ?: run { Log.w(TAG, "getWindows() null"); return emptyList() }
        val wanted = ArrayList<AccessibilityWindowInfo>(windows.size)
        var selfInFront = false

        for (window in windows) {
            val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull()

            if (pkg == packageName) {
                if (window.isActive) selfInFront = true
                continue
            }
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue

            // A pinned target narrows the app windows only. The bars and the IME are
            // part of the screen being looked at whoever owns them.
            val pinned = targetPackage
            if (pinned != null && pkg != null && pkg != pinned &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION
            ) continue

            wanted += window
        }

        setHidden(selfInFront)
        if (selfInFront) return emptyList()

        wanted.sortBy { it.layer }
        val out = ArrayList<Target>(wanted.size)
        for (window in wanted) {
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            if (bounds.isEmpty) continue
            out += Target(window.id, bounds)
        }
        return if (out.size <= MAX_LAYERS) out else out.subList(out.size - MAX_LAYERS, out.size)
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

        val targets = collectTargets()
        if (targets.isEmpty()) return
        capture(targets)
    }

    /** Fire one screenshot per target and publish the set once they have all landed. */
    private fun capture(targets: List<Target>) {
        val id = ++batch
        val shots = arrayOfNulls<Bitmap>(targets.size)
        var outstanding = targets.size

        fun settle(index: Int, bitmap: Bitmap?) {
            if (id != batch) { bitmap?.recycle(); return }   // a newer batch overtook us
            shots[index] = bitmap
            if (--outstanding == 0) publish(targets, shots)
        }

        targets.forEachIndexed { index, target ->
            runCatching {
                takeScreenshotOfWindow(target.id, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val copy = try {
                            // Copy off the hardware buffer so it can be released, and
                            // so probe()'s getPixel() works.
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            Log.e(TAG, "wrap failed: $e")
                            null
                        } finally {
                            buffer.close()
                        }
                        settle(index, copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot of window ${target.id} failed, code=$errorCode")
                        settle(index, null)
                    }
                })
            }.onFailure {
                Log.e(TAG, "takeScreenshotOfWindow threw: $it")
                settle(index, null)
            }
        }
    }

    private fun publish(targets: List<Target>, shots: Array<Bitmap?>) {
        val layers = ArrayList<OverlayView.Layer>(targets.size)
        val fresh = HashMap<Int, Bitmap>(targets.size)

        targets.forEachIndexed { index, target ->
            // A window the platform refused this round — usually the screenshot
            // interval — keeps its last image rather than punching a black hole
            // through the composite.
            val bitmap = shots[index] ?: cache[target.id] ?: return@forEachIndexed
            fresh[target.id] = bitmap
            layers += OverlayView.Layer(bitmap, target.bounds)
        }

        // Safe here and nowhere else: publishing and drawing are both on this thread,
        // so nothing that survives into the new set is in flight.
        for ((windowId, bitmap) in cache) {
            if (fresh[windowId] !== bitmap) bitmap.recycle()
        }
        cache.clear()
        cache.putAll(fresh)

        overlay?.setLayers(layers)
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
        teardown()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
