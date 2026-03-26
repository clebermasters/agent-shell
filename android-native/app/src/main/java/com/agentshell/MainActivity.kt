package com.agentshell

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.agentshell.core.theme.AgentShellTheme
import com.agentshell.feature.home.AgentShellNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * Singleton holder for volume key callbacks.
 * Set by TerminalScreen when active, cleared on dispose.
 */
object VolumeKeyHandler {
    var onVolumeUp: (() -> Unit)? = null
    var onVolumeDown: (() -> Unit)? = null
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentShellTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentShellNavHost()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                VolumeKeyHandler.onVolumeUp?.let { it(); return true }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                VolumeKeyHandler.onVolumeDown?.let { it(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (VolumeKeyHandler.onVolumeUp != null || VolumeKeyHandler.onVolumeDown != null) {
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
