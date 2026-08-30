package com.jnetai.checkers.net

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.jnetai.checkers.utils.ErrorLogger

/**
 * Hosts the hidden PeerJS transport bridge (WebRTC over public Google STUN and
 * openrelay TURN) that powers online multiplayer. The heavy lifting happens in
 * a bundled page (assets/web/bridge.html + js/bridge.js); this object only owns
 * the WebView lifecycle and routes messages to/from [P2PManager].
 *
 * All public entry points are safe to call from any thread.
 */
object PeerTransport {

    private const val BRIDGE_URL = "https://appassets.androidplatform.net/assets/web/bridge.html"

    private var webView: WebView? = null
    private var ready = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<String>()

    /**
     * Idempotent. Creates the hidden transport WebView (once) and re-parents
     * it into the current activity's window so its JS keeps running even while
     * the pairing screen is paused underneath the game activity.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(context: Context) {
        val wv: WebView
        if (webView == null) {
            wv = buildWebView(context.applicationContext)
            webView = wv
            wv.loadUrl(BRIDGE_URL)
        } else {
            wv = webView!!
        }
        if (context is Activity && Looper.myLooper() == Looper.getMainLooper()) {
            val root = context.findViewById<ViewGroup>(android.R.id.content)
            if (root != null && wv.parent == null) {
                root.addView(wv, ViewGroup.LayoutParams(1, 1))
                wv.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(app: Context): WebView {
        val wv = WebView(app)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "$userAgentString CheckersApp/1.5"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            displayZoomControls = false
        }

        WebView.setWebContentsDebuggingEnabled(false)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return try {
                    assetLoader.shouldInterceptRequest(request.url)
                } catch (e: Exception) {
                    ErrorLogger.logf(ErrorLogger.Codes.NET_RECEIVE_FAILED,
                        "Bridge asset loader error: ${request.url}", e)
                    super.shouldInterceptRequest(view, request)
                }
            }

            @Deprecated("Deprecated in JavaScript")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_CLOSED,
                    "Bridge WebView error $errorCode: $description ($failingUrl)")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                ErrorLogger.logf(ErrorLogger.Codes.NET_CLOSED,
                    "Bridge WebView error code=${error.errorCode} desc=${error.description} url=${request.url}")
            }
        }

        wv.addJavascriptInterface(JsBridge(), "AndroidJsb")
        return wv
    }

    fun isReady(): Boolean = ready

    fun runJs(script: String) {
        val wv = webView ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluate(wv, script)
        } else {
            uiHandler.post { evaluate(wv, script) }
        }
    }

    private fun evaluate(wv: WebView, script: String) {
        if (!ready) {
            synchronized(pending) {
                pending.add(script)
                if (pending.size > 64) pending.removeAt(0)
            }
            return
        }
        try {
            wv.evaluateJavascript(script, null)
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.NET_SEND_FAILED,
                "evaluateJavascript failed for: ${script.take(60)}", e)
        }
    }

    // Called from JS once the bridge page finished loading.
    internal fun onBridgeReady() {
        uiHandler.post {
            val flush: List<String>
            synchronized(pending) {
                ready = true
                flush = pending.toMutableList()
                pending.clear()
            }
            for (s in flush) runJs(s)
        }
    }

    fun destroy() {
        uiHandler.post {
            synchronized(pending) {
                ready = false
                pending.clear()
            }
            try { webView?.stopLoading() } catch (_: Exception) {}
            try { webView?.removeAllViews() } catch (_: Exception) {}
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
        }
    }

    /** Bridge used by the JS side to push events into the Kotlin layer. */
    class JsBridge {

        @JavascriptInterface
        fun onReady() = PeerTransport.onBridgeReady()

        @JavascriptInterface
        fun onStatus(text: String, isError: Boolean) = P2PManager.onStatus(text, isError)

        @JavascriptInterface
        fun onLocalId(id: String) = P2PManager.onLocalId(id)

        @JavascriptInterface
        fun onConnected(role: String, name: String) = P2PManager.onConnected(role, name)

        @JavascriptInterface
        fun onMove(data: String) = P2PManager.onMove(data)

        @JavascriptInterface
        fun onPeerResigned() = P2PManager.onPeerResigned()

        @JavascriptInterface
        fun onPeerDisconnected(reason: String) = P2PManager.onPeerDisconnected(reason)

        @JavascriptInterface
        fun onError(code: String, message: String) = P2PManager.onError(code, message)

        @JavascriptInterface
        fun onLog(message: String) =
            ErrorLogger.logf(ErrorLogger.Codes.NET_PROTOCOL, "[JS] %s", message.take(200))
    }
}