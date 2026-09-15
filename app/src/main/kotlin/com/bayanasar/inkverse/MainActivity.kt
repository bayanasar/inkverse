package com.bayanasar.inkverse

import android.app.Activity
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
        prefs = Prefs.of(this)
        applyTheme(prefs.getInt(Prefs.THEME, 0))
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
            text = "\n" + getString(R.string.hint_overlay) + "\n"
        })

        root.addView(label(getString(R.string.section_mode)))
        modes = RadioGroup(this).apply {
            addOption(this, getString(R.string.mode_passthrough), OverlayView.MODE_PASSTHROUGH)
            addOption(this, getString(R.string.mode_luma), OverlayView.MODE_LUMA_INVERT)
            addOption(this, getString(R.string.mode_rgb), OverlayView.MODE_RGB_INVERT)
            addOption(this, getString(R.string.mode_shaped), OverlayView.MODE_SHAPED)
            check(prefs.getInt(Prefs.MODE, OverlayView.MODE_LUMA_INVERT))
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putInt(Prefs.MODE, id).apply()
                InvertAccessibilityService.get()?.setMode(id)
            }
        }
        root.addView(modes)

        root.addView(label("\n" + getString(R.string.section_levels)))
        blackBar = slider(root, getString(R.string.level_black), prefs.getInt(Prefs.BLACK, 0))
        whiteBar = slider(root, getString(R.string.level_white), prefs.getInt(Prefs.WHITE, 255))

        root.addView(label("\n" + getString(R.string.section_appearance)))
        root.addView(RadioGroup(this).apply {
            addOption(this, getString(R.string.theme_system), THEME_ID_BASE + 0)
            addOption(this, getString(R.string.theme_light), THEME_ID_BASE + 1)
            addOption(this, getString(R.string.theme_dark), THEME_ID_BASE + 2)
            check(THEME_ID_BASE + prefs.getInt(Prefs.THEME, 0))
            setOnCheckedChangeListener { _, id ->
                prefs.edit().putInt(Prefs.THEME, id - THEME_ID_BASE).apply()
                recreate()
            }
        })

        root.addView(label("\n" + getString(R.string.section_boox)))
        root.addView(TextView(this).apply { text = getString(R.string.hint_boox) })

        root.addView(Button(this).apply {
            text = getString(R.string.btn_accessibility)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(label("\n" + getString(R.string.section_updates)))
        updateStatus = TextView(this).apply { text = getString(R.string.status_version, version()) }
        root.addView(updateStatus)
        updateButton = Button(this).apply {
            text = getString(R.string.btn_check)
            setOnClickListener { onUpdateClicked() }
        }
        root.addView(updateButton)
        root.addView(android.widget.CheckBox(this).apply {
            text = getString(R.string.chk_autocheck)
            isChecked = prefs.getBoolean(Prefs.AUTOCHECK, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(Prefs.AUTOCHECK, on).apply()
            }
        })

        if (prefs.getBoolean(Prefs.AUTOCHECK, true)) checkForUpdate(silent = true)

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
        updateStatus.text = getString(R.string.status_downloading, release.version, 0)
        Updater.download(
            this, release,
            onProgress = { pct -> updateStatus.text = getString(R.string.status_downloading, release.version, pct) },
            onDone = { ok, message ->
                updateStatus.text = message
                updateButton.isEnabled = true
                if (!ok) updateButton.text = getString(R.string.btn_retry_update)
            },
        )
    }

    /**
     * E-Ink does not reliably repaint a one-line text change, so the result also has
     * to move the button — a big filled element the panel will redraw. A silent
     * check still reports its outcome; an update that quietly did nothing is
     * indistinguishable from a broken button.
     */
    private fun checkForUpdate(silent: Boolean) {
        updateButton.isEnabled = false
        updateButton.text = getString(R.string.btn_checking)
        updateStatus.text = getString(R.string.status_contacting)

        Updater.check(
            this,
            onResult = { release ->
                pending = release
                updateButton.isEnabled = true
                val now = timestamp()
                if (release != null) {
                    updateStatus.text = getString(R.string.status_available, release.version, now)
                    updateButton.text = getString(R.string.btn_install, release.version)
                } else {
                    updateStatus.text = getString(R.string.status_uptodate, version(), now)
                    updateButton.text = getString(R.string.btn_check)
                }
            },
            onError = { message ->
                updateButton.isEnabled = true
                updateButton.text = getString(R.string.btn_retry_check)
                val hint = if (message.contains("resolve host", true) ||
                    message.contains("Unable", true)
                ) getString(R.string.status_wifi_hint) else ""
                updateStatus.text = getString(R.string.status_check_failed, message, hint)
            },
        )
    }

    private fun version(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun timestamp(): String = android.text.format.DateFormat
        .getTimeFormat(this).format(java.util.Date())

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
            .putInt(Prefs.BLACK, blackBar.progress)
            .putInt(Prefs.WHITE, whiteBar.progress)
            .apply()
        service.setTarget(null)                       // follow whatever is in front
        service.setMode(modes.checkedRadioButtonId)
        service.setLevels(
            Prefs.blackPoint(blackBar.progress),
            Prefs.whitePoint(whiteBar.progress),
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
                // The overlay was up and the service is gone: nobody turned it off,
                // so say what actually happened rather than "please enable it".
                status.text = getString(
                    if (prefs.getBoolean(Prefs.ACTIVE, false)) R.string.status_acc_lost
                    else R.string.status_acc_off
                )
                toggle.text = getString(R.string.btn_open_accessibility)
            }
            service.isActive -> {
                status.setTextColor(Color.rgb(60, 130, 100))
                status.text = getString(R.string.status_running)
                toggle.text = getString(R.string.btn_stop)
            }
            else -> {
                status.setTextColor(Color.rgb(90, 110, 100))
                status.text = getString(R.string.status_ready)
                toggle.text = getString(R.string.btn_start)
            }
        }
    }
}
