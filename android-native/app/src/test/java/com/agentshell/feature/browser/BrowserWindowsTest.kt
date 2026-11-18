package com.agentshell.feature.browser

import android.app.Application
import com.agentshell.data.repository.BrowserFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BrowserWindowsTest {
    private fun tab(id: Int, active: Boolean, group: Int = -1, split: Int = -1) = JSONObject()
        .put("id", id).put("title", "Tab $id").put("active", active).put("pinned", false)
        .put("groupId", group).put("splitId", split)

    private fun window(id: Int, focused: Boolean, vararg tabs: JSONObject) = JSONObject().put("id", id)
        .put("focused", focused).put("tabs", JSONArray(tabs.toList()))

    private fun catalogue(revision: String, vararg windows: JSONObject) = JSONObject().put("ok", true).put("snapshot",
        JSONObject().put("revision", revision).put("splitSupported", true).put("windows", JSONArray(windows.toList())))

    @Test fun splitPickerKeepsGroupAndPinBoundariesAndRecognizesSignedSplitIds() {
        val win = window(1, true, tab(10, true, group = 8), tab(11, false, group = 8),
            tab(12, false), tab(13, false, group = 8, split = -23), tab(14, false, group = 8).put("pinned", true))
        val catalog = readChromeWindows(catalogue("a", win).getJSONObject("snapshot")) { 1 }
        val first = catalog.windows.single().tabs.first()
        assertEquals(listOf(11), catalog.windows.single().tabs.filter(first::canPairWith).map { it.id })
        assertEquals(1 to 10, catalog.focus)
    }

    @Test fun staleConfirmationNeverSendsActionOrSilentlyAddsNewWindow() = runTest {
        var reply = catalogue("first", window(1, true, tab(10, true)), window(3, false, tab(12, true)))
        val actions = mutableListOf<JSONObject>()
        val manager = BrowserWindowManager {}
        val observation = backgroundScope.launch {
            manager.observe(this) { action, _ -> action?.let(actions::add); reply }
        }
        runCurrent()
        manager.open()
        runCurrent()
        val frozen = WindowAction(WindowOperation.MERGE, manager.state.value.catalog!!.revision, targetWindowId = 1)
        reply = catalogue("next", window(3, false, tab(12, true)), window(5, true, tab(15, true)))
        manager.reload()
        runCurrent()
        manager.perform(frozen)
        runCurrent()
        assertEquals(emptyList<JSONObject>(), actions)
        assertEquals(listOf("Window 2", "Window 3"), manager.state.value.catalog!!.windows.map { it.name })
        assertNotNull(manager.state.value.actionError)
        observation.cancelAndJoin()
        assertEquals(BrowserWindowsState(), manager.state.value)
    }

    @Test fun doubleTapProducesOneMutationAndPartialReplyKeepsActualLayout() = runTest {
        val manager = BrowserWindowManager {}
        val pending = CompletableDeferred<JSONObject>()
        val existing = catalogue("old", window(1, true, tab(10, true)), window(2, false, tab(11, true), tab(12, false)))
        val sent = mutableListOf<JSONObject>()
        backgroundScope.launch {
            manager.observe(this) { action, _ ->
                if (action == null) existing else { sent.add(action); pending.await() }
            }
        }
        runCurrent()
        manager.open()
        runCurrent()
        val action = WindowAction(WindowOperation.MERGE, "old", targetWindowId = 1)
        manager.perform(action)
        manager.perform(action)
        runCurrent()
        assertTrue(manager.state.value.pending)
        assertEquals("merge", sent.single().getString("action"))
        assertEquals(1, sent.single().getInt("targetWindowId"))
        assertFalse(sent.single().has("windowId"))
        assertEquals(36, sent.single().getString("requestId").length)
        pending.complete(catalogue("new", window(1, true, tab(10, true), tab(11, false)), window(2, false, tab(12, true)))
            .put("ok", false).put("code", "WINDOW_OPERATION_PARTIAL").put("moved", 1).put("total", 2))
        runCurrent()
        assertEquals("new", manager.state.value.catalog!!.revision)
        assertTrue(manager.state.value.visible)
        assertFalse(manager.state.value.pending)
        assertTrue(manager.state.value.actionError!!.startsWith("1 of 2 tabs moved"))
    }

    @Test fun updatedFocusAndLostControllerInvalidatePendingVoiceAndTitles() = runTest {
        var changes = 0
        val manager = BrowserWindowManager { changes++ }
        var answer = catalogue("a", window(1, true, tab(10, true), tab(11, false)))
        var revoked = false
        backgroundScope.launch { manager.observe(this) { _, _ ->
            if (revoked) throw BrowserFailure("CONTROLLER_CHANGED")
            answer
        } }
        runCurrent()
        manager.reload()
        runCurrent()
        assertEquals(0, changes)
        answer = catalogue("b", window(1, true, tab(10, false), tab(11, true)))
        assertEquals(1 to 11, manager.freshFocus())
        assertEquals(1, changes)
        revoked = true
        assertNull(manager.freshFocus())
        assertEquals(2, changes)
        assertNull(manager.state.value.catalog)
        assertNotNull(manager.state.value.readError)
    }

    @Test fun onlyAnExplicitOpenOrRefreshCanStartProfilePairing() = runTest {
        val flags = mutableListOf<Boolean>()
        val manager = BrowserWindowManager {}
        val current = catalogue("r", window(1, true, tab(1, true)))
        backgroundScope.launch { manager.observe(this) { _, allowed -> flags.add(allowed); current } }
        runCurrent()
        manager.open()
        runCurrent()
        manager.freshFocus()
        manager.reload()
        runCurrent()
        manager.perform(WindowAction(WindowOperation.FOCUS, "r", windowId = 1))
        runCurrent()
        assertEquals(listOf(false, true, false, true, false), flags)
    }

    @Test fun disconnectWhileMovingDoesNotApplyLateReplyToNewViewer() = runTest {
        val manager = BrowserWindowManager {}
        val old = catalogue("old", window(1, true, tab(10, true)))
        val pending = CompletableDeferred<JSONObject>()
        val connection = backgroundScope.launch { manager.observe(this) { action, _ ->
            if (action == null) old else pending.await()
        } }
        runCurrent()
        manager.open()
        runCurrent()
        manager.perform(WindowAction(WindowOperation.FOCUS, "old", 1, 10))
        runCurrent()
        assertTrue(manager.state.value.pending)
        connection.cancelAndJoin()
        val new = catalogue("new", window(4, true, tab(20, true)))
        backgroundScope.launch { manager.observe(this) { _, _ -> new } }
        runCurrent()
        pending.complete(old)
        runCurrent()
        assertEquals("new", manager.state.value.catalog?.revision)
        assertFalse(manager.state.value.visible)
        assertFalse(manager.state.value.pending)
    }

    @Test fun eachTabActionSpecifiesItsScopeAndOnlyNecessaryTargets() {
        val pair = WindowAction(WindowOperation.PAIR, "rev", 2, 31, otherTabId = 32).toJson()
        assertEquals("pair", pair.getString("action"))
        assertEquals(2, pair.getInt("windowId"))
        assertEquals(31, pair.getInt("tabId"))
        assertEquals(32, pair.getInt("otherTabId"))
        assertFalse(pair.has("targetWindowId"))
        val move = WindowAction(WindowOperation.MOVE, "rev", 2, 31, targetWindowId = 3).toJson()
        assertEquals(3, move.getInt("targetWindowId"))
        assertFalse(move.has("otherTabId"))
    }
}
