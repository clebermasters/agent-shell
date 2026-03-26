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
     * Returns the file path on success, or null if permission is missing.
     */
    fun startRecording(): String? {
        if (!hasPermission()) return null
        if (_isRecording.value) return currentFilePath

        val timestamp = System.currentTimeMillis()
        val dir  = context.cacheDir
        val file = File(dir, "whisper_$timestamp.m4a")
        currentFilePath = file.absolutePath

        @Suppress("DEPRECATION")
        val mr: MediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

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

        recorder = mr
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
    fun stopRecording(): String? {
        if (!_isRecording.value) return null

        durationJob?.cancel()
        durationJob = null

        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            // MediaRecorder.stop() can throw if nothing was recorded
        }

        recorder = null
        recordingStartTime = null
        _isRecording.value = false

        return currentFilePath
    }

    /**
     * Cancel an in-progress recording and delete the file.
     */
    fun cancelRecording() {
        durationJob?.cancel()
        durationJob = null

        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {}

        recorder = null
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
    fun dispose() {
        cancelRecording()
        scope.cancel()
    }
}
