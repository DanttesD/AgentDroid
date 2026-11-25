package com.termux.app.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout
import com.termux.shared.termux.TermuxConstants

/** Basic embedded browser for viewing help pages.*/
class HelpActivity : Activity() {

    @JvmField var mWebView: WebView? = null

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val progressLayout = RelativeLayout(this)
        val lParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lParams.addRule(RelativeLayout.CENTER_IN_PARENT)
        val progressBar = ProgressBar(this)
        progressBar.setIndeterminate(true)
        progressBar.setLayoutParams(lParams)
        progressLayout.addView(progressBar)

        mWebView = WebView(this)
        val settings: WebSettings = mWebView!!.getSettings()
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE)
        // WebView AppCache was removed; it is disabled by default on legacy WebViews.
        setContentView(progressLayout)
        mWebView!!.clearCache(true)

        mWebView!!.setWebViewClient(object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.equals(TermuxConstants.TERMUX_WIKI_URL) || url.startsWith(TermuxConstants.TERMUX_WIKI_URL + "/")) {
                    // Inline help.
                    setContentView(progressLayout)
                    return false
                }

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    // Android TV does not have a system browser.
                    setContentView(progressLayout)
                    return false
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                setContentView(mWebView)
            }
        })
        mWebView!!.loadUrl(TermuxConstants.TERMUX_WIKI_URL)
    }

    override fun onBackPressed() {
        if (mWebView!!.canGoBack()) {
            mWebView!!.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
