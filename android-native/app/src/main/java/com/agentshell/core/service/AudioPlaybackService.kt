package com.agentshell.core.service

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.agentshell.core.config.BuildConfig
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("X-Auth-Token" to BuildConfig.AUTH_TOKEN))

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
