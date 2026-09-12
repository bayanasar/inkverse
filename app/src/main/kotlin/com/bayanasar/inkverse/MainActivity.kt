package com.bayanasar.inkverse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private companion object {
        const val PREFS = "inkverse"
        const val KEY_THEME = "theme"     // 0 system, 1 light, 2 dark
        const val KEY_MODE = "mode"
        const val KEY_BLACK = "black"
        const val KEY_WHITE = "white"
        const val KEY_AUTOCHECK = "autocheck"
        const val THEME_ID_BASE = 1000
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var modes: RadioGroup
    private lateinit var blackBar: SeekBar
    private lateinit var whiteBar: SeekBar
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var pending: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        applyTheme(prefs.getInt(KEY_THEME, 0))
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
        })

        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)

        toggle = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                val service = InvertAccessibilityService.get()
                if (service == null) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    if (service.isActive) service.stop() else { push(service); service.start() }
                    refresh()
                }
            }
        }
        root.addView(toggle)

        root.addView(TextView(this).apply {
            text = "\nThe overlay is opaque, so it hides itself while you are in this app " +
                "and comes back when you leave. Pen and touch always pass through to the " +
                "app underneath.\n"
        })

        root.addView(label("Mode"))
        modes = RadioGroup(this).apply {
            addOption(this, "Off / passthrough", OverlayView.MODE_PASSTHROUGH)
            addOption(this, "Grayscale invert  (best for E-Ink)", OverlayView.MODE_LUMA_INVERT)
            addOption(this, "RGB invert  (photos go negative)", OverlayView.MODE_RGB_INVERT)
            addOption(this, "Grayscale invert + levels", OverlayView.MODE_SHAPED)
            check(prefs.getInt(KEY_MODE, OverlayView.MODE_LUMA_INVERT))
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putInt(KEY_MODE, id).apply()
                InvertAccessibilityService.get()?.setMode(id)
            }
        }
        root.addView(modes)

        root.addView(label("\nLevels  (used by the \"+ levels\" mode)"))
        blackBar = slider(root, "black point", prefs.getInt(KEY_BLACK, 0))
        whiteBar = slider(root, "white point", prefs.getInt(KEY_WHITE, 255))

        root.addView(label("\nAppearance"))
        root.addView(RadioGroup(this).apply {
            addOption(this, "Follow system", THEME_ID_BASE + 0)
            addOption(this, "Light", THEME_ID_BASE + 1)
            addOption(this, "Dark", THEME_ID_BASE + 2)
            check(THEME_ID_BASE + prefs.getInt(KEY_THEME, 0))
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putInt(KEY_THEME, id - THEME_ID_BASE).apply()
                recreate()
            }
        })

        root.addView(Button(this).apply {
            text = "Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(label("\nUpdates"))
        updateStatus = TextView(this).apply {
            text = "Version ${packageManager.getPackageInfo(packageName, 0).versionName}"
        }
        root.addView(updateStatus)
        updateButton = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { onUpdateClicked() }
        }
        root.addView(updateButton)
        root.addView(android.widget.CheckBox(this).apply {
            text = "Check automatically on launch"
            isChecked = prefs.getBoolean(KEY_AUTOCHECK, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(KEY_AUTOCHECK, on).apply()
            }
        })

        if (prefs.getBoolean(KEY_AUTOCHECK, true)) checkForUpdate(silent = true)

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        })
        refresh()
    }

    private fun onUpdateClicked() {
        val release = pending
        if (release == null) { checkForUpdate(silent = false); return }
        updateButton.isEnabled = false
        updateStatus.text = "Downloading ${release.version}…"
        Updater.download(
            this, release,
            onProgress = { pct -> updateStatus.text = "Downloading ${release.version}… $pct%" },
            onDone = { ok, message ->
                updateStatus.text = message
                updateButton.isEnabled = true
                if (!ok) updateButton.text = "Retry update"
            },
        )
    }

    private fun checkForUpdate(silent: Boolean) {
        if (!silent) updateStatus.text = "Checking…"
        Updater.check(
            this,
            onResult = { release ->
                pending = release
                if (release != null) {
                    updateStatus.text = "Version ${release.version} is available."
                    updateButton.text = "Download and install ${release.version}"
                } else if (!silent) {
                    updateStatus.text =
                        "Up to date (${packageManager.getPackageInfo(packageName, 0).versionName})."
                }
            },
            onError = { message -> if (!silent) updateStatus.text = "Check failed: $message" },
        )
    }

    /**
     * The reading screen is white when nothing is inverted, so the app follows the
     * system rather than forcing dark.
     */
    private fun applyTheme(choice: Int) = setTheme(
        when (choice) {
            1 -> R.style.Theme_Inkverse_Light
            2 -> R.style.Theme_Inkverse_Dark
            else -> {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) R.style.Theme_Inkverse_Dark
                else R.style.Theme_Inkverse_Light
            }
        }
    )

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
    }

    private fun addOption(group: RadioGroup, label: String, id: Int) {
        group.addView(RadioButton(this).apply { text = label; this.id = id })
    }

    private fun slider(root: LinearLayout, label: String, initial: Int): SeekBar {
        root.addView(TextView(this).apply { text = label })
        return SeekBar(this).apply {
            max = 255
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) InvertAccessibilityService.get()?.let(::push)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            root.addView(this)
        }
    }

    private fun push(service: InvertAccessibilityService) {
        prefs.edit()
            .putInt(KEY_BLACK, blackBar.progress)
            .putInt(KEY_WHITE, whiteBar.progress)
            .apply()
        service.setTarget(null)                       // follow whatever is in front
        service.setMode(modes.checkedRadioButtonId)
        service.setLevels(
            blackBar.progress / 255f * 0.5f,
            0.5f + whiteBar.progress / 255f * 0.5f,
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val service = InvertAccessibilityService.get()
        when {
            service == null -> {
                status.setTextColor(Color.rgb(200, 90, 60))
                status.text = "Accessibility service is off.\nEnable \"Inkverse\" in " +
                    "Accessibility settings — that is what allows a fully opaque overlay " +
                    "that still lets touch through."
                toggle.text = "Open accessibility settings"
            }
            service.isActive -> {
                status.setTextColor(Color.rgb(60, 130, 100))
                status.text = "Running. Leave this app and the inverted view appears."
                toggle.text = "STOP"
            }
            else -> {
                status.setTextColor(Color.rgb(90, 110, 100))
                status.text = "Ready."
                toggle.text = "START"
            }
        }
    }
}
