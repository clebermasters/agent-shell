package com.agentshell.feature.chat

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Fullscreen chat content owns its safe drawing and keyboard insets. */
@Composable
internal fun ChatFullscreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    // Compose 1.7's custom-width dialog measures against configuration height,
    // which includes system bars on Android 15 and ignores the window's IME
    // constraints. Keep platform measurement and set the window size explicitly.
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
                if (it.attributes.width != matchParent || it.attributes.height != matchParent) {
                    it.setLayout(matchParent, matchParent)
                }
            }
        }
        content()
    }
}
