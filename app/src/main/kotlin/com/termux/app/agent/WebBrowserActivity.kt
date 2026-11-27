package com.termux.app.agent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Built-in browser (WebView) shown inside the app. Used for claude.ai and other sign-ins.
 * Supports pop-up windows, which "Sign in with Google" needs.
 **/
open class WebBrowserActivity : Activity() {
    private var frame: FrameLayout? = null
    private var web: WebView? = null
    private var helper: BrowserHelper? = null
    private var bar: EditText? = null

    /** Browser profile number; 0 is the main one. Each profile runs in its own process with its own cookies.*/
    protected open fun slot(): Int {
        return 0
    }

    protected override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (slot() > 0 && Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix("profile" + slot())
            } catch (ignored: IllegalStateException) {
            }
        }
        frame = FrameLayout(this)
        frame!!.setBackgroundColor(Color.parseColor("#121418"))
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        root.setPadding(0, (28 * getResources().getDisplayMetrics().density).toInt(), 0, 0)
        bar = EditText(this)
        bar!!.setSingleLine(true)
        bar!!.setHint(BrowserPicker.name(this, slot()))
        bar!!.setTextColor(Color.WHITE)
        bar!!.setBackgroundColor(Color.parseColor("#1c1f26"))
        bar!!.setImeOptions(EditorInfo.IME_ACTION_GO)
        bar!!.setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        bar!!.setOnEditorActionListener(TextView.OnEditorActionListener { v, actionId, ev ->
            go(bar!!.getText().toString())
            return@OnEditorActionListener true
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        helper = BrowserHelper(this, frame, BrowserHelper.UrlListener { v, url ->
            if (v === web) {
                bar!!.setText(url)
            }
        })
        web = helper!!.create()
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame!!.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        val url: String? = getIntent().getStringExtra("url")
        go(if (url == null) "https://claude.ai" else url)
    }

    private fun go(s: String) {
        var s = s
        s = (s as java.lang.String).trim()
        if (!s.contains("://")) {
            s = if (s.contains(".") && !s.contains(" ")) "https://" + s else "https://duckduckgo.com/?q=" + Uri.encode(s)
        }
        web!!.loadUrl(s)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (helper!!.hasPopup()) {
                helper!!.closePopup()
                return true
            }
            if (web!!.canGoBack()) {
                web!!.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (helper != null && helper!!.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    protected override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (!helper!!.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
