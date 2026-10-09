package com.agentshell.data.services

/** Immediate results and retry events can both announce the same transcription. */
internal class TranscriptionReceipt {
    private val handled = linkedSetOf<String>()
    fun accept(jobId: String): Boolean {
        if (!handled.add(jobId)) return false
        while (handled.size > 64) handled.remove(handled.first())
        return true
    }
}
