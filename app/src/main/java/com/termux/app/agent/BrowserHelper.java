package com.termux.app.agent;

import android.app.Activity;
import android.os.Build;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    private File photoFile;
    private File videoFile;
    private Uri photoUri;
    private Uri videoUri;

    public BrowserHelper(Activity activity, FrameLayout popupHost, UrlListener urlListener) {
        this.activity = activity;
        this.popupHost = popupHost;
        this.urlListener = urlListener;
    }

    public WebView create() { return create(null); }

    /** True when this device's WebView can keep several independent accounts (profiles) in one process. */
    public static boolean profilesSupported() {
        try {
            return androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** @param profile WebView profile name (own cookies and storage), or null for the default profile. */
    public WebView create(final String profile) {
        final WebView w = new WebView(activity);
        if (profile != null && profilesSupported()) {
            try {
                androidx.webkit.WebViewCompat.setProfile(w, profile);
            } catch (Throwable ignored) {
                // falls back to the default profile
            }
        }
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
                popup = create(profile);
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
                    activity.startActivityForResult(buildChooser(params), REQ_FILE);
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

    /** A small system sheet: camera, video camera, voice recorder, photo picker and the file apps. */
    private Intent buildChooser(WebChromeClient.FileChooserParams params) {
        Intent chooser = Intent.createChooser(params.createIntent(), activity.getString(com.termux.R.string.agent_browser_attach));
        List<Intent> extra = new ArrayList<>();
        photoFile = null; videoFile = null; photoUri = null; videoUri = null;
        try {
            File dir = new File(activity.getCacheDir(), "workspace-share");
            dir.mkdirs();
            String authority = activity.getPackageName() + ".workspace.files";
            photoFile = File.createTempFile("photo-", ".jpg", dir);
            photoUri = FileProvider.getUriForFile(activity, authority, photoFile);
            extra.add(new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            videoFile = File.createTempFile("video-", ".mp4", dir);
            videoUri = FileProvider.getUriForFile(activity, authority, videoFile);
            extra.add(new Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, videoUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (IOException | IllegalArgumentException ignored) {
            // no camera options then; picking existing files still works
        }
        extra.add(new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION));
        if (Build.VERSION.SDK_INT >= 33) extra.add(new Intent(MediaStore.ACTION_PICK_IMAGES));
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toArray(new Intent[0]));
        return chooser;
    }

    /** Returns true when the result belonged to the file picker. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE) return false;
        if (fileCallback != null) {
            Uri[] result = null;
            boolean hasData = data != null && (data.getData() != null || data.getClipData() != null);
            if (resultCode == Activity.RESULT_OK && !hasData) {
                // camera apps write into our file instead of returning a Uri
                if (photoFile != null && photoFile.length() > 0) result = new Uri[]{photoUri};
                else if (videoFile != null && videoFile.length() > 0) result = new Uri[]{videoUri};
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
        if (photoFile != null && photoFile.length() == 0) photoFile.delete();
        if (videoFile != null && videoFile.length() == 0) videoFile.delete();
        return true;
    }
}
