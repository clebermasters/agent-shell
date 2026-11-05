package com.agentshell.feature.splitscreen

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A stable capture keeps draft facts out of the large composable's local data-flow graph. */
@Stable
internal class PanelChatDraftState {
    var text by mutableStateOf("")
    var activeKey by mutableStateOf<String?>(null)
    var revision by mutableLongStateOf(0L)
    var restoreGeneration by mutableLongStateOf(0L)
    var restoring by mutableStateOf(false)
    var cachedNativeIdentity by mutableStateOf(false)
}
