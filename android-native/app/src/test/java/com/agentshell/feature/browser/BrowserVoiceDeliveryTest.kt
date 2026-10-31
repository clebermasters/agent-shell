package com.agentshell.feature.browser

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserVoiceDeliveryTest {
    private val delivery=BrowserVoiceDelivery("request","job\"\\","ação 😀\nprivate transcript",
        BrowserVoiceTarget("host","session","generation","viewer"),
        BrowserLaunch("https://example.test","session","grant"),3)

    @Test fun synchronousKasmInsertionAndDuplicateReceiptsDoNotPollOrResend() = runTest {
        for(receipt in listOf("\"inserted\"","\"duplicate\"")) {
            val scripts=mutableListOf<String>()
            val result=awaitBrowserVoiceDelivery(delivery,{true}) { script -> scripts+=script;receipt }
            assertEquals(receipt,result)
            assertEquals(listOf(browserVoiceScript(delivery)),scripts)
        }
    }

    @Test fun deferredNativeReceiptPollsTheSameJobWithoutRepeatingThePaste() = runTest {
        var pastes=0;var queries=0
        val result=awaitBrowserVoiceDelivery(delivery,{true}) { script ->
            if(script==browserVoiceScript(delivery)) { pastes++;"\"pending\"" }
            else {
                queries++
                assertTrue(script.contains("v.pasteStatus(${JSONObject.quote(delivery.jobId)})"))
                assertFalse(script.contains(delivery.text))
                if(queries==1) "\"pending\"" else "\"inserted\""
            }
        }
        assertEquals("\"inserted\"",result)
        assertEquals(1,pastes);assertEquals(2,queries)
        assertEquals(300L,testScheduler.currentTime)
    }

    @Test fun missingNativeReceiptStopsAtTenSecondsAndNeverReplaysInput() = runTest {
        val scripts=mutableListOf<String>()
        val result=awaitBrowserVoiceDelivery(delivery,{true}) { script -> scripts+=script;"\"pending\"" }
        assertNull(result)
        assertEquals(10_000L,testScheduler.currentTime)
        assertEquals(1,scripts.count { it==browserVoiceScript(delivery) })
        assertTrue(scripts.size in 2..70)
        assertTrue(scripts.drop(1).all { it==browserVoiceStatusScript(delivery.jobId) })
    }

    @Test fun aMissingJavascriptCallbackUsesTheSameDeadline() = runTest {
        var pastes=0
        val result=awaitBrowserVoiceDelivery(delivery,{true}) { pastes++;awaitCancellation() }
        assertNull(result);assertEquals(1,pastes);assertEquals(10_000L,testScheduler.currentTime)
    }

    @Test fun disconnectingBeforeTheNextPollNeverQueriesAnotherViewer() = runTest {
        var connected=true;var calls=0
        val result=awaitBrowserVoiceDelivery(delivery,{connected}) { calls++;connected=false;"\"pending\"" }
        assertNull(result);assertEquals(1,calls)
    }

    @Test fun aLateSuccessAfterTheContextChangesCannotConfirmTheNewViewer() = runTest {
        var current=true;var calls=0
        val result=awaitBrowserVoiceDelivery(delivery,{current}) {
            calls++
            if(calls==1) "\"pending\"" else { delay(200);current=false;"\"inserted\"" }
        }
        assertNull(result);assertEquals(2,calls)
    }

    @Test fun unavailableNativeReceiptFinishesWithoutTreatingItAsInsertion() = runTest {
        var calls=0
        val result=awaitBrowserVoiceDelivery(delivery,{true}) { calls++;if(calls==1) "\"pending\"" else "\"unavailable\"" }
        assertEquals("\"unavailable\"",result);assertEquals(2,calls)
    }

    @Test fun duplicateJavascriptCallbacksAreConsumedOnlyOnce() = runTest {
        val result=awaitBrowserJavascriptResult { callback -> callback("\"pending\"");callback("\"inserted\"") }
        assertEquals("\"pending\"",result)
    }

    @Test fun aJavascriptCallbackAfterCancellationCannotCompleteAnotherDelivery() = runTest {
        lateinit var callback:(String?)->Unit
        var completed=false
        val job=launch { awaitBrowserJavascriptResult { callback=it };completed=true }
        runCurrent();job.cancelAndJoin();callback("\"inserted\"")
        assertFalse(completed)
    }

    @Test fun nativeFailureCodesExplainTheAvailableMacRecoveryAction() {
        val permissions=browserError("MACOS_PERMISSIONS_REQUIRED")
        assertTrue(permissions.contains("Screen Recording"));assertTrue(permissions.contains("Accessibility"))
        assertEquals(permissions,browserError("SCREEN_CAPTURE_PERMISSION_REQUIRED"))
        assertEquals(permissions,browserError("ACCESSIBILITY_PERMISSION_REQUIRED"))
        assertTrue(browserError("CHROME_WINDOW_AMBIGUOUS").contains("windows"))
        assertEquals(browserError("CHROME_WINDOW_AMBIGUOUS"),browserError("AMBIGUOUS_CHROME_WINDOW"))
        assertEquals(browserError("CHROME_WINDOW_AMBIGUOUS"),browserError("AMBIGUOUS_ACCESSIBILITY_WINDOW"))
        assertTrue(browserError("CHROME_NOT_INSTALLED").contains("Install Google Chrome"))
        assertTrue(browserError("NATIVE_HELPER_UNAVAILABLE").contains("helper"))
    }
}
