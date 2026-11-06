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
import android.webkit.PermissionRequest;
import android.view.View;
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

    private androidx.webkit.WebViewAssetLoader assetLoader;

    /** Serves some https:// addresses from the app's own files (used by the built-in network check). */
    public void setAssetLoader(androidx.webkit.WebViewAssetLoader loader) { this.assetLoader = loader; }

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
        st.setUserAgentString(cleanUserAgent(st.getUserAgentString()));
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setUseWideViewPort(true);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        w.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        BrowserProxy.apply(activity);
        w.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, android.webkit.WebResourceRequest r) {
                if (assetLoader != null) {
                    android.webkit.WebResourceResponse x = assetLoader.shouldInterceptRequest(r.getUrl());
                    if (x != null) return x;
                }
                return super.shouldInterceptRequest(v, r);
            }
            @Override public void onReceivedHttpAuthRequest(WebView v, android.webkit.HttpAuthHandler h, String host, String realm) {
                if (!BrowserProxy.authenticate(activity, h, true)) super.onReceivedHttpAuthRequest(v, h, host, realm);
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (urlListener != null) urlListener.onUrl(v, url);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                activity.runOnUiThread(() -> handlePermission(request));
            }

            @Override public void onShowCustomView(View view, CustomViewCallback cb) { showFullscreen(view, cb); }

            @Override public void onHideCustomView() { hideFullscreen(); }

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

    // ---- site permissions (camera and microphone for voice and video features) ----
    public static final int REQ_MEDIA = 4712;
    private PermissionRequest pendingMedia;
    private String[] pendingAndroid;

    private void handlePermission(PermissionRequest request) {
        List<String> needed = new ArrayList<>();
        List<String> grant = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) { needed.add(android.Manifest.permission.RECORD_AUDIO); grant.add(r); }
            else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { needed.add(android.Manifest.permission.CAMERA); grant.add(r); }
        }
        if (grant.isEmpty()) { request.deny(); return; }
        List<String> missing = new ArrayList<>();
        for (String perm : needed) {
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing.add(perm);
        }
        if (missing.isEmpty()) { request.grant(grant.toArray(new String[0])); return; }
        if (pendingMedia != null) pendingMedia.deny();
        pendingMedia = request;
        pendingAndroid = grant.toArray(new String[0]);
        activity.requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
    }

    /** Forward from the host activity's onRequestPermissionsResult. */
    public boolean onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        if (requestCode != REQ_MEDIA) return false;
        if (pendingMedia != null) {
            boolean ok = results.length > 0;
            for (int r : results) if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingMedia.grant(pendingAndroid); else pendingMedia.deny();
            pendingMedia = null;
        }
        return true;
    }

    // ---- full-screen video ----
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) { cb.onCustomViewHidden(); return; }
        customView = view;
        customCallback = cb;
        view.setBackgroundColor(0xFF000000);
        popupHost.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean hideFullscreen() {
        if (customView == null) return false;
        popupHost.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        return true;
    }

    // ---- downloads ----
    private void download(String url, String ua, String cd, String mime) {
        try {
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(Uri.parse(url));
            String name = android.webkit.URLUtil.guessFileName(url, cd, mime);
            r.setMimeType(mime);
            r.addRequestHeader("User-Agent", ua);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name);
            ((android.app.DownloadManager) activity.getSystemService(Activity.DOWNLOAD_SERVICE)).enqueue(r);
            android.widget.Toast.makeText(activity, name, android.widget.Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException ignored) { }
        }
    }

    /** The WebView user agent as a plain mobile Chrome one: no "; wv" marker, no "Version/x.y" token, no build id. */
    static String cleanUserAgent(String ua) {
        String s = ua.replace("; wv", "").replace(" Version/4.0", "");
        return s.replaceAll("; Build/[^;)]*", "");
    }

    public boolean hasPopup() {
        if (customView != null) return true; return popup != null; }

    public void closePopup() {
        if (hideFullscreen()) return;
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
