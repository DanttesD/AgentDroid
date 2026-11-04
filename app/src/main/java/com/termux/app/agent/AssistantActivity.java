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
    private Button toggle;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.assistant_title);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView info = new TextView(this);
        info.setText(R.string.assistant_info);
        info.setTextSize(15);
        col.addView(info);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad, 0, pad);
        col.addView(status);

        col.addView(button(R.string.assistant_overlay_btn, v -> {
            if (Build.VERSION.SDK_INT >= 23) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }));
        col.addView(button(R.string.assistant_mic_btn, v -> {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }));
        col.addView(button(R.string.assistant_a11y_btn, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        toggle = button(R.string.assistant_start, v -> {
            if (AssistantOverlayService.isRunning()) AssistantOverlayService.stop(this);
            else AssistantOverlayService.start(this);
            v.postDelayed(this::refresh, 600);
        });
        col.addView(toggle);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    private Button button(int text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setOnClickListener(l);
        return bt;
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
    }
}
