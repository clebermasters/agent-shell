package com.agentshell.feature.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import org.json.JSONObject
import java.net.URLEncoder
import kotlinx.coroutines.delay

data class BrowserLaunch(val origin: String, val sessionId: String, val grant: String,val tunnelAddress:String?=null) {
    val basePath get() = "/browser/$sessionId/"
    val bootstrapUrl get() = "$origin${basePath}bootstrap"
}

/** The trusted streaming viewer gets a scoped cookie; the app's token never enters this WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserViewer(launch: BrowserLaunch, onConnection: (String) -> Unit,
    onView: (WebView?) -> Unit, modifier: Modifier = Modifier,
    onDisplaySupport: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connection by rememberUpdatedState(onConnection)
    val displaySupport by rememberUpdatedState(onDisplaySupport)
    var connected by remember(launch) { mutableStateOf(false) }
    var routeReady by remember(launch) { mutableStateOf(launch.tunnelAddress==null) }
    DisposableEffect(launch) {
        var tunnel:BrowserTunnel?=null
        var disposed=false
        val executor=ContextCompat.getMainExecutor(context)
        if(launch.tunnelAddress!=null) {
            if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                connection("lan-routing-error")
            } else {
                val origin=Uri.parse(launch.origin)
                try {
                    tunnel=BrowserTunnel(origin.host!!,if(origin.port==-1) 443 else origin.port,launch.tunnelAddress)
                    val proxy=ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${tunnel!!.port}")
                        .addBypassRule(origin.host!!).setReverseBypassEnabled(true).removeImplicitRules().build()
                    ProxyController.getInstance().setProxyOverride(proxy,executor) { if(!disposed) routeReady=true }
                } catch (_:Exception) { tunnel?.close();tunnel=null;connection("lan-routing-error") }
            }
        }
        onDispose {
            disposed=true
            val old=tunnel
            if(old!=null) ProxyController.getInstance().clearProxyOverride(executor) { old.close() }
        }
    }
    if(!routeReady) { Box(modifier);return }
    val view = remember(launch) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(false)
            settings.useWideViewPort = false
            WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "BrowserHost", setOf(launch.origin)) { _, message, origin, mainFrame, _ ->
                    if (!mainFrame || origin.toString().trimEnd('/') != launch.origin) return@addWebMessageListener
                    val data = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                    if (data.optString("type") == "display-capability") post {
                        displaySupport(data.optBoolean("resizeSupported", false))
                    }
                    if (data.optString("type") == "connection") post {
                        connected = data.optString("state") == "connected"
                        connection(data.optString("state"))
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                fun allowed(url: Uri): Boolean = url.toString().startsWith(launch.origin + launch.basePath) &&
                    url.scheme == Uri.parse(launch.origin).scheme && url.host == Uri.parse(launch.origin).host
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (allowed(request.url)) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    handler.cancel(); connection("certificate-error")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) connection("disconnected")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    connection(if (response.statusCode == 401) "authorization-error" else "viewer-error")
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    connection("renderer-error"); return true
                }
            }
            postUrl(launch.bootstrapUrl, ("grant=" + URLEncoder.encode(launch.grant, "UTF-8")).toByteArray())
        }
    }
    LaunchedEffect(view) { delay(15_000); if (!connected) connection("viewer-error") }
    DisposableEffect(view, lifecycle) {
        onView(view)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { view.evaluateJavascript("window.AgentShellViewer?.pause()", null); view.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) view.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); onView(null)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view, "BrowserHost")
            view.stopLoading(); view.destroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}
