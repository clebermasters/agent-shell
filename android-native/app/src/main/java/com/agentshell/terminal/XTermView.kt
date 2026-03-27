package com.agentshell.terminal

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "XTermView"

/**
 * Compose wrapper for an xterm.js-based terminal running in a WebView.
 *
 * @param onInput   Called when the user types in the terminal (base64-decoded UTF-8).
 * @param onResize  Called when xterm.js reports a new terminal size.
 * @param onReady   Called when xterm.js is initialized (with initial cols/rows).
 * @param modifier  Standard Compose modifier.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XTermView(
    onInput: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onReady: (cols: Int, rows: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Remember the bridge so callbacks update without recreating the WebView
    val bridge = remember { XTermBridge(onInput, onResize, onReady) }
    bridge.onInput = onInput
    bridge.onResize = onResize
    bridge.onReady = onReady

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(bridge, "Android")
            loadUrl("file:///android_asset/terminal.html")
        }
    }

    // Store webView reference in bridge for calling JS from Kotlin
    bridge.webView = webView

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}

/**
 * Controller for sending commands to xterm.js from Kotlin code.
 * Obtain via [rememberXTermController].
 */
class XTermController {
    internal var bridge: XTermBridge? = null

    /** Write terminal data (raw UTF-8 string — will be base64-encoded for JS bridge). */
    fun writeData(data: String) {
        bridge?.writeData(data)
    }

    /** Request xterm.js to fit to its container. */
    fun fit() {
        bridge?.callJs("termBridge.fit()")
    }

    /** Set font size in pixels. */
    fun setFontSize(px: Float) {
        bridge?.callJs("termBridge.setFontSize(${px.toInt()})")
    }

    /** Clear the terminal. */
    fun clear() {
        bridge?.callJs("termBridge.clear()")
    }

    /** Focus the terminal. */
    fun focus() {
        bridge?.callJs("termBridge.focus()")
    }

    /** Get selected text from xterm.js via callback. */
    fun getSelection(callback: (String) -> Unit) {
        val wv = bridge?.webView ?: return
        wv.post {
            wv.evaluateJavascript("termBridge.getSelection()") { result ->
                // evaluateJavascript returns JSON-encoded string (with quotes)
                val text = result?.removeSurrounding("\"")?.replace("\\n", "\n")
                    ?.replace("\\t", "\t") ?: ""
                callback(text)
            }
        }
    }

    /** Select all text in terminal buffer. */
    fun selectAll() {
        bridge?.callJs("termBridge.selectAll()")
    }

    /** Clear current selection. */
    fun clearSelection() {
        bridge?.callJs("termBridge.clearSelection()")
    }

    /** Extract all text from terminal buffer (for selection overlay). */
    fun getBufferText(callback: (String) -> Unit) {
        val wv = bridge?.webView ?: return
        wv.post {
            wv.evaluateJavascript("termBridge.getBufferText()") { result ->
                val text = result
                    ?.removeSurrounding("\"")
                    ?.replace("\\n", "\n")
                    ?.replace("\\t", "\t")
                    ?.replace("\\\\", "\\")
                    ?: ""
                callback(text)
            }
        }
    }
}

@Composable
fun rememberXTermController(): XTermController = remember { XTermController() }

/**
 * Overload that also accepts an [XTermController] for sending data to the terminal.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun XTermView(
    controller: XTermController,
    onInput: (String) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onReady: (cols: Int, rows: Int) -> Unit,
    onVolumeUp: (() -> Unit)? = null,
    onVolumeDown: (() -> Unit)? = null,
    onSelectionChanged: ((hasSelection: Boolean) -> Unit)? = null,
    onHorizontalDragChanged: ((dx: Float) -> Unit)? = null,
    onHorizontalSwipeEnd: ((dx: Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val bridge = remember { XTermBridge(onInput, onResize, onReady) }
    bridge.onInput = onInput
    bridge.onResize = onResize
    bridge.onReady = onReady
    controller.bridge = bridge

    // Store callbacks in bridge so the WebView can access them
    bridge.onVolumeUp = onVolumeUp
    bridge.onVolumeDown = onVolumeDown
    bridge.onSelectionChanged = onSelectionChanged
    bridge.onHorizontalDragChanged = onHorizontalDragChanged
    bridge.onHorizontalSwipeEnd = onHorizontalSwipeEnd

    val webView = remember {
        object : WebView(context) {
            private var touchStartY = 0f
            private var touchStartX = 0f
            private var scrollAccum = 0f
            private var isScrolling = false
            private var isHorizontal = false
            private var sentDownToWebView = false
            private val dragThreshold = 10f
            private val lineHeight = 16f

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.y
                        touchStartX = event.x
                        scrollAccum = 0f
                        isScrolling = false
                        isHorizontal = false
                        sentDownToWebView = false
                        bridge.onHorizontalDragChanged?.invoke(0f)
                        // Don't pass DOWN to WebView yet — wait to see
                        // if this becomes a scroll or a tap.
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isHorizontal) {
                            val dy = touchStartY - event.y
                            val dx = event.x - touchStartX

                            if (!isScrolling) {
                                if (abs(dx) > abs(dy) && abs(dx) > dragThreshold) {
                                    isHorizontal = true
                                } else if (abs(dy) > dragThreshold) {
                                    isScrolling = true
                                    scrollAccum = 0f
                                    parent?.requestDisallowInterceptTouchEvent(true)
                                }
                            }

                            if (isScrolling) {
                                scrollAccum += dy
                                touchStartY = event.y
                                val lines = (scrollAccum / lineHeight).toInt()
                                if (lines != 0) {
                                    val button = if (lines > 0) 64 else 65
                                    val count = abs(lines).coerceAtMost(5)
                                    val seq = "\u001b[<$button;1;1M"
                                    bridge.onInput(seq.repeat(count))
                                    scrollAccum -= lines * lineHeight
                                }
                                return true
                            }
                        }
                        // Notify drag progress when horizontal
                        if (isHorizontal) {
                            bridge.onHorizontalDragChanged?.invoke(event.x - touchStartX)
                        }
                        // Not yet scrolling — still might become one, consume MOVE
                        if (!sentDownToWebView) return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasScrolling = isScrolling
                        val wasHorizontal = isHorizontal
                        val totalDx = event.x - touchStartX
                        isScrolling = false
                        isHorizontal = false
                        scrollAccum = 0f
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (wasScrolling) return true
                        if (wasHorizontal) {
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                bridge.onHorizontalSwipeEnd?.invoke(totalDx)
                            } else {
                                bridge.onHorizontalDragChanged?.invoke(0f)
                            }
                            return true
                        }
                        // Was a tap — replay DOWN+UP to WebView so it
                        // toggles keyboard / focuses terminal.
                        if (!sentDownToWebView) {
                            val down = MotionEvent.obtain(
                                event.downTime, event.downTime,
                                MotionEvent.ACTION_DOWN, touchStartX, touchStartY, 0,
                            )
                            super.dispatchTouchEvent(down)
                            down.recycle()
                            sentDownToWebView = true
                        }
                    }
                }
                return super.dispatchTouchEvent(event)
            }

            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                            bridge.onVolumeUp?.invoke(); return true
                        }
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            bridge.onVolumeDown?.invoke(); return true
                        }
                    }
                } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_UP,
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF1E1E1E.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            addJavascriptInterface(bridge, "Android")
            loadUrl("file:///android_asset/terminal.html")
        }
    }

    bridge.webView = webView

    DisposableEffect(Unit) {
        onDispose {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}

// ── Internal bridge ────────────────────────────────────────────────────────

internal class XTermBridge(
    var onInput: (String) -> Unit,
    var onResize: (cols: Int, rows: Int) -> Unit,
    var onReady: (cols: Int, rows: Int) -> Unit,
) {
    var webView: WebView? = null
    var onVolumeUp: (() -> Unit)? = null
    var onVolumeDown: (() -> Unit)? = null
    var onSelectionChanged: ((hasSelection: Boolean) -> Unit)? = null
    var onHorizontalDragChanged: ((dx: Float) -> Unit)? = null
    var onHorizontalSwipeEnd: ((dx: Float) -> Unit)? = null

    // ── JS → Android (called from JavaScript) ─────────────────────────────

    @JavascriptInterface
    fun onTerminalInput(b64data: String) {
        try {
            val decoded = String(Base64.decode(b64data, Base64.DEFAULT), Charsets.UTF_8)
            onInput(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "onTerminalInput decode error", e)
        }
    }

    @JavascriptInterface
    fun onTerminalResize(cols: Int, rows: Int) {
        Log.d(TAG, "onTerminalResize: ${cols}x${rows}")
        onResize(cols, rows)
    }

    @JavascriptInterface
    fun onTerminalReady(cols: Int, rows: Int) {
        Log.d(TAG, "onTerminalReady: ${cols}x${rows}")
        onReady(cols, rows)
    }

    @JavascriptInterface
    fun onSelectionChange(hasSelection: Boolean) {
        onSelectionChanged?.invoke(hasSelection)
    }

    // ── Android → JS ──────────────────────────────────────────────────────

    fun writeData(data: String) {
        val b64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        callJs("termBridge.write('$b64')")
    }

    fun callJs(script: String) {
        val wv = webView ?: return
        wv.post { wv.evaluateJavascript(script, null) }
    }
}
