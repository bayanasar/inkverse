package com.bayanasar.inkverse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb-reachable control surface.
 *
 * The overlay is opaque, so when a mode renders badly the on-screen controls are
 * exactly what you cannot see. Everything needed to diagnose is driveable from the
 * host instead:
 *
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 \
 *       --es cmd start --es pkg '*'
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 \
 *       --es cmd mode --ei shader 2
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd probe
 *   adb shell am broadcast -a com.bayanasar.inkverse.DIAG -f 0x01000020 --es cmd stop
 *
 * The 0x01000020 flags are FLAG_INCLUDE_STOPPED_PACKAGES, needed because BOOX
 * auto-freeze leaves sideloaded apps in the stopped state.
 */
class DiagReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "DIAG cmd=$cmd")

        val service = InvertAccessibilityService.get()
        if (service == null) {
            Log.w(TAG, "accessibility service is not running")
            return
        }

        when (cmd) {
            "start" -> {
                intent.getStringExtra("pkg")?.let(service::setTarget)
                service.start()
            }
            "stop" -> service.stop()
            "mode" -> service.setMode(intent.getIntExtra("shader", OverlayView.MODE_LUMA_INVERT))
            "target" -> service.setTarget(intent.getStringExtra("pkg"))
            "levels" -> service.setLevels(
                intent.getFloatExtra("black", 0f),
                intent.getFloatExtra("white", 1f),
            )
            "probe" -> service.probe()
            else -> Log.w(TAG, "unknown diag cmd: $cmd")
        }
    }
}
