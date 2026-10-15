package com.agentshell

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.feature.browser.*

/** Loopback-only debug fixture. Never reads host settings or production credentials. */
class BrowserPocActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val origin = intent.getStringExtra("origin") ?: "http://127.0.0.1:18994"
        val parsed = android.net.Uri.parse(origin)
        require(parsed.host == "127.0.0.1" && parsed.scheme in listOf("http", "https") && parsed.port in 18994..18999)
        val sessionId = intent.getStringExtra("sessionId") ?: "poc"
        val grant = intent.getStringExtra("grant") ?: "poc-only"
        setContent {
            AgentShellTheme {
                var status by remember { mutableStateOf("connecting") }
                var view by remember { mutableStateOf<WebView?>(null) }
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Text("Browser fixture · $status")
                    Row {
                        TextButton(onClick = { view?.evaluateJavascript("window.AgentShellViewer?.keyboard()", null) }) { Text("Keyboard") }
                        TextButton(onClick = { view?.evaluateJavascript("window.AgentShellViewer?.key(65293)", null) }) { Text("Enter") }
                    }
                    BrowserViewer(BrowserLaunch(origin, sessionId, grant), { status = it }, { view = it }, Modifier.weight(1f))
                }
            }
        }
    }
}
