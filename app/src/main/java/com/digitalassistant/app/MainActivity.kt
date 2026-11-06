package com.digitalassistant.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pendingAudioRequest: PermissionRequest? = null
    private val audioPermissionRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 6, 9)
        window.navigationBarColor = Color.rgb(8, 6, 9)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(8, 6, 9))
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val asksForAudio = request.resources.contains(
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    )
                    val asksForOther = request.resources.any {
                        it != PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }

                    if (!asksForAudio || asksForOther) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(
                            arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        )
                    } else {
                        pendingAudioRequest?.deny()
                        pendingAudioRequest = request
                        requestPermissions(
                            arrayOf(Manifest.permission.RECORD_AUDIO),
                            audioPermissionRequestCode
                        )
                    }
                }
            }

            override fun onPermissionRequestCanceled(
                request: PermissionRequest
            ) {
                if (pendingAudioRequest == request) {
                    pendingAudioRequest = null
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/index.html"
        )
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == audioPermissionRequestCode) {
            val request = pendingAudioRequest
            pendingAudioRequest = null

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                request?.grant(
                    arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                )
            } else {
                request?.deny()
            }
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        pendingAudioRequest?.deny()
        pendingAudioRequest = null

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
