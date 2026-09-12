package com.bayanasar.inkverse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * adb-reachable control surface.
 *
 * The overlay covers the screen, so when a mode renders badly the on-screen buttons
 * are exactly what you cannot see. Everything needed to diagnose is therefore also
 * driveable from the host:
 *
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG --es cmd mode --ei shader 0
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG --es cmd probe
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG --es cmd stop
 */
public class DiagReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.bayanasar.inkverse.DIAG";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        Log.i(Tags.TAG, "DIAG cmd=" + cmd);
        if (cmd == null) return;

        switch (cmd) {
            case "start": {
                InvertAccessibilityService a = InvertAccessibilityService.get();
                if (a == null) { Log.w(Tags.TAG, "accessibility not running"); break; }
                String pkg = intent.getStringExtra("pkg");
                if (pkg != null) a.setTarget(pkg);
                a.start();
                break;
            }
            case "mode": {
                int shader = intent.getIntExtra("shader", 2);
                InvertAccessibilityService a = InvertAccessibilityService.get();
                if (a != null) a.setMode(shader);
                break;
            }
            case "target": {
                InvertAccessibilityService a = InvertAccessibilityService.get();
                if (a != null) a.setTarget(intent.getStringExtra("pkg"));
                break;
            }
            case "shape": {
                InvertAccessibilityService a2 = InvertAccessibilityService.get();
                if (a2 != null) {
                    a2.setShaping(intent.getFloatExtra("gamma", 1f),
                                  intent.getFloatExtra("black", 0f),
                                  intent.getFloatExtra("white", 1f));
                    break;
                }
                break;
            }

            case "probe": {
                InvertAccessibilityService a3 = InvertAccessibilityService.get();
                if (a3 != null) a3.probe();
                break;
            }
            case "stop": {
                InvertAccessibilityService a4 = InvertAccessibilityService.get();
                if (a4 != null) a4.stop();
                break;
            }
            default:
                Log.w(Tags.TAG, "unknown diag cmd: " + cmd);
        }
    }
}
