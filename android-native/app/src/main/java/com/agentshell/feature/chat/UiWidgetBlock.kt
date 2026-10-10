package com.agentshell.feature.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.agentshell.data.model.ChatBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Debug-only interactive document. The terminal's privileged bridge is never attached. */
@Composable
fun UiWidgetBlock(block: ChatBlock, onAction: (String, String) -> Unit,
    initialState: String? = null, onStateChanged: (String) -> Unit = {}) {
    val html = block.html ?: return
    val id = block.id ?: return
    val context = LocalContext.current
    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        Text(block.title ?: "Interactive widget (preview build required)")
        return
    }
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    // The content hash invalidates controls when the agent replaces a document under the same ID.
    var controls by rememberSaveable(id, html.hashCode()) { mutableStateOf(initialState) }
    val saveControls: (String) -> Unit = { controls = it; onStateChanged(it) }
    var currentView by remember { mutableStateOf<WebView?>(null) }
    var shareError by remember { mutableStateOf(false) }
    val title = block.title ?: "Interactive view"
    val share: () -> Unit = {
        shareError = runCatching { currentView?.let { shareWidget(it, title) } }.isFailure
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = share, enabled = currentView != null) {
                Icon(Icons.Default.Share, contentDescription = "Share widget image", Modifier.size(18.dp))
            }
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.OpenInFull, contentDescription = "Expand interactive view", Modifier.size(18.dp))
            }
        }
        if (!expanded) {
            WidgetSurface(block, controls, saveControls, onAction, { currentView = it })
        }
        if (shareError) Text("Unable to share this view. Try reopening it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text("Interactive view", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = share, enabled = currentView != null) {
                        Icon(Icons.Default.Share, contentDescription = "Share widget image")
                    }
                    IconButton(onClick = { expanded = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close expanded view")
                    }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    WidgetSurface(block, controls, saveControls, onAction, { currentView = it })
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetSurface(
    block: ChatBlock,
    controls: String?,
    onState: (String) -> Unit,
    onAction: (String, String) -> Unit,
    onView: (WebView?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current.density
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val actionCallback by rememberUpdatedState(onAction)
    val stateCallback by rememberUpdatedState(onState)
    val id = block.id!!
    val light = MaterialTheme.colorScheme.background.red > .5f
    var height by remember(id) { mutableIntStateOf(420) }
    var loading by remember(id) { mutableStateOf(true) }
    var failed by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    val view = remember(id, block.html, light, attempt, density) {
        val themed = android.view.ContextThemeWrapper(context,
            if (light) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar)
        WebView(themed).apply {
            WebView.setWebContentsDebuggingEnabled(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val assets = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            addJavascriptInterface(object {
                @JavascriptInterface fun resize(value: Int) { post { height = value.coerceIn(100, 1600) } }
                @JavascriptInterface fun ready() { post {
                    postVisualStateCallback(android.os.SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { loading = false; invalidate() }
                    })
                } }
                @JavascriptInterface fun failed() { post { failed = true; loading = false } }
                @JavascriptInterface fun focusField(top: Double, bottom: Double) {
                    if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return
                    post { scope.launch {
                        // Wait for the IME insets before asking the Compose scroll
                        // container to reveal this field inside its AndroidView.
                        delay(350)
                        if (!isShown || !hasFocus()) return@launch
                        val cssStart = top.coerceIn(0.0, height.toDouble())
                        val start = cssStart.toFloat() * density
                        val end = bottom.coerceIn(cssStart, height.toDouble()).toFloat() * density
                        if (end > start) runCatching { bringIntoView.bringIntoView(Rect(0f, start, width.toFloat(), end)) }
                    } }
                }
                @JavascriptInterface fun saveState(value: String) {
                    if (value.length <= 32768 && runCatching { JSONObject(value) }.isSuccess)
                        post { stateCallback(value) }
                }
                @JavascriptInterface fun requestFollowUp(text: String) {
                    if (text.isBlank() || text.length > 4000) return
                    post {
                        val visible = android.graphics.Rect()
                        if (isShown && getGlobalVisibleRect(visible) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            actionCallback(id, text)
                    }
                }
            }, "WidgetHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val response = assets.shouldInterceptRequest(request.url)
                    response?.responseHeaders = response?.responseHeaders.orEmpty() + ("Access-Control-Allow-Origin" to "*")
                    return response
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failed = true; loading = false
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url.endsWith("/ui-poc/host.html")) {
                        val data = JSONObject().put("id", id).put("title", block.title).put("html", block.html)
                            .put("theme", if (light) "light" else "dark")
                        controls?.let { runCatching { data.put("state", JSONObject(it)) } }
                        view.evaluateJavascript("mountWidget($data)", null)
                    }
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/ui-poc/host.html")
        }
    }
    LaunchedEffect(view) {
        loading = true; failed = false
        delay(12_000)
        if (loading) { loading = false; failed = true }
    }
    DisposableEffect(view, lifecycle) {
        onView(view)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume() else view.onPause()
        val scrollObserver = android.view.ViewTreeObserver.OnScrollChangedListener {
            val visible = android.graphics.Rect()
            val active = view.getGlobalVisibleRect(visible) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            view.evaluateJavascript("window.setWidgetActive?.($active)", null)
        }
        view.viewTreeObserver.addOnScrollChangedListener(scrollObserver)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                view.onResume(); view.evaluateJavascript("window.setWidgetActive?.(true)", null)
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                view.evaluateJavascript("window.setWidgetActive?.(false)", null); view.onPause()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnScrollChangedListener(scrollObserver)
            onView(null)
            view.removeJavascriptInterface("WidgetHost")
            view.stopLoading(); view.destroy()
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This interactive view couldn't load.", style = MaterialTheme.typography.titleSmall)
                Text("Your conversation is still available. Reopen the view to try again.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { attempt++ }) { Text("Reopen view") }
            } else AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(height.dp).bringIntoViewRequester(bringIntoView))
        }
    }
}

private fun shareWidget(view: WebView, title: String) {
    check(view.width > 0 && view.height > 0)
    val ratio = sqrt(4_000_000.0 / (view.width.toDouble() * view.height)).coerceAtMost(1.0).toFloat()
    val bitmap = Bitmap.createBitmap((view.width * ratio).toInt().coerceAtLeast(1),
        (view.height * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE); canvas.scale(ratio, ratio); view.draw(canvas)
        val directory = File(view.context.cacheDir, "file_previews/widget-images").apply { mkdirs() }
        // Keep only a small number of generated previews.
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
        val file = File(directory, "interactive-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(view.context, "${view.context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.context.startActivity(Intent.createChooser(intent, "Share interactive view"))
    } finally { bitmap.recycle() }
}
