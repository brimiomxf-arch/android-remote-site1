package com.monib.receivercontrol.controller

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class WebsiteActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = getSharedPreferences("controller", MODE_PRIVATE).getString("language", "ar") ?: "ar"
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(loc(language, "موقع التطبيق", "App website")) },
                            navigationIcon = {
                                TextButton(onClick = { finish() }) { Text(loc(language, "رجوع", "Back")) }
                            }
                        )
                    }
                ) { padding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                setDownloadListener { url, _, _, _, _ -> downloadApk(url, language) }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val uri = request.url
                                        if (uri.path.orEmpty().endsWith(".apk", ignoreCase = true)) {
                                            downloadApk(uri.toString(), language)
                                            return true
                                        }
                                        if (uri.scheme in setOf("http", "https") && uri.host == SITE_HOST) return false
                                        if (uri.scheme in setOf("http", "https")) {
                                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                                            return true
                                        }
                                        return true
                                    }
                                }
                                loadUrl(SITE_URL)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun downloadApk(rawUrl: String, language: String) {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host != SITE_HOST || !uri.path.orEmpty().endsWith(".apk", ignoreCase = true)) {
            Toast.makeText(this, loc(language, "رابط التنزيل غير صالح.", "This download link is not valid."), Toast.LENGTH_LONG).show()
            return
        }

        val fileName = uri.lastPathSegment?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "AndroidRemote.apk"
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription(loc(language, "جارٍ تنزيل التطبيق", "Downloading app"))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(this, loc(language, "بدأ تنزيل التطبيق. ستجده في التنزيلات.", "Download started. Find it in Downloads."), Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val SITE_HOST = "brimiomxf-arch.github.io"
        const val SITE_URL = "https://brimiomxf-arch.github.io/android-remote-site1/"
    }
}
