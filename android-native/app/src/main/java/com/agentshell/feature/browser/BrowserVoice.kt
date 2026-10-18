package com.agentshell.feature.browser

import org.json.JSONObject
import java.util.UUID

/** A transcript belongs to one browser runtime, even when its viewer reconnects. */
data class BrowserVoiceTarget(val hostId: String, val sessionId: String, val generation: String, val viewerId: String) {
    /** A deliberate paste may use a new viewer after reconnecting to the same running browser. */
    fun sameRuntime(other: BrowserVoiceTarget?) = other!=null && hostId==other.hostId &&
        sessionId==other.sessionId && generation==other.generation
}

data class BrowserVoiceTranscript(val jobId: String, val text: String, val target: BrowserVoiceTarget?,
    val message: String? = null)

data class BrowserVoiceDelivery(val requestId: String, val jobId: String, val text: String,
    val target: BrowserVoiceTarget, val launch: BrowserLaunch, val attempt: Long)

data class BrowserVoiceUiState(val isRecording: Boolean = false, val recordingDuration: Int = 0,
    val isTranscribing: Boolean = false, val error: String? = null, val failedJobId: String? = null,
    val failedContext: String? = null, val transcripts: List<BrowserVoiceTranscript> = emptyList(),
    val delivery: BrowserVoiceDelivery? = null)

internal data class BrowserVoiceCapture(val context: String, val target: BrowserVoiceTarget,
    val launch: BrowserLaunch, val attempt: Long)

/** Only the uninterrupted viewer that started a recording can insert its result automatically. */
internal class BrowserVoiceGate {
    private var capture: BrowserVoiceCapture? = null

    fun begin(target: BrowserVoiceTarget, launch: BrowserLaunch, attempt: Long): BrowserVoiceCapture {
        val context = JSONObject().put("host", target.hostId).put("session", target.sessionId)
            .put("generation", target.generation).put("viewer", target.viewerId)
            .put("recording", UUID.randomUUID().toString()).toString()
        return BrowserVoiceCapture(context, target, launch, attempt).also { capture = it }
    }

    fun invalidate() { capture = null }

    fun canInsert(context: String?, target: BrowserVoiceTarget?, launch: BrowserLaunch?, attempt: Long,
        connected: Boolean): Boolean = capture?.let {
        connected && context == it.context && target == it.target && launch == it.launch && attempt == it.attempt
    } == true
}

internal fun browserVoiceTarget(context: String?): BrowserVoiceTarget? = runCatching {
    val data = JSONObject(context ?: return null)
    BrowserVoiceTarget(data.getString("host"), data.getString("session"), data.getString("generation"),
        data.getString("viewer")).takeIf { listOf(it.hostId, it.sessionId, it.generation, it.viewerId).all(String::isNotBlank) }
}.getOrNull()

/** JSON escaping is required: transcripts are data inside the trusted viewer, never source code. */
internal fun browserVoiceScript(delivery: BrowserVoiceDelivery): String =
    """(()=>{const v=window.AgentShellViewer;if(typeof v?.pasteText!=='function')return 'unsupported';return v.pasteText(${JSONObject.quote(delivery.jobId)},${JSONObject.quote(delivery.text)});})()"""
