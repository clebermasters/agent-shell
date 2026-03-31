package com.agentshell.data.services

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agentshell.core.service.AudioPlaybackService
import com.agentshell.data.local.PreferencesDataStore
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class AudioPlaybackState(
    val activeAudioId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

@Singleton
class AudioPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: PreferencesDataStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var positionPollJob: Job? = null

    fun connect() {
        val token = SessionToken(
            context,
            ComponentName(context, AudioPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get()
            attachPlayerListener()
            startPositionPolling()
        }, MoreExecutors.directExecutor())
    }

    fun play(audioId: String, audioUrl: String) {
        val ctrl = controller ?: return
        scope.launch {
            val savedMs = preferencesDataStore.getAudioPosition(audioId)
            ctrl.setMediaItem(MediaItem.fromUri(audioUrl))
            ctrl.prepare()
            if (savedMs > 0L) ctrl.seekTo(savedMs)
            ctrl.play()
            _state.update { it.copy(activeAudioId = audioId) }
        }
    }

    fun pause() {
        controller?.pause()
        saveCurrentPosition()
    }

    fun stop() {
        val ctrl = controller ?: return
        saveCurrentPosition()
        ctrl.stop()
        ctrl.clearMediaItems()
        _state.update { AudioPlaybackState() }
    }

    fun seekTo(fraction: Float) {
        val duration = _state.value.durationMs
        if (duration > 0) controller?.seekTo((fraction * duration).toLong())
    }

    private fun saveCurrentPosition() {
        val audioId = _state.value.activeAudioId ?: return
        val posMs = _state.value.positionMs
        scope.launch { preferencesDataStore.setAudioPosition(audioId, posMs) }
    }

    private fun attachPlayerListener() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (!isPlaying) saveCurrentPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val dur = controller?.duration?.coerceAtLeast(0L) ?: 0L
                _state.update { it.copy(isBuffering = buffering, durationMs = dur) }
                if (playbackState == Player.STATE_ENDED) {
                    val id = _state.value.activeAudioId ?: return
                    scope.launch { preferencesDataStore.setAudioPosition(id, 0L) }
                    _state.update { it.copy(isPlaying = false, positionMs = 0L) }
                }
            }
        })
    }

    private fun startPositionPolling() {
        positionPollJob?.cancel()
        positionPollJob = scope.launch {
            while (isActive) {
                delay(200)
                val ctrl = controller ?: continue
                if (ctrl.isPlaying) {
                    _state.update { it.copy(positionMs = ctrl.currentPosition) }
                }
            }
        }
    }
}
