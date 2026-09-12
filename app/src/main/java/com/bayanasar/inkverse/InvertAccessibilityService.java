package com.bayanasar.inkverse;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Night inversion driven entirely by the accessibility APIs.
 *
 * MediaProjection is a dead end on this device: an opaque overlay ends up capturing
 * itself (the feedback converges to flat grey), FLAG_SECURE blanks the whole capture
 * to black, and this firmware's capture dialog offers no single-app option.
 *
 * takeScreenshotOfWindow() captures ONE window rather than the display, so the
 * overlay — a different window — cannot appear in its own input. No recursion by
 * construction, and no MediaProjection consent dialog either.
 *
 * The platform rate-limits these screenshots, which suits E-Ink: one capture per
 * content change is all a reader needs.
 */
public class InvertAccessibilityService extends AccessibilityService {

    private static final String TAG = Tags.TAG;
    /** Platform floor is ~333ms; stay above it or requests fail. */
    private static final long MIN_INTERVAL_MS = 400;

    private static volatile InvertAccessibilityService instance;

    private WindowManager wm;
    private OverlayView overlay;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Executor exec = r -> ui.post(r);

    private volatile boolean active = false;
    private volatile String targetPkg = "com.onyx.kreader";
    /** Follow whatever is in front instead of one fixed package. */
    private volatile boolean autoFollow = true;
    /** Package of the window we are currently mirroring. */
    private volatile String shownPkg = null;
    private volatile boolean hidden = false;
    private long lastShotMs = 0;
    private long debounceMs = 250;
    private boolean shotPending = false;

    public static InvertAccessibilityService get() { return instance; }
    public static boolean isRunning() { return instance != null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility connected; screenshot-of-window path available");
    }

    // ---------------------------------------------------------------- control

    public void setTarget(String pkg) {
        targetPkg = pkg;
        autoFollow = (pkg == null || pkg.isEmpty() || "*".equals(pkg));
        Log.i(TAG, "target -> " + (autoFollow ? "AUTO (follow foreground)" : pkg));
    }

    public boolean isActive() { return active; }

    /**
     * Our own UI must stay readable: the overlay is opaque, so while the user is in
     * this app we take it down and put it back when they leave.
     */
    private void setHidden(boolean h) {
        if (hidden == h || overlay == null) return;
        hidden = h;
        overlay.setVisibility(h ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        Log.i(TAG, h ? "overlay hidden (own UI in front)" : "overlay shown");
    }

    public void setDebounce(long ms) { debounceMs = Math.max(ms, MIN_INTERVAL_MS); }

    public void setMode(int m) {
        if (overlay != null) ui.post(() -> overlay.setMode(m));
    }

    public void setShaping(float g, float b, float w) {
        if (overlay != null) ui.post(() -> overlay.setShaping(g, b, w));
    }

    public void probe() {
        ui.post(() -> Log.i(TAG, "PROBE  " + (overlay == null ? "no overlay" : overlay.probe())
                + "  target=" + targetPkg + "  active=" + active));
    }

    public void start() {
        ui.post(() -> {
            if (overlay != null) return;
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.alpha = 1.0f;

            overlay = new OverlayView(this);
            wm.addView(overlay, lp);
            active = true;
            Log.i(TAG, "overlay added (trusted, opaque) " + dm.widthPixels + "x" + dm.heightPixels);
            requestShot();
        });
    }

    public void stop() {
        ui.post(() -> {
            active = false;
            if (overlay != null && wm != null) {
                try { wm.removeViewImmediate(overlay); }
                catch (Exception e) { Log.w(TAG, "removeView: " + e); }
            }
            overlay = null;
            Log.i(TAG, "overlay removed");
        });
    }

    // ---------------------------------------------------------------- capture

    /** Accessibility window id of the newest window belonging to targetPkg. */
    private int findTargetWindowId() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) { Log.w(TAG, "getWindows() null"); return -1; }
        int match = -1, activeFallback = -1;
        boolean selfInFront = false;
        String matchPkg = null;
        StringBuilder sb = new StringBuilder("windows:");
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            CharSequence pkg = null;
            try {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root != null) pkg = root.getPackageName();
            } catch (Exception ignored) { }
            android.graphics.Rect r = new android.graphics.Rect();
            w.getBoundsInScreen(r);
            sb.append("\n  id=").append(w.getId())
              .append(" type=").append(w.getType())
              .append(" active=").append(w.isActive())
              .append(" focus=").append(w.isFocused())
              .append(" pkg=").append(pkg)
              .append(" bounds=").append(r.width()).append("x").append(r.height());
            // Never capture our own overlay window.
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                sb.append("  <SKIP own overlay>");
                continue;
            }
            boolean isSelf = pkg != null && getPackageName().contentEquals(pkg);
            if (isSelf) {
                sb.append("  <SKIP self>");
                if (w.isActive()) selfInFront = true;
                continue;
            }
            if (match < 0) {
                if (autoFollow ? w.isActive() : targetPkg.contentEquals(pkg == null ? "" : pkg)) {
                    match = w.getId();
                    matchPkg = pkg == null ? null : pkg.toString();
                    sb.append("  <TARGET>");
                }
            }
            if (w.isActive() && activeFallback < 0) activeFallback = w.getId();
        }
        setHidden(selfInFront);
        if (selfInFront) return -1;
        int chosen = match >= 0 ? match : activeFallback;
        shownPkg = matchPkg;
        sb.append("\n  chosen=").append(chosen).append(" pkg=").append(matchPkg)
          .append(match >= 0 ? " (target)" : " (active fallback)");
        Log.i(TAG, sb.toString());
        return chosen;
    }

    private void requestShot() {
        if (!active) return;
        long now = SystemClock.uptimeMillis();
        long since = now - lastShotMs;
        if (since < MIN_INTERVAL_MS) {
            if (!shotPending) {
                shotPending = true;
                ui.postDelayed(() -> { shotPending = false; requestShot(); },
                        MIN_INTERVAL_MS - since);
            }
            return;
        }
        lastShotMs = now;

        final int winId = findTargetWindowId();
        if (winId < 0) {
            Log.w(TAG, "no target window for " + targetPkg);
            return;
        }
        try {
            takeScreenshotOfWindow(winId, exec, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer hb = result.getHardwareBuffer();
                    try {
                        Bitmap bmp = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (bmp != null) {
                            Log.i(TAG, "shot ok " + bmp.getWidth() + "x" + bmp.getHeight()
                                    + " cfg=" + bmp.getConfig());
                        }
                        if (bmp != null && overlay != null) {
                            // Copy off the hardware buffer so it can be released, and so
                            // getPixel() works for the probe.
                            Bitmap copy = bmp.copy(Bitmap.Config.ARGB_8888, false);
                            overlay.setFrame(copy);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "wrap failed: " + e);
                    } finally {
                        if (hb != null) hb.close();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.w(TAG, "screenshot failed, code=" + errorCode);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "takeScreenshotOfWindow threw: " + e);
        }
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!active || event == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                ui.removeCallbacks(shotTask);
                ui.postDelayed(shotTask, debounceMs);
                break;
            default:
                break;
        }
    }

    private final Runnable shotTask = this::requestShot;

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stop();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
