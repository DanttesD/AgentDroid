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
    private WebView popup;
    private EditText bar;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.parseColor("#121418"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, (int) (28 * getResources().getDisplayMetrics().density), 0, 0);
        bar = new EditText(this);
        bar.setSingleLine(true);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(Color.parseColor("#1c1f26"));
        bar.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        bar.setOnEditorActionListener((v, actionId, ev) -> { go(bar.getText().toString()); return true; });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = newWebView();
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        String url = getIntent().getStringExtra("url");
        go(url == null ? "https://claude.ai" : url);
    }

    private WebView newWebView() {
        final WebView w = new WebView(this);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportMultipleWindows(true);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        // Sites (notably Google sign-in) refuse the "; wv" embedded-WebView marker.
        st.setUserAgentString(st.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { if (v == web) bar.setText(url); }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                closePopup();
                popup = newWebView();
                frame.addView(popup, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                if (window == popup) closePopup();
            }
        });
        return w;
    }

    private void closePopup() {
        if (popup != null) {
            frame.removeView(popup);
            popup.destroy();
            popup = null;
        }
    }

    private void go(String s) {
        s = s.trim();
        if (!s.contains("://")) s = s.contains(".") && !s.contains(" ") ? "https://" + s
                : "https://duckduckgo.com/?q=" + android.net.Uri.encode(s);
        web.loadUrl(s);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (popup != null) { closePopup(); return true; }
            if (web.canGoBack()) { web.goBack(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }
}
