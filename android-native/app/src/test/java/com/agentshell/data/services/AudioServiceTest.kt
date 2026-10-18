package com.agentshell.data.services

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AudioServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun anotherFeatureCannotReuseStopOrCancelAnOwnedRecording() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = AudioService(app)
        try {
            val path = audio.startRecording("browser-one")
            assertNotNull(path)
            assertEquals(path, audio.startRecording("browser-one"))
            assertNull(audio.startRecording())
            assertNull(audio.startRecording("browser-two"))
            assertNull(audio.stopRecording())
            assertNull(audio.stopRecording("browser-two"))
            audio.cancelRecording()
            audio.cancelRecording("browser-two")
            assertTrue(audio.isRecording.value)
            assertEquals(path, audio.stopRecording("browser-one"))
            assertFalse(audio.isRecording.value)
        } finally {
            audio.dispose()
        }
    }

    @Test fun browserCannotTakeOverALegacyRecordingAndDisposeReleasesAnyOwner() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = AudioService(app)
        try {
            assertNotNull(audio.startRecording())
            assertNull(audio.startRecording("browser"))
            assertNull(audio.stopRecording("browser"))
            audio.cancelRecording("browser")
            assertTrue(audio.isRecording.value)
            audio.cancelRecording()
            assertFalse(audio.isRecording.value)
            val path = audio.startRecording("browser")!!
            File(path).writeText("recorded audio")
            audio.dispose()
            assertFalse(audio.isRecording.value)
            assertFalse(File(path).exists())
        } finally {
            audio.dispose()
        }
    }

    @Test fun missingPermissionDoesNotStartOrClaimTheRecorder() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val audio = AudioService(app)
        try {
            assertNull(audio.startRecording("browser"))
            assertFalse(audio.isRecording.value)
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            assertNotNull(audio.startRecording())
        } finally {
            audio.dispose()
        }
    }
}
