package com.agentshell

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — no action needed, NotificationHelper handles SecurityException */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            AgentShellTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentShellNavHost()
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
