package com.bayanasar.inkverse

import android.content.Context
import android.content.SharedPreferences

/**
 * The one store both the activity and the service read.
 *
 * The service needs its own access because it outlives the activity: BOOX
 * auto-freeze and ordinary low-memory kills both take the process down, and on
 * the way back up onServiceConnected() is the only thing that runs. Without the
 * settings here it would reconnect with no idea that it had been inverting.
 */
internal object Prefs {

    const val FILE = "inkverse"

    const val THEME = "theme"         // 0 system, 1 light, 2 dark
    const val MODE = "mode"
    const val BLACK = "black"         // seek bar progress, 0..255
    const val WHITE = "white"         // seek bar progress, 0..255
    const val AUTOCHECK = "autocheck"
    const val ACTIVE = "active"       // was the overlay up when we last had a say?

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Bar progress to matrix black point: the lower half of the range. */
    fun blackPoint(progress: Int): Float = progress / 255f * 0.5f

    /** Bar progress to matrix white point: the upper half. */
    fun whitePoint(progress: Int): Float = 0.5f + progress / 255f * 0.5f
}
