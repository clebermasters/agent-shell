package com.agentshell.data.repository

import android.content.Context
import com.agentshell.core.config.BuildConfig
import com.agentshell.data.model.ChatBlockType
import com.agentshell.data.model.ChatMessage
import com.agentshell.data.model.chatFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/** Attachment bytes live in filesDir so Android's temporary-cache cleanup cannot evict them. */
@Singleton
class OfflineAttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val downloads = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (url in downloads) {
                try {
                    if (cachedFile(context, url) != null) continue
                    val destination = attachmentFile(context, url)
                    destination.parentFile?.mkdirs()
                    val temporary = File(destination.path + ".part")
                    try {
                        val request = Request.Builder().url(url).header("X-Auth-Token", BuildConfig.AUTH_TOKEN).build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use
                            val body = response.body ?: return@use
                            body.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                            if (temporary.renameTo(destination)) completed.emit(url)
                        }
                    } finally { temporary.delete() }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Retry missing files on the next history refresh. Keep saved bytes on failures.
                } finally { pending.remove(url) }
            }
        }
    }

    fun enqueue(baseUrl: String, messages: List<ChatMessage>) {
        for (message in messages) for (block in message.blocks) {
            if (block.blockType !in setOf(ChatBlockType.IMAGE, ChatBlockType.AUDIO, ChatBlockType.FILE)) continue
            val id = block.id?.takeIf { it.isNotBlank() } ?: continue
            val url = "$baseUrl/$id"
            if (cachedFile(context, url) == null && pending.add(url)) downloads.trySend(url)
        }
    }

    internal fun close() { scope.cancel() }

    companion object {
        private val completed = MutableSharedFlow<String>(extraBufferCapacity = 16)

        /** Only the image being downloaded changes; disk/hash work stays off the UI thread. */
        fun observeUrl(context: Context, url: String): Flow<String> = channelFlow {
            // Subscribe before probing disk so a download finishing during the probe is not missed.
            launch(start = CoroutineStart.UNDISPATCHED) {
                completed.filter { it == url }.collect { send(resolve(context, url)) }
            }
            send(resolve(context, url))
        }.distinctUntilChanged().flowOn(Dispatchers.IO)
        private fun attachmentFile(context: Context, url: String) = File(context.filesDir, "offline_chat_files/${chatFingerprint(url)}")
        fun cachedFile(context: Context, url: String): File? = attachmentFile(context, url).takeIf { it.isFile }
        fun resolve(context: Context, url: String): String = cachedFile(context, url)?.toURI()?.toString() ?: url
    }
}
