package com.termux.app.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;

/** Settings and switch for the floating voice assistant: the three permissions it needs and a start/stop button. */
public final class AssistantActivity extends Activity {
    private TextView status;
    private TextView toggle;
    private Ui ui;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        AppTheme.apply(this);
        ui = new Ui(this);
        setTitle(R.string.assistant_title);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        col.addView(ui.title(getString(R.string.assistant_title)));
        col.addView(ui.text(getString(R.string.assistant_info), 15, ui.t.on2, false), ui.gap(8));

        status = ui.text("", 15, ui.t.on, false);
        LinearLayout sc = ui.card();
        sc.addView(status);
        col.addView(sc, ui.gap(14));

        col.addView(button(R.string.assistant_overlay_btn, v -> {
            if (Build.VERSION.SDK_INT >= 23) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }), ui.gap(10));
        col.addView(button(R.string.assistant_mic_btn, v -> {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }), ui.gap(10));
        col.addView(button(R.string.assistant_a11y_btn, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), ui.gap(10));
        toggle = ui.button(getString(R.string.assistant_start), Ui.Kind.FILLED, v -> {
            if (AssistantOverlayService.isRunning()) AssistantOverlayService.stop(this);
            else AssistantOverlayService.start(this);
            v.postDelayed(this::refresh, 600);
        });
        col.addView(toggle, ui.gap(10));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.t.bg);
        sv.addView(col);
        setContentView(sv);
    }

    private TextView button(int text, View.OnClickListener l) {
        return ui.button(getString(text), Ui.Kind.TONAL, l);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh();
    }

    private String yn(boolean v) { return getString(v ? R.string.assistant_yes : R.string.assistant_no); }

    private void refresh() {
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean mic = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean on = AssistantOverlayService.isRunning();
        status.setText(getString(R.string.assistant_status, yn(overlay), yn(mic), yn(AgentAccessibilityService.isRunning()), yn(on)));
        toggle.setText(on ? R.string.assistant_off : R.string.assistant_start);
        toggle.setBackground(ui.shape(on ? ui.t.err2 : ui.t.p, 24, 0, 0));
        toggle.setTextColor(on ? ui.t.err : ui.t.onp);
    }
}
