
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
    private lateinit var auth: ChatGPTAuth
    private lateinit var chatGPTBridge: ChatGPTBridge

    private var pendingAudioRequest: PermissionRequest? = null
    private val audioPermissionRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(8, 6, 9)
        window.navigationBarColor = Color.rgb(8, 6, 9)

        auth = ChatGPTAuth(this)
        webView = WebView(this)
        chatGPTBridge = ChatGPTBridge(this, auth)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.setBackgroundColor(Color.rgb(8, 6, 9))
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode =
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(
            chatGPTBridge,
            "AndroidChatGPT"
        )

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(
                    request.url
                )
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return request.url.host !=
                    "appassets.androidplatform.net"
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {
                super.onPageFinished(view, url)

                if (
                    url.startsWith(
                        "https://appassets.androidplatform.net/"
                    )
                ) {
                    installChatGPTInterface()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(
                request: PermissionRequest
            ) {
                runOnUiThread {
                    val asksForAudio =
                        request.resources.contains(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        )

                    val asksForOther = request.resources.any {
                        it != PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }

                    if (!asksForAudio || asksForOther) {
                        request.deny()
                        return@runOnUiThread
                    }

                    if (
                        checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        request.grant(
                            arrayOf(
                                PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            )
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

    private fun installChatGPTInterface() {
        val script = """
            (function() {
                if (window.__chatGPTInstalled) return;
                if (!window.AndroidChatGPT) return;

                window.__chatGPTInstalled = true;

                window.ask = function(txt) {
                    txt = String(txt || '').trim();
                    if (!txt) return;

                    if (!window.AndroidChatGPT.isConnected()) {
                        if (window.setCap) {
                            window.setCap(
                                'err',
                                'Сначала подключите ChatGPT'
                            );
                        }
                        return;
                    }

                    var id = String(
                        Date.now() + Math.random()
                    );

                    if (window.setCap) {
                        window.setCap('hint', 'Думаю…');
                    }

                    window.AndroidChatGPT.sendMessage(
                        id,
                        txt,
                        'цифровой помощник'
                    );
                };

                window.onChatGPTReply = function(id, answer) {
                    if (window.setCap) {
                        window.setCap('hint', 'Готов слушать');
                    }

                    if (window.speak) {
                        window.speak(answer);
                    }
                };

                window.onChatGPTError = function(message, id) {
                    if (window.setCap) {
                        window.setCap(
                            'err',
                            String(message || 'Ошибка ChatGPT')
                        );
                    }
                };

                window.onChatGPTConnected = function(message) {
                    var button = document.getElementById(
                        'chatGPTConnectButton'
                    );

                    if (button) {
                        button.textContent = 'ChatGPT подключён';
                    }

                    if (window.setCap) {
                        window.setCap('hint', message);
                    }
                };

                window.onChatGPTDisconnected = function() {
                    var button = document.getElementById(
                        'chatGPTConnectButton'
                    );

                    if (button) {
                        button.textContent = 'Подключить ChatGPT';
                    }
                };

                var button = document.createElement('button');

                button.id = 'chatGPTConnectButton';
                button.textContent = 'Подключить ChatGPT';

                button.style.cssText =
                    'position:fixed;z-index:99999;bottom:18px;' +
                    'right:18px;padding:12px 17px;' +
                    'border:1px solid rgba(255,255,255,.2);' +
                    'border-radius:30px;background:#242124;' +
                    'color:white;font-size:13px;';

                button.onclick = function() {
                    window.AndroidChatGPT.connectChatGPT();
                };

                document.body.appendChild(button);

                if (window.AndroidChatGPT.isConnected()) {
                    button.textContent = 'ChatGPT подключён';
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
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
                    arrayOf(
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    )
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

        if (::chatGPTBridge.isInitialized) {
            chatGPTBridge.close()
        }

        if (::auth.isInitialized) {
            auth.close()
        }

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
