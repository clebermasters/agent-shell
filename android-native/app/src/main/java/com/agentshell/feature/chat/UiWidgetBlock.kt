package com.agentshell.feature.chat

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.agentshell.data.model.ChatBlock
import org.json.JSONObject

/** Debug POC only. The terminal's privileged JavaScript bridge is never used. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun UiWidgetBlock(block: ChatBlock, onAction: (String, String) -> Unit) {
    val html = block.html ?: return
    val id = block.id ?: return
    val context = LocalContext.current
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        Text(block.title ?: "Interactive widget (POC build required)")
        return
    }
    val callback by rememberUpdatedState(onAction)
    var height by remember(id) { mutableStateOf(440) }
    val lightTheme = MaterialTheme.colorScheme.background.red > 0.5f
    val view = remember(id, lightTheme) {
        val themed = android.view.ContextThemeWrapper(context, if (lightTheme) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar)
        WebView(themed).apply {
            WebView.setWebContentsDebuggingEnabled(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            addJavascriptInterface(object {
                @JavascriptInterface fun resize(value: Int) { post { height = value.coerceIn(100, 1200) } }
                @JavascriptInterface fun requestFollowUp(text: String) {
                    if (text.isNotBlank() && text.length <= 4000) post { if (isShown) callback(id, text) }
                }
            }, "WidgetHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val response = assets.shouldInterceptRequest(request.url)
                    response?.responseHeaders = (response?.responseHeaders.orEmpty() + ("Access-Control-Allow-Origin" to "*"))
                    return response
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.endsWith("/ui-poc/host.html")) {
                        val data = JSONObject().put("id", id).put("html", html).toString()
                        view.evaluateJavascript("mountWidget($data)", null)
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/ui-poc/host.html")
        }
    }
    DisposableEffect(view) { onDispose { view.removeJavascriptInterface("WidgetHost"); view.stopLoading(); view.destroy() } }
    Column {
        Text(block.title ?: "Interactive widget")
        AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(height.dp))
    }
}
