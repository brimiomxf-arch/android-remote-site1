package com.monib.receivercontrol.controller

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Hosts the phone page served by the user's UrTV app; no UrTV source or assets are bundled here. */
class UrTvRemoteActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var progress: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.getStringExtra(EXTRA_URL)?.let(Uri::parse)
        if (address == null || !UrTvPhoneDiscovery.isUrTvPhoneUrl(address)) {
            Toast.makeText(this, "رابط UrTV غير صالح", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val page = WebView(this)
        webView = page
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(page, false)
        page.webChromeClient = WebChromeClient()
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !sameOrigin(request.url, address)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !sameOrigin(Uri.parse(url), address)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress?.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress?.visibility = View.GONE
            }
        }
        root.addView(page, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        setContentView(root)
        page.loadUrl(address.toString())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page.canGoBack()) page.goBack() else finish()
            }
        })
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun sameOrigin(candidate: Uri, expected: Uri): Boolean =
        candidate.scheme == "http" && candidate.host == expected.host && candidate.port == expected.port

    companion object {
        const val EXTRA_URL = "urtv.remote.url"
    }
}
