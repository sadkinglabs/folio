package dev.sadking.folio.acquire

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** What the browser does with a link. */
sealed interface Navigation {
    /** An https page: load it. */
    data object Load : Navigation

    /** A plain http page: load the https address instead. */
    data class Upgrade(val url: String) : Navigation

    /** Any other scheme (`intent:`, `market:`, `file:`, `javascript:` …): refuse. */
    data object Block : Navigation
}

/** The browser's link rules. Pure, so they are tested on the JVM. */
object BrowserPolicy {
    fun decide(url: String): Navigation {
        val scheme = url.substringBefore(':', "").lowercase()
        return when {
            scheme == "https" && url.length > "https://".length -> Navigation.Load
            scheme == "http" && url.length > "http://".length ->
                Navigation.Upgrade("https" + url.substring(scheme.length))
            url == "about:blank" -> Navigation.Load
            else -> Navigation.Block
        }
    }

    /** [url] as https: an http address is upgraded; anything else is null. */
    fun https(url: String): String? =
        when (val decision = decide(url)) {
            Navigation.Load -> url.takeIf { it.startsWith("https:", ignoreCase = true) }
            is Navigation.Upgrade -> decision.url
            Navigation.Block -> null
        }
}

/**
 * Sets up a WebView for book sites: scripts only when the site needs them, no file or content
 * access, no JavaScript interfaces, no mixed content, Safe Browsing on, no pop-up windows, no
 * location or camera, no file uploads, no third-party cookies. URLs, cookies and page titles are
 * never logged.
 */
internal object BrowserWebView {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(
        webView: WebView,
        javaScript: Boolean,
        onBlocked: () -> Unit,
        onProgress: (Int) -> Unit,
        onPage: (String?) -> Unit,
        onGone: () -> Unit = {},
    ) {
        webView.settings.apply {
            javaScriptEnabled = javaScript
            domStorageEnabled = javaScript
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean =
                    when (val decision = BrowserPolicy.decide(request.url.toString())) {
                        Navigation.Load -> false
                        is Navigation.Upgrade -> {
                            if (request.isForMainFrame) view.loadUrl(decision.url)
                            true
                        }
                        Navigation.Block -> {
                            if (request.isForMainFrame) onBlocked()
                            true
                        }
                    }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    onPage(url)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    onPage(url)
                }

                /**
                 * The page's renderer crashed or was killed for memory. The WebView can't be used
                 * again; [onGone] takes it off screen, which destroys it, instead of the app dying.
                 */
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    onGone()
                    return true
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) =
                    onProgress(newProgress)

                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback,
                ) = callback.invoke(origin, false, false)

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean = false
            }
    }
}
