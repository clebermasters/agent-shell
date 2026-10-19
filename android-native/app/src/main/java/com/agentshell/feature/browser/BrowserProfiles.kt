package com.agentshell.feature.browser

import org.json.JSONObject
import com.agentshell.data.repository.BrowserFailure

/** Profile IDs belong to the server: never interpret them as file names or paths. */
data class BrowserProfile(val id: String, val name: String, val location: String, val status: String,
    val selected: Boolean = false, val source: String? = null) {
    val sourceLabel: String get() = source ?: "Chrome"
    val locationLabel: String get() = when(location) { "computer" -> "On computer"; "phone" -> "On phone"; "closed" -> "Closed"; "unavailable" -> "Unavailable"; else -> "" }
    val statusLabel: String get() = when(status) {
        "running", "open", "ready", "native" -> "Open"
        "stopped", "closed", "available" -> "Available"
        "in-use", "in_use", "locked" -> "In use"
        else -> status.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.titlecase() }
    }
}

data class BrowserProfileConfirmation(val profileId: String, val profileName: String, val token: String,
    val location: String, val closesAllWindows: Boolean)

data class BrowserProfilesUiState(val available: Boolean = false, val visible: Boolean = false,
    val loading: Boolean = false, val switching: Boolean = false, val profiles: List<BrowserProfile> = emptyList(),
    val selectedProfileId: String? = null, val rememberedProfileId: String? = null,
    val confirmation: BrowserProfileConfirmation? = null, val error: String? = null) {
    val current: BrowserProfile? get() = profiles.firstOrNull { it.id == selectedProfileId }
    val highlightedId: String? get() = selectedProfileId ?: rememberedProfileId?.takeIf { remembered -> profiles.any { it.id == remembered } }
}

private fun JSONObject.profileString(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }

internal fun browserProfiles(data: JSONObject): List<BrowserProfile> {
    val rows = data.optJSONArray("profiles") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.profileString("id") ?: return@mapNotNull null
        BrowserProfile(id, row.profileString("name") ?: "Chrome profile",
            row.optString("location"), row.optString("status"), row.optBoolean("selected"), row.profileString("source"))
    }.distinctBy { it.id }
}

internal fun browserProfileConfirmation(data: JSONObject, requested: BrowserProfile): BrowserProfileConfirmation? {
    if(data.optBoolean("connected") || data.optString("code") != "PROFILE_CONFIRMATION_REQUIRED") return null
    val confirmation = data.optJSONObject("confirmation") ?: return null
    val id = confirmation.profileString("profileId")
    val token = confirmation.profileString("token") ?: return null
    if(id != requested.id) return null
    return BrowserProfileConfirmation(id, requested.name, token, confirmation.optString("location"),
        confirmation.optBoolean("closesAllWindows", true))
}

internal fun browserProfileRequest(profileId: String, viewerId: String, generation: String?, confirmation: BrowserProfileConfirmation?): JSONObject =
    JSONObject().put("profileId", profileId).put("viewerId", viewerId).apply {
        generation?.let { put("runtimeGeneration", it) }
        // Never attach a token to a different target, including a stale UI choice.
        confirmation?.takeIf { it.profileId == profileId }?.let { put("confirmationToken", it.token) }
    }

internal fun browserProfileFailureMessage(failure: BrowserFailure?): String =
    browserError(failure?.code ?: "CONNECTION_FAILED") + when {
        failure?.computerClosed == true && failure.phonePreserved -> " Chrome on the computer was closed; the current phone browser is still open."
        failure?.computerClosed == true -> " Chrome on the computer was closed. Reconnect to check the phone browser."
        else -> ""
    }
