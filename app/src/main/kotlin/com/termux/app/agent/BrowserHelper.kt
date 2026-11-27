package com.termux.app.agent

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termux.R
import java.io.File
import java.io.IOException

/**
 * Builds a configured WebView for the built-in browser: cookies, pop-up windows (Google sign-in)
 * and the file/photo picker ("Choose files or photos" on claude.ai).
 * The hosting activity must forward [.onActivityResult].
 **/
class BrowserHelper {

    fun interface UrlListener {
        fun onUrl(view: WebView, url: String)
    }

    private val activity: Activity
    private val popupHost: FrameLayout?
    private val urlListener: UrlListener?
    private var popup: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri?>?>? = null
    private var photoFile: File? = null
    private var videoFile: File? = null
    private var photoUri: Uri? = null
    private var videoUri: Uri? = null

    constructor(activity: Activity, popupHost: FrameLayout?, urlListener: UrlListener?) {
        this.activity = activity
        this.popupHost = popupHost
        this.urlListener = urlListener
    }

    private var assetLoader: WebViewAssetLoader? = null

    /** Serves some https:// addresses from the app's own files (used by the built-in network check).*/
    fun setAssetLoader(loader: WebViewAssetLoader) {
        this.assetLoader = loader
    }

    fun create(): WebView {
        return create(null)
    }

    /** @param profile WebView profile name (own cookies and storage), or null for the default profile.*/
    fun create(profile: String?): WebView {
        val w = WebView(activity)
        if (profile != null && profilesSupported()) {
            try {
                WebViewCompat.setProfile(w, profile)
            } catch (ignored: Throwable) {
                // falls back to the default profile
            }
        }
        val st: WebSettings = w.getSettings()
        st.setJavaScriptEnabled(true)
        st.setDomStorageEnabled(true)
        st.setLoadWithOverviewMode(true)
        st.setSupportMultipleWindows(true)
        st.setJavaScriptCanOpenWindowsAutomatically(true)
        st.setAllowContentAccess(true)
        // Sites (notably Google sign-in) refuse the "; wv" embedded-WebView marker.
        st.setUserAgentString(cleanUserAgent(st.getUserAgentString()))
        st.setMediaPlaybackRequiresUserGesture(false)
        st.setSupportZoom(true)
        st.setBuiltInZoomControls(true)
        st.setDisplayZoomControls(false)
        st.setUseWideViewPort(true)
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE)
        w.setDownloadListener(DownloadListener { url, ua, cd, mime, len -> download(url, ua, cd, mime) })
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        BrowserProxy.apply(activity)
        w.setWebViewClient(object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (assetLoader != null) {
                    val x: WebResourceResponse? = assetLoader!!.shouldInterceptRequest(r.getUrl())
                    if (x != null) {
                        return x
                    }
                }
                return super.shouldInterceptRequest(v, r)
            }

            override fun onReceivedHttpAuthRequest(v: WebView, h: HttpAuthHandler, host: String, realm: String) {
                if (!BrowserProxy.authenticate(activity, h, true)) {
                    super.onReceivedHttpAuthRequest(v, h, host, realm)
                }
            }

            override fun onPageFinished(v: WebView, url: String) {
                if (urlListener != null) {
                    urlListener.onUrl(v, url)
                }
            }
        })
        w.setWebChromeClient(object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                activity.runOnUiThread(Runnable { handlePermission(request) })
            }

            override fun onShowCustomView(view: View, cb: WebChromeClient.CustomViewCallback) {
                showFullscreen(view, cb)
            }

            override fun onHideCustomView() {
                hideFullscreen()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                closePopup()
                popup = create(profile)
                popupHost!!.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                (resultMsg.obj as WebView.WebViewTransport).setWebView(popup)
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                if (window === popup) {
                    closePopup()
                }
            }

            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri?>?>, params: WebChromeClient.FileChooserParams): Boolean {
                if (fileCallback != null) {
                    fileCallback!!.onReceiveValue(null)
                }
                fileCallback = callback
                try {
                    activity.startActivityForResult(buildChooser(params), REQ_FILE)
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    return false
                }
                return true
            }
        })
        return w
    }

    private var pendingMedia: PermissionRequest? = null
    private var pendingAndroid: Array<String>? = null

    private fun handlePermission(request: PermissionRequest) {
        val needed: MutableList<String> = ArrayList<String>()
        val grant: MutableList<String> = ArrayList<String>()
        for (r in request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                needed.add(Manifest.permission.RECORD_AUDIO)
                grant.add(r)
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                needed.add(Manifest.permission.CAMERA)
                grant.add(r)
            }
        }
        if (grant.isEmpty()) {
            request.deny()
            return
        }
        val missing: MutableList<String> = ArrayList<String>()
        for (perm in needed) {
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm)
            }
        }
        if (missing.isEmpty()) {
            request.grant(grant.toTypedArray())
            return
        }
        if (pendingMedia != null) {
            pendingMedia!!.deny()
        }
        pendingMedia = request
        pendingAndroid = grant.toTypedArray()
        activity.requestPermissions(missing.toTypedArray(), REQ_MEDIA)
    }

    /** Forward from the host activity's onRequestPermissionsResult.*/
    fun onRequestPermissionsResult(requestCode: Int, perms: Array<String>, results: IntArray): Boolean {
        if (requestCode != REQ_MEDIA) {
            return false
        }
        if (pendingMedia != null) {
            var ok: Boolean = results.size > 0
            for (r in results) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ok = false
                }
            }
            if (ok) {
                pendingMedia!!.grant(pendingAndroid)
            } else {
                pendingMedia!!.deny()
            }
            pendingMedia = null
        }
        return true
    }

    // ---- full-screen video ----
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = cb
        view.setBackgroundColor(0xFF000000.toInt())
        popupHost!!.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun hideFullscreen(): Boolean {
        if (customView == null) {
            return false
        }
        popupHost!!.removeView(customView)
        customView = null
        if (customCallback != null) {
            customCallback!!.onCustomViewHidden()
        }
        customCallback = null
        return true
    }

    // ---- downloads ----
    private fun download(url: String, ua: String, cd: String, mime: String) {
        try {
            val r = DownloadManager.Request(Uri.parse(url))
            val name: String = URLUtil.guessFileName(url, cd, mime)
            r.setMimeType(mime)
            r.addRequestHeader("User-Agent", ua)
            val cookie: String? = CookieManager.getInstance().getCookie(url)
            if (cookie != null) {
                r.addRequestHeader("Cookie", cookie)
            }
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            Toast.makeText(activity, name, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ignored: RuntimeException) {
            }
        }
    }

    fun hasPopup(): Boolean {
        if (customView != null) {
            return true
        }
        return popup != null
    }

    fun closePopup() {
        if (hideFullscreen()) {
            return
        }
        if (popup != null) {
            popupHost!!.removeView(popup)
            popup!!.destroy()
            popup = null
        }
    }

    /** A small system sheet: camera, video camera, voice recorder, photo picker and the file apps.*/
    private fun buildChooser(params: WebChromeClient.FileChooserParams): Intent {
        val chooser: Intent = Intent.createChooser(params.createIntent(), activity.getString(R.string.agent_browser_attach))
        val extra: MutableList<Intent> = ArrayList<Intent>()
        photoFile = null
        videoFile = null
        photoUri = null
        videoUri = null
        try {
            val dir = File(activity.getCacheDir(), "workspace-share")
            dir.mkdirs()
            val authority: String = activity.getPackageName() + ".workspace.files"
            photoFile = File.createTempFile("photo-", ".jpg", dir)
            photoUri = FileProvider.getUriForFile(activity, authority, photoFile!!)
            extra.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photoUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            videoFile = File.createTempFile("video-", ".mp4", dir)
            videoUri = FileProvider.getUriForFile(activity, authority, videoFile!!)
            extra.add(Intent(MediaStore.ACTION_VIDEO_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, videoUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        } catch (ignored: IOException) {
            // no camera options then; picking existing files still works
        } catch (ignored: IllegalArgumentException) {
        }
        extra.add(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION))
        if (Build.VERSION.SDK_INT >= 33) {
            extra.add(Intent(MediaStore.ACTION_PICK_IMAGES))
        }
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toTypedArray())
        return chooser
    }

    /** Returns true when the result belonged to the file picker.*/
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_FILE) {
            return false
        }
        if (fileCallback != null) {
            var result: Array<Uri?>? = null
            val hasData: Boolean = data != null && (data.getData() != null || data.getClipData() != null)
            if (resultCode == Activity.RESULT_OK && !hasData) {
                // camera apps write into our file instead of returning a Uri
                if (photoFile != null && photoFile!!.length() > 0) {
                    result = arrayOf<Uri?>(photoUri)
                } else if (videoFile != null && videoFile!!.length() > 0) {
                    result = arrayOf<Uri?>(videoUri)
                }
            } else {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            }
            fileCallback!!.onReceiveValue(result)
            fileCallback = null
        }
        if (photoFile != null && photoFile!!.length() == 0L) {
            photoFile!!.delete()
        }
        if (videoFile != null && videoFile!!.length() == 0L) {
            videoFile!!.delete()
        }
        return true
    }

    companion object {
        const val REQ_FILE: Int = 4711

        /** True when this device's WebView can keep several independent accounts (profiles) in one process.*/
        @JvmStatic
        fun profilesSupported(): Boolean {
            try {
                return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            } catch (t: Throwable) {
                return false
            }
        }

        /** Forgets the sign-ins of a deleted web account. Best effort: a profile a live WebView still uses is kept until it is closed.*/
        @JvmStatic
        fun deleteProfile(profile: String?) {
            if (profile == null || !profilesSupported()) {
                return
            }
            try {
                ProfileStore.getInstance().deleteProfile(profile)
            } catch (ignored: Throwable) {
                // in use or already gone
            }
        }

        // ---- site permissions (camera and microphone for voice and video features) ----
        const val REQ_MEDIA: Int = 4712

        /** The WebView user agent as a plain mobile Chrome one: no "; wv" marker, no "Version/x.y" token, no build id.*/
        @JvmStatic
        fun cleanUserAgent(ua: String): String {
            val s: String = ((ua as java.lang.String).replace("; wv", "") as java.lang.String).replace(" Version/4.0", "")
            return (s as java.lang.String).replaceAll("; Build/[^;)]*", "")
        }
    }
}
