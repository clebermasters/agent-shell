package com.agentshell.feature.splitscreen

import com.agentshell.feature.chat.ChatDraftWriter
import com.agentshell.feature.chat.ChatSubmission
import com.agentshell.feature.chat.ChatSubmissionStore

internal fun panelSubmission(records: List<ChatSubmission>, serverUrl: String?, sessionName: String,
    windowIndex: Int, isAcp: Boolean, bindingId: String?): ChatSubmission? {
    if (isAcp || serverUrl == null) return null
    return records.firstOrNull { ticket ->
        ticket.serverUrl == serverUrl && ticket.sessionName == sessionName && ticket.windowIndex == windowIndex &&
            (bindingId == null || bindingId == ticket.bindingId)
    }
}

/** ACP/legacy paths clear only after the intended live socket accepts the packet. */
internal fun sendPanelDraft(key: String, writer: ChatDraftWriter, send: () -> Boolean): Boolean {
    if (!send()) return false
    writer.save(key, "")
    return true
}

internal data class PanelDraftMigration(val allowLegacy: Boolean, val saveLocalEdit: Boolean)

internal fun panelDraftMigration(cachedNativeIdentity: Boolean, firstNativeBinding: Boolean,
    deliveredLegacy: Boolean, localFallbackEdit: Boolean): PanelDraftMigration = PanelDraftMigration(
        allowLegacy = !cachedNativeIdentity && firstNativeBinding && !deliveredLegacy,
        saveLocalEdit = firstNativeBinding && localFallbackEdit,
    )

/** Kept outside the large composable so persistence decisions remain independently reviewable. */
internal suspend fun restorePanelDraft(key: String, fallback: String, previousKey: String?, cachedNativeIdentity: Boolean,
    records: List<ChatSubmission>, writer: ChatDraftWriter, submissions: ChatSubmissionStore): String? {
    writer.flush()
    if (key == fallback) return writer.restore(key) { submissions.savedDraft(key) }
    val legacy = submissions.savedDraft(fallback)
    val delivered = records.any { ticket ->
        ticket.draftKey == key && ticket.phase == "complete" && ticket.success == true &&
            ticket.widgetId == null && !ticket.draftEdited && ticket.text == legacy
    }
    val local = writer.snapshot(fallback)
    val migration = panelDraftMigration(cachedNativeIdentity, previousKey == null || previousKey == fallback,
        delivered, local != null)
    if (migration.saveLocalEdit) {
        writer.save(key, local!!.text)
        writer.flush()
    }
    val saved = submissions.migrateSavedDraft(fallback, key, migration.allowLegacy, legacy)
    return writer.snapshot(key)?.text ?: saved
}

internal fun recoverablePanelDraft(ticket: ChatSubmission?): String {
    if (ticket == null || ticket.widgetId != null || ticket.draftEdited) return ""
    if (ticket.phase == "complete" && ticket.success == true) return ""
    return ticket.text
}
