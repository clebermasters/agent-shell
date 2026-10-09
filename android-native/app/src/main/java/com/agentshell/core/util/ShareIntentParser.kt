package com.agentshell.core.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.agentshell.data.model.IncomingShare
import java.io.IOException
import java.util.UUID

object ShareIntentParser {
    fun isShare(intent: Intent?): Boolean = intent?.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

    fun parse(intent: Intent): IncomingShare {
        require(isShare(intent))
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            .ifBlank { intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString().orEmpty() }
            .ifBlank { intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty() }
        if (text.length > 100_000) throw IOException("Shared text is too long (maximum 100,000 characters)")
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        val uris = (streams.ifEmpty {
            val clip = intent.clipData
            (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
        }).distinct()
        if (uris.size > 20) throw IOException("Share up to 20 files at a time")
        if (uris.any { it.scheme != "content" }) throw IOException("The sending app did not provide a readable file")
        if (text.isBlank() && uris.isEmpty()) throw IOException("No text or files were shared")
        return IncomingShare(UUID.randomUUID().toString(), text, uris.map(Uri::toString), intent.type)
    }
}
