package com.termux.app.agent;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Message;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Built-in browser (WebView) shown inside the app. Used for claude.ai and other sign-ins.
 * Supports pop-up windows, which "Sign in with Google" needs.
 */
public class WebBrowserActivity extends Activity {
    private FrameLayout frame;
    private WebView web;
    private BrowserHelper helper;
    private EditText bar;

    /** Browser profile number; 0 is the main one. Each profile runs in its own process with its own cookies. */
    protected int slot() { return 0; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (slot() > 0 && android.os.Build.VERSION.SDK_INT >= 28) {
            try { WebView.setDataDirectorySuffix("profile" + slot()); } catch (IllegalStateException ignored) { }
        }
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#121418"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (28 * getResources().getDisplayMetrics().density), 0, 0);
        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setHint(BrowserPicker.name(this, slot()));
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(Color.parseColor("#1c1f26"));
        bar.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        bar.setOnEditorActionListener((v, actionId, ev) -> { go(bar.getText().toString()); return true; });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        helper = new BrowserHelper(this, frame, (v, url) -> { if (v == web) bar.setText(url); });
        web = helper.create();
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        String url = getIntent().getStringExtra("url");
        go(url == null ? "https://claude.ai" : url);
    }

    private void go(String s) {
        s = s.trim();
        if (!s.contains("://")) s = s.contains(".") && !s.contains(" ") ? "https://" + s
                : "https://duckduckgo.com/?q=" + android.net.Uri.encode(s);
        web.loadUrl(s);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (helper.hasPopup()) { helper.closePopup(); return true; }
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (!helper.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
    }
}
