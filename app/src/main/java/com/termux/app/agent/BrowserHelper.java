package com.termux.app.agent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Builds a configured WebView for the built-in browser: cookies, pop-up windows (Google sign-in)
 * and the file/photo picker ("Choose files or photos" on claude.ai).
 * The hosting activity must forward {@link #onActivityResult}.
 */
public final class BrowserHelper {
    public static final int REQ_FILE = 4711;

    public interface UrlListener { void onUrl(WebView view, String url); }

    private final Activity activity;
    private final FrameLayout popupHost;
    private final UrlListener urlListener;
    private WebView popup;
    private ValueCallback<Uri[]> fileCallback;

    public BrowserHelper(Activity activity, FrameLayout popupHost, UrlListener urlListener) {
        this.activity = activity;
        this.popupHost = popupHost;
        this.urlListener = urlListener;
    }

    public WebView create() {
        final WebView w = new WebView(activity);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setSupportMultipleWindows(true);
        st.setJavaScriptCanOpenWindowsAutomatically(true);
        st.setAllowContentAccess(true);
        // Sites (notably Google sign-in) refuse the "; wv" embedded-WebView marker.
        st.setUserAgentString(st.getUserAgentString().replace("; wv", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (urlListener != null) urlListener.onUrl(v, url);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                closePopup();
                popup = create();
                popupHost.addView(popup, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                if (window == popup) closePopup();
            }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    activity.startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        return w;
    }

    public boolean hasPopup() { return popup != null; }

    public void closePopup() {
        if (popup != null) {
            popupHost.removeView(popup);
            popup.destroy();
            popup = null;
        }
    }

    /** Returns true when the result belonged to the file picker. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) return false;
        if (fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
        return true;
    }
}
