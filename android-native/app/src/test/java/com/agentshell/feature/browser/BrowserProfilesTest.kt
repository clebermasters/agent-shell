package com.agentshell.feature.browser

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.repository.BrowserFailure
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class BrowserProfilesTest {
    @get:Rule val directory=TemporaryFolder()
    private val profile=BrowserProfile("computer:opaque-id", "Work – 😀", "computer", "running")
    private fun confirmation(id:String=profile.id,token:Any="server-confirmation")=JSONObject()
        .put("code","PROFILE_CONFIRMATION_REQUIRED").put("connected",false)
        .put("confirmation",JSONObject().put("profileId",id).put("token",token)
            .put("location","computer").put("closesAllWindows",true))

    @Test fun profileIdsRemainOpaqueAndFriendlyNamesStaySeparateFromLocation() {
        val rows=browserProfiles(JSONObject().put("profiles",JSONArray()
            .put(JSONObject().put("id",profile.id).put("name",profile.name).put("location","computer").put("status","running").put("selected",true))
            .put(JSONObject().put("id",profile.id).put("name","duplicate"))
            .put(JSONObject().put("id",JSONObject.NULL)).put(JSONObject().put("id",42))))
        assertEquals(listOf(profile.copy(selected=true)),rows)
        assertEquals("On computer",rows.single().locationLabel)
    }

    @Test fun authoritativeServerSelectionAlwaysWinsOverRememberedChoice() {
        val other=profile.copy(id="managed:other",name="Personal")
        val state=BrowserProfilesUiState(profiles=listOf(profile,other),selectedProfileId=other.id,rememberedProfileId=profile.id)
        assertEquals(other,state.current)
        assertEquals(other.id,state.highlightedId)
        assertEquals(profile.id,state.copy(selectedProfileId=null).highlightedId)
        assertNull(state.copy(selectedProfileId=null,rememberedProfileId="deleted").highlightedId)
    }

    @Test fun confirmationMustComeFromServerForTheExactRequestedProfile() {
        val pending=browserProfileConfirmation(confirmation(),profile)!!
        assertEquals(profile.id,pending.profileId)
        assertEquals(profile.name,pending.profileName)
        assertTrue(pending.closesAllWindows)
        assertNull(browserProfileConfirmation(confirmation(id="another-profile"),profile))
        assertNull(browserProfileConfirmation(confirmation(token=""),profile))
        assertNull(browserProfileConfirmation(confirmation(token=JSONObject.NULL),profile))
        assertNull(browserProfileConfirmation(confirmation().put("connected",true),profile))
        assertNull(browserProfileConfirmation(JSONObject().put("code","PROFILE_CONFIRMATION_REQUIRED"),profile))
    }

    @Test fun firstRequestNeverAuthorizesClosureAndConfirmationCannotBeReusedForAnotherProfile() {
        val pending=browserProfileConfirmation(confirmation(),profile)!!
        val first=browserProfileRequest(profile.id,"viewer","generation",null)
        assertFalse(first.has("confirmationToken"))
        assertFalse(first.has("confirmCloseNative"))
        assertEquals("viewer",first.getString("viewerId"))
        assertEquals("generation",first.getString("runtimeGeneration"))
        assertEquals("server-confirmation",browserProfileRequest(profile.id,"viewer","generation",pending).getString("confirmationToken"))
        assertFalse(browserProfileRequest("different","viewer",null,pending).has("confirmationToken"))
        assertFalse(browserProfileRequest(profile.id,"viewer",null,null).has("runtimeGeneration"))
    }

    @Test fun pickerDismissalAndConfirmationCancellationKeepAuthoritativeSelection() {
        val original=BrowserProfilesUiState(available=true,visible=true,profiles=listOf(profile),selectedProfileId=profile.id,
            confirmation=browserProfileConfirmation(confirmation(),profile))
        val cancelled=original.copy(confirmation=null)
        assertEquals(profile.id,cancelled.selectedProfileId)
        assertTrue(cancelled.visible)
        assertEquals(profile.id,cancelled.copy(visible=false).selectedProfileId)
    }

    @Test fun partialFailureStatesExactlyWhichBrowserWasClosedAndWhetherPhoneWasPreserved() {
        val kept=browserProfileFailureMessage(BrowserFailure("CHROME_CLOSE_BLOCKED",phonePreserved=true,computerClosed=true))
        assertTrue(kept.contains("Chrome on the computer was closed; the current phone browser is still open."))
        val unknown=browserProfileFailureMessage(BrowserFailure("PROFILE_OPEN_FAILED",computerClosed=true))
        assertTrue(unknown.contains("Chrome on the computer was closed."))
        assertFalse(unknown.contains("phone browser is still open"))
        assertFalse(browserProfileFailureMessage(BrowserFailure("CHROME_CLOSE_BLOCKED",phonePreserved=true)).contains("computer was closed"))
    }

    @Test fun rememberedProfileIsPersistedSeparatelyForEachServer()=runBlocking<Unit> {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        try {
            val store=PreferencesDataStore(PreferenceDataStoreFactory.create(scope=scope,
                produceFile={File(directory.root,"browser.preferences_pb")}))
            store.setBrowserProfileId("main",profile.id)
            store.setBrowserProfileId("homelab","managed:lab")
            assertEquals(profile.id,store.browserProfileId("main").first())
            assertEquals("managed:lab",store.browserProfileId("homelab").first())
            assertNull(store.browserProfileId("new-server").first())
            store.setBrowserProfileId("main","managed:personal")
            assertEquals("managed:lab",store.browserProfileId("homelab").first())
        } finally { scope.coroutineContext[Job]?.cancelAndJoin();dispatcher.close() }
    }
}
