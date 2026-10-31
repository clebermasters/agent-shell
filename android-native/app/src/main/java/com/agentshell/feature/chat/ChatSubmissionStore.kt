package com.agentshell.feature.chat

import com.agentshell.data.local.PreferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A delivery ticket, not a queued command. It must never replay terminal input. */
@Serializable
data class ChatSubmission(
    val id: String,
    val serverUrl: String,
    val conversationKey: String,
    val bindingId: String,
    val sessionName: String,
    val windowIndex: Int,
    val text: String,
    val draftKey: String,
    val file: AttachedFile? = null,
    val widgetId: String? = null,
    val phase: String = "sending",
    val success: Boolean? = null,
    val error: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val draftEdited: Boolean = false,
) {
    val waiting get() = phase == "sending" || phase == "checking"
    val unresolved get() = phase != "complete"
    val lookup get() = mapOf("type" to "get-chat-send-result", "requestId" to id, "bindingId" to bindingId)
    val notice get() = when (phase) {
        "sending" -> "Sending message…"
        "checking" -> "Checking whether your message was sent…"
        "unknown" -> "Delivery could not be confirmed. Check the conversation before sending again"
        else -> null
    }
    fun matchesDraft(text: String, file: AttachedFile?) = !draftEdited && this.text == text && this.file == file

    fun accepts(server: String, message: Map<String, Any?>) =
        serverUrl == server && message["requestId"] == id && message["bindingId"] == bindingId

    fun receipt(server: String, message: Map<String, Any?>): ChatSubmission {
        if (!accepts(server, message) || phase == "complete") return this
        return when (message["status"]) {
            "pending" -> copy(phase = "checking")
            "unknown" -> copy(phase = "unknown")
            "complete", null -> {
                val accepted = message["success"] as? Boolean ?: return this
                copy(phase = "complete", success = accepted, error = message["error"] as? String)
            }
            else -> this
        }
    }
}

/** Small persisted tickets let a recreated chat reconcile an acknowledgement it missed. */
@Singleton
class ChatSubmissionStore @Inject constructor(private val preferences: PreferencesDataStore) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun decode(raw: String) = runCatching { json.decodeFromString<List<ChatSubmission>>(raw) }.getOrDefault(emptyList())
    val records = preferences.chatSubmissions.map(::decode).distinctUntilChanged()

    suspend fun begin(ticket: ChatSubmission): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val all = decode(preferences.chatSubmissions.first()).map {
            if (it.waiting && now - it.createdAtMillis > 10 * 60_000L) it.copy(phase = "unknown") else it
        }.filter { it.waiting || now - it.createdAtMillis < 7 * 24 * 60 * 60_000L }
        if (all.any { it.serverUrl == ticket.serverUrl && it.conversationKey == ticket.conversationKey && it.waiting }) return@withLock false
        val retained = all.filterNot { it.serverUrl == ticket.serverUrl && it.conversationKey == ticket.conversationKey }.toMutableList()
        while (retained.size >= 64) {
            val oldestFinal = retained.filterNot { it.waiting }.minByOrNull { it.createdAtMillis } ?: break
            retained.remove(oldestFinal)
        }
        if (retained.size >= 64) return@withLock false
        if (ticket.widgetId == null) preferences.seedDraftMessageIfMissing(ticket.draftKey, ticket.text)
        preferences.setChatSubmissions(json.encodeToString(retained + ticket))
        true
    }

    suspend fun record(server: String, message: Map<String, Any?>) = mutex.withLock {
        val old = decode(preferences.chatSubmissions.first())
        val next = old.map { it.receipt(server, message) }
        if (old != next) preferences.setChatSubmissions(json.encodeToString(next))
    }

    suspend fun checking(id: String, receiptsAvailable: Boolean = true) = mutex.withLock {
        val old = decode(preferences.chatSubmissions.first())
        val next = old.map {
            if (it.id == id && it.waiting) it.copy(phase = if (receiptsAvailable) "checking" else "unknown") else it
        }
        if (next != old) preferences.setChatSubmissions(json.encodeToString(next))
    }

    suspend fun unconfirmed(id: String) = checking(id, receiptsAvailable = false)

    suspend fun remove(id: String) = mutex.withLock {
        val old = decode(preferences.chatSubmissions.first())
        val next = old.filterNot { it.id == id }
        if (next != old) preferences.setChatSubmissions(json.encodeToString(next))
    }

    suspend fun clearSavedDraft(ticket: ChatSubmission) {
        if (ticket.widgetId == null && !ticket.draftEdited) preferences.clearDraftMessageIfMatches(ticket.draftKey, ticket.text)
    }

    suspend fun draftEdited(id: String, text: String, file: AttachedFile?) = mutex.withLock {
        val old = decode(preferences.chatSubmissions.first())
        val next = old.map {
            if (it.id == id && it.widgetId == null && !it.draftEdited && (it.text != text || it.file != file)) it.copy(draftEdited = true) else it
        }
        if (next != old) preferences.setChatSubmissions(json.encodeToString(next))
    }

    suspend fun savedDraft(key: String) = preferences.getDraftMessage(key)
    suspend fun saveDraft(key: String, text: String) = preferences.setDraftMessage(key, text, keepEmpty = true)
}
