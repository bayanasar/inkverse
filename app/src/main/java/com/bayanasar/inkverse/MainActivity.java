package com.bayanasar.inkverse;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Night inversion, driven entirely by the accessibility screenshot path.
 *
 * No MediaProjection: it cannot work here. An opaque overlay captures itself and the
 * feedback collapses to grey, FLAG_SECURE blanks the capture to black, and this
 * firmware's capture dialog has no single-app option. takeScreenshotOfWindow()
 * captures one window, so the overlay is never part of its own input.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "inkverse";
    private static final String KEY_THEME = "theme";   // 0 system, 1 light, 2 dark
    private static final String KEY_MODE = "mode";
    private static final String KEY_BLACK = "black";
    private static final String KEY_WHITE = "white";

    private SharedPreferences prefs;
    private TextView status;
    private Button toggle;
    private SeekBar blackBar, whiteBar;
    private RadioGroup modes;

    @Override
    protected void onCreate(Bundle b) {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        applyTheme(prefs.getInt(KEY_THEME, 0));
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText("Inkverse");
        t.setTextSize(26);
        root.addView(t);

        status = new TextView(this);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        toggle = new Button(this);
        toggle.setTextSize(18);
        toggle.setOnClickListener(v -> {
            InvertAccessibilityService s = InvertAccessibilityService.get();
            if (s == null) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            if (s.isActive()) s.stop();
            else { pushSettings(s); s.start(); }
            refresh();
        });
        root.addView(toggle);

        TextView hint = new TextView(this);
        hint.setText("\nThe overlay is opaque, so it hides itself while you are in this "
                + "app and comes back when you leave. Pen and touch always pass through "
                + "to the app underneath.\n");
        root.addView(hint);

        TextView ml = new TextView(this);
        ml.setText("Mode");
        ml.setTextSize(16);
        root.addView(ml);

        modes = new RadioGroup(this);
        addMode(modes, "Off / passthrough", OverlayView.MODE_PASSTHROUGH);
        addMode(modes, "Grayscale invert  (best for E-Ink)", OverlayView.MODE_LUMA_INVERT);
        addMode(modes, "RGB invert  (photos go negative)", OverlayView.MODE_RGB_INVERT);
        addMode(modes, "Grayscale invert + levels", OverlayView.MODE_SHAPED);
        modes.check(prefs.getInt(KEY_MODE, OverlayView.MODE_LUMA_INVERT));
        modes.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putInt(KEY_MODE, id).apply();
            InvertAccessibilityService s = InvertAccessibilityService.get();
            if (s != null) s.setMode(id);
        });
        root.addView(modes);

        TextView ll = new TextView(this);
        ll.setText("\nLevels  (used by the \"+ levels\" mode)");
        ll.setTextSize(16);
        root.addView(ll);
        blackBar = addSlider(root, "black point", prefs.getInt(KEY_BLACK, 0));
        whiteBar = addSlider(root, "white point", prefs.getInt(KEY_WHITE, 255));

        TextView tl = new TextView(this);
        tl.setText("\nAppearance");
        tl.setTextSize(16);
        root.addView(tl);

        RadioGroup themeGroup = new RadioGroup(this);
        addTheme(themeGroup, "Follow system", 0);
        addTheme(themeGroup, "Light", 1);
        addTheme(themeGroup, "Dark", 2);
        themeGroup.check(1000 + prefs.getInt(KEY_THEME, 0));
        themeGroup.setOnCheckedChangeListener((g, id) -> {
            prefs.edit().putInt(KEY_THEME, id - 1000).apply();
            recreate();
        });
        root.addView(themeGroup);

        Button acc = new Button(this);
        acc.setText("Accessibility settings");
        acc.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(acc);

        setContentView(scroll);
        refresh();
    }

    /**
     * The reading screen is white when this app is not doing anything, so the app
     * itself defaults to light and follows the system rather than forcing dark.
     */
    private void applyTheme(int choice) {
        switch (choice) {
            case 1: setTheme(R.style.Theme_Inkverse_Light); break;
            case 2: setTheme(R.style.Theme_Inkverse_Dark); break;
            default:
                int night = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                setTheme(night == Configuration.UI_MODE_NIGHT_YES
                        ? R.style.Theme_Inkverse_Dark : R.style.Theme_Inkverse_Light);
        }
    }

    private void addTheme(RadioGroup g, String label, int choice) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setId(1000 + choice);
        g.addView(r);
    }

    private void addMode(RadioGroup g, String label, int id) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setId(id);
        g.addView(r);
    }

    private SeekBar addSlider(LinearLayout root, String label, int init) {
        TextView t = new TextView(this);
        t.setText(label);
        root.addView(t);
        SeekBar s = new SeekBar(this);
        s.setMax(255);
        s.setProgress(init);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (!u) return;
                InvertAccessibilityService svc = InvertAccessibilityService.get();
                if (svc != null) pushSettings(svc);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(s);
        return s;
    }

    private void pushSettings(InvertAccessibilityService s) {
        prefs.edit().putInt(KEY_BLACK, blackBar.getProgress())
                    .putInt(KEY_WHITE, whiteBar.getProgress()).apply();
        s.setTarget("*");                     // follow whatever is in front
        s.setMode(modes.getCheckedRadioButtonId());
        s.setShaping(1f,
                blackBar.getProgress() / 255f * 0.5f,
                0.5f + whiteBar.getProgress() / 255f * 0.5f);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        InvertAccessibilityService s = InvertAccessibilityService.get();
        if (s == null) {
            status.setTextColor(Color.rgb(200, 90, 60));
            status.setText("Accessibility service is off.\n"
                    + "Enable \"Night Invert\" in Accessibility settings — that is what "
                    + "allows a fully opaque overlay that still lets touch through.");
            toggle.setText("Open accessibility settings");
        } else if (s.isActive()) {
            status.setTextColor(Color.rgb(60, 130, 100));
            status.setText("Running. Leave this app and the inverted view appears.");
            toggle.setText("STOP");
        } else {
            status.setTextColor(Color.rgb(90, 110, 100));
            status.setText("Ready.");
            toggle.setText("START");
        }
    }
}
