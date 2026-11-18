package com.agentshell.feature.browser

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class BrowserVoiceTest {
    private val target=BrowserVoiceTarget("host","session","generation","viewer")
    private val launch=BrowserLaunch("https://example.test","session","scoped-grant")

    @Test fun uninterruptedRecordingCanInsertIntoItsOriginalViewer() {
        val gate=BrowserVoiceGate()
        val recording=gate.begin(target,launch,3)
        assertTrue(gate.canInsert(recording.context,target,launch,3,true))
        assertEquals(target,browserVoiceTarget(recording.context))
    }

    @Test fun disconnectedViewerNeverInsertsAnEventAutomatically() {
        val gate=BrowserVoiceGate()
        val recording=gate.begin(target,launch,3)
        assertFalse(gate.canInsert(recording.context,target,launch,3,false))
        assertFalse(gate.canInsert(recording.context,target,null,3,true))
    }

    @Test fun reconnectingToTheSameRuntimeStillRequiresAnExplicitPaste() {
        val gate=BrowserVoiceGate()
        val recording=gate.begin(target,launch,3)
        assertFalse(gate.canInsert(recording.context,target,launch.copy(grant="new-grant"),4,true))
        // Even a reconnect with an identical launch grant is a new connection attempt.
        assertFalse(gate.canInsert(recording.context,target,launch,4,true))
    }

    @Test fun pauseHandoffOrStopInvalidatesAutomaticDeliveryPermanently() {
        val gate=BrowserVoiceGate()
        val recording=gate.begin(target,launch,3)
        gate.invalidate()
        assertFalse(gate.canInsert(recording.context,target,launch,3,true))
        assertEquals("The transcript retains its original destination for manual recovery",target,browserVoiceTarget(recording.context))
    }

    @Test fun aDifferentHostRuntimeSessionOrViewerCannotReceiveTheRecording() {
        val gate=BrowserVoiceGate()
        val recording=gate.begin(target,launch,3)
        for(other in listOf(target.copy(hostId="other"),target.copy(sessionId="other"),
            target.copy(generation="restarted"),target.copy(viewerId="other"))) {
            assertFalse(gate.canInsert(recording.context,other,launch,3,true))
        }
    }

    @Test fun automaticPasteRequiresKnownUnchangedTabOnWindowManagementHosts() {
        val gate=BrowserVoiceGate()
        val unknown=gate.begin(target,launch,3)
        assertFalse(gate.canInsert(unknown.context,target,launch,3,true,requireKnownTab=true))
        assertTrue(gate.canInsert(unknown.context,target,launch,3,true,requireKnownTab=false))
        val known=target.copy(windowId=42,tabId=37)
        val capture=gate.begin(known,launch,3)
        assertEquals(known,browserVoiceTarget(capture.context))
        assertTrue(gate.canInsert(capture.context,known,launch,3,true,requireKnownTab=true))
        assertFalse(gate.canInsert(capture.context,known.copy(tabId=38),launch,3,true,requireKnownTab=true))
        assertTrue(known.sameRuntime(known.copy(tabId=38)))
    }

    @Test fun delayedResultFromAnOlderRecordingCannotAutopasteDuringANewerRecording() {
        val gate=BrowserVoiceGate()
        val old=gate.begin(target,launch,3)
        val current=gate.begin(target,launch,3)
        assertNotEquals(old.context,current.context)
        assertFalse(gate.canInsert(old.context,target,launch,3,true))
        assertTrue(gate.canInsert(current.context,target,launch,3,true))
    }

    @Test fun manualPasteCanRecoverThroughANewViewerOnlyIntoTheSameRuntime() {
        assertTrue(target.sameRuntime(target.copy(viewerId="reconnected-viewer")))
        assertFalse(target.sameRuntime(target.copy(hostId="other-host")))
        assertFalse(target.sameRuntime(target.copy(sessionId="new-session")))
        assertFalse(target.sameRuntime(target.copy(generation="restarted")))
        assertFalse(target.sameRuntime(null))
    }

    @Test fun persistedContextsAreLosslessAndMalformedContextsAreRejected() {
        val unusual=target.copy(hostId="quotes\"/newline\n",sessionId="sessão-😀")
        assertEquals(unusual,browserVoiceTarget(BrowserVoiceGate().begin(unusual,launch,3).context))
        for(context in listOf(null,"", "{}", "{broken", "{\"host\":\"h\",\"session\":\"s\",\"generation\":\"g\",\"viewer\":\"\"}")) {
            assertNull(browserVoiceTarget(context))
        }
    }

    @Test fun transcriptIsEscapedAsDataAndNeverDispatchesEnter() {
        val text="ação 😀\n'\");window.alert('unsafe');\\ \u2028 end"
        val delivery=BrowserVoiceDelivery("request","job\"\\",text,target,launch,3)
        val script=browserVoiceScript(delivery)
        assertTrue(script.contains("v.pasteText(${JSONObject.quote(delivery.jobId)},${JSONObject.quote(text)})"))
        assertFalse(script.contains("sendKey"))
        assertFalse(script.contains("65293"))
        assertFalse(script.contains("submit("))
    }
}
