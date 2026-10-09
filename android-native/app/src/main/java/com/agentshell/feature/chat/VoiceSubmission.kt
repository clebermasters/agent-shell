package com.agentshell.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The ViewModel owns submission so clearing the UI's transcription event cannot cancel it. */
internal fun CoroutineScope.submitVoiceDraft(
    draft: String,
    autoSendEnabled: suspend () -> Boolean,
    currentDraft: () -> String,
    send: (String) -> Unit,
) = launch {
    // A manual send or a user edit while preferences load takes precedence over automatic sending.
    if (autoSendEnabled() && currentDraft() == draft) send(draft)
}
