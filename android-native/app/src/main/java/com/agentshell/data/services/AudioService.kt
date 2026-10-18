package com.agentshell.data.services

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.agentshell.core.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// AudioService
//
// Audio recording service using MediaRecorder with AAC-LC codec.
// Mirrors flutter/lib/data/services/audio_service.dart.
// ---------------------------------------------------------------------------
@Singleton
class AudioService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Suppress("DEPRECATION")
    private var recorder: MediaRecorder? = null
    private var recordingOwner: String? = null
    private var currentFilePath: String? = null
    private var recordingStartTime: Long? = null

    private var durationJob: Job? = null

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true when the RECORD_AUDIO permission has been granted. */
    fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording to a temp .m4a file.
     * Returns the file path on success, or null if unavailable. An owner cannot
     * reuse, stop, or cancel a recording started by another feature.
     */
    @Synchronized
    fun startRecording(owner: String? = null): String? {
        if (!hasPermission()) return null
        if (_isRecording.value) return currentFilePath.takeIf { owner == recordingOwner }

        val dir  = context.cacheDir
        val file = File(dir, "whisper_${UUID.randomUUID()}.m4a")
        var mr: MediaRecorder? = null
        try {
            @Suppress("DEPRECATION")
            mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AppConfig.AUDIO_BIT_RATE)
                setAudioSamplingRate(AppConfig.AUDIO_SAMPLE_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (_: Exception) {
            runCatching { mr?.release() }
            file.delete()
            return null
        }

        recorder = mr
        recordingOwner = owner
        currentFilePath = file.absolutePath
        recordingStartTime = System.currentTimeMillis()
        _recordingDuration.value = 0
        _isRecording.value = true

        // Update duration every second
        durationJob = scope.launch {
            while (_isRecording.value) {
                delay(1_000)
                val start = recordingStartTime ?: break
                _recordingDuration.value = ((System.currentTimeMillis() - start) / 1000).toInt()
            }
        }

        return currentFilePath
    }

    /**
     * Stop recording and return the file path, or null if not recording.
     */
    @Synchronized
    fun stopRecording(owner: String? = null): String? {
        if (!_isRecording.value || owner != recordingOwner) return null

        durationJob?.cancel()
        durationJob = null

        val path = currentFilePath
        val stopped = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.release() }

        recorder = null
        recordingOwner = null
        currentFilePath = null
        recordingStartTime = null
        _isRecording.value = false

        if (!stopped) {
            path?.let { File(it).delete() }
            return null
        }
        return path
    }

    /**
     * Cancel an in-progress recording and delete the file.
     */
    @Synchronized
    fun cancelRecording(owner: String? = null) {
        if (_isRecording.value && owner != recordingOwner) return
        durationJob?.cancel()
        durationJob = null

        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }

        recorder = null
        recordingOwner = null
        recordingStartTime = null
        _isRecording.value = false
        _recordingDuration.value = 0

        currentFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        currentFilePath = null
    }

    /** Release all resources. */
    @Synchronized
    fun dispose() {
        cancelRecording(recordingOwner)
        scope.cancel()
    }
}
