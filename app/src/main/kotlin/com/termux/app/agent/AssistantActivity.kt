package com.termux.app.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.R

/** Settings and switch for the floating voice assistant: the three permissions it needs and a start/stop button.*/
class AssistantActivity : Activity() {
    private var status: TextView? = null
    private var toggle: TextView? = null
    private var ui: Ui? = null

    private var themeSig: String = ""

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        AppTheme.apply(this)
        themeSig = AppTheme.signature(this)
        ui = Ui(this)
        setTitle(R.string.assistant_title)
        val pad: Int = Math.round(16 * getResources().getDisplayMetrics().density)
        val col = LinearLayout(this)
        col.setOrientation(LinearLayout.VERTICAL)
        col.setPadding(pad, pad, pad, pad)

        col.addView(ui!!.title(getString(R.string.assistant_title)))
        col.addView(ui!!.text(getString(R.string.assistant_info), 15.0f, ui!!.t.on2, false), ui!!.gap(8))

        status = ui!!.text("", 15.0f, ui!!.t.on, false)
        val sc: LinearLayout = ui!!.card()
        sc.addView(status)
        col.addView(sc, ui!!.gap(14))

        col.addView(button(R.string.assistant_overlay_btn, View.OnClickListener { v ->
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))
            }
        }), ui!!.gap(10))
        col.addView(button(R.string.assistant_mic_btn, View.OnClickListener { v ->
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(arrayOf<String>(Manifest.permission.RECORD_AUDIO), 1)
            }
        }), ui!!.gap(10))
        col.addView(button(R.string.assistant_a11y_btn, View.OnClickListener { v -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }), ui!!.gap(10))
        toggle = ui!!.button(getString(R.string.assistant_start), Ui.Kind.FILLED, View.OnClickListener { v ->
            if (AssistantOverlayService.isRunning()) {
                AssistantOverlayService.stop(this)
            } else {
                AssistantOverlayService.start(this)
            }
            v.postDelayed(Runnable(this::refresh), 600L)
        })
        col.addView(toggle, ui!!.gap(10))

        val sv = ScrollView(this)
        sv.setBackgroundColor(ui!!.t.bg)
        sv.addView(col)
        setContentView(sv)
    }

    private fun button(text: Int, l: View.OnClickListener): TextView {
        return ui!!.button(getString(text), Ui.Kind.TONAL, l)
    }

    protected override fun onResume() {
        super.onResume()
        if (!themeSig.equals(AppTheme.signature(this))) {
            recreate()
            return
        }
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun yn(v: Boolean): String {
        return getString(if (v) R.string.assistant_yes else R.string.assistant_no)
    }

    private fun refresh() {
        val overlay: Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val mic: Boolean = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val on: Boolean = AssistantOverlayService.isRunning()
        status!!.setText(getString(R.string.assistant_status, yn(overlay), yn(mic), yn(AgentAccessibilityService.isRunning()), yn(on)))
        toggle!!.setText(if (on) R.string.assistant_off else R.string.assistant_start)
        toggle!!.setBackground(ui!!.shape(if (on) ui!!.t.err2 else ui!!.t.p, 24.0f, 0, 0.0f))
        toggle!!.setTextColor(if (on) ui!!.t.err else ui!!.t.onp)
    }
}
