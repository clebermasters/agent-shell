package com.agentshell.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    // ─── Keys ────────────────────────────────────────────────────────────────

    private object Keys {
        val SELECTED_HOST_ID = stringPreferencesKey("selected_host_id")
        val IMPORTED_DEFAULT_HOSTS = stringSetPreferencesKey("imported_default_hosts")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TERMINAL_FONT_SIZE = floatPreferencesKey("terminal_font_size")
        val OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val VOICE_AUTO_ENTER = booleanPreferencesKey("voice_auto_enter")
        val SHOW_VOICE_BUTTON = booleanPreferencesKey("show_voice_button")
        val SHOW_THINKING = booleanPreferencesKey("show_thinking")
        val SHOW_TOOL_CALLS = booleanPreferencesKey("show_tool_calls")
        val WEB_AUTH_TOKEN = stringPreferencesKey("web_auth_token")
        val FILE_SORT_MODE = stringPreferencesKey("file_sort_mode")
        val FILE_SHOW_HIDDEN = booleanPreferencesKey("file_show_hidden")
        val FILE_EDITOR_FONT_SIZE = floatPreferencesKey("file_editor_font_size")
        val HOME_TAB_INDEX = intPreferencesKey("home_tab_index")
        val VOICE_BUTTON_POS_X = floatPreferencesKey("voice_button_pos_x")
        val VOICE_BUTTON_POS_Y = floatPreferencesKey("voice_button_pos_y")
        val LAST_SESSION_NAME = stringPreferencesKey("last_session_name")
        val AUTO_ATTACH_ENABLED = booleanPreferencesKey("auto_attach_enabled")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CHAT_NOTIFICATIONS_ENABLED = booleanPreferencesKey("chat_notifications_enabled")
        val CHAT_READ_STATES = stringPreferencesKey("chat_read_states")
        val AGENT_FINISHED_ENABLED = booleanPreferencesKey("agent_finished_enabled")
        val AGENT_QUIET_ENABLED = booleanPreferencesKey("agent_quiet_enabled")
        val AGENT_COMPLETION_RECEIPTS = stringPreferencesKey("agent_completion_receipts")
        val AGENT_SIGNAL_LEDGERS = stringPreferencesKey("agent_signal_ledgers")
    }

    // ─── selectedHostId ──────────────────────────────────────────────────────

    val selectedHostId: Flow<String?>
        get() = dataStore.data.map { it[Keys.SELECTED_HOST_ID] }

    suspend fun setSelectedHostId(value: String?) {
        dataStore.edit { prefs ->
            if (value != null) prefs[Keys.SELECTED_HOST_ID] = value
            else prefs.remove(Keys.SELECTED_HOST_ID)
        }
    }

    val importedDefaultHosts: Flow<Set<String>>
        get() = dataStore.data.map { it[Keys.IMPORTED_DEFAULT_HOSTS] ?: emptySet() }

    suspend fun markDefaultHostsImported(endpoints: Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.IMPORTED_DEFAULT_HOSTS] = (prefs[Keys.IMPORTED_DEFAULT_HOSTS] ?: emptySet()) + endpoints
        }
    }

    // ─── themeMode ───────────────────────────────────────────────────────────

    val themeMode: Flow<String>
        get() = dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun setThemeMode(value: String) {
        require(value in setOf("system", "light", "dark")) { "Invalid theme mode" }
        dataStore.edit { it[Keys.THEME_MODE] = value }
    }

    // ─── terminalFontSize ────────────────────────────────────────────────────

    val terminalFontSize: Flow<Float>
        get() = dataStore.data.map { it[Keys.TERMINAL_FONT_SIZE] ?: 14f }

    suspend fun setTerminalFontSize(value: Float) {
        dataStore.edit { it[Keys.TERMINAL_FONT_SIZE] = value }
    }

    // ─── openaiApiKey ────────────────────────────────────────────────────────

    val openaiApiKey: Flow<String>
        get() = dataStore.data.map { it[Keys.OPENAI_API_KEY] ?: com.agentshell.core.config.BuildConfig.DEFAULT_API_KEY }

    suspend fun setOpenaiApiKey(value: String) {
        dataStore.edit { it[Keys.OPENAI_API_KEY] = value }
    }

    // ─── voiceAutoEnter ──────────────────────────────────────────────────────

    val voiceAutoEnter: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.VOICE_AUTO_ENTER] ?: false }

    suspend fun setVoiceAutoEnter(value: Boolean) {
        dataStore.edit { it[Keys.VOICE_AUTO_ENTER] = value }
    }

    // ─── showVoiceButton ─────────────────────────────────────────────────────

    val showVoiceButton: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.SHOW_VOICE_BUTTON] ?: true }

    suspend fun setShowVoiceButton(value: Boolean) {
        dataStore.edit { it[Keys.SHOW_VOICE_BUTTON] = value }
    }

    // ─── showThinking ────────────────────────────────────────────────────────

    val showThinking: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.SHOW_THINKING] ?: com.agentshell.core.config.BuildConfig.DEFAULT_SHOW_THINKING }

    suspend fun setShowThinking(value: Boolean) {
        dataStore.edit { it[Keys.SHOW_THINKING] = value }
    }

    // ─── showToolCalls ───────────────────────────────────────────────────────

    val showToolCalls: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.SHOW_TOOL_CALLS] ?: com.agentshell.core.config.BuildConfig.DEFAULT_SHOW_TOOL_CALLS }

    suspend fun setShowToolCalls(value: Boolean) {
        dataStore.edit { it[Keys.SHOW_TOOL_CALLS] = value }
    }

    // ─── webAuthToken ────────────────────────────────────────────────────────

    val webAuthToken: Flow<String?>
        get() = dataStore.data.map { it[Keys.WEB_AUTH_TOKEN] }

    suspend fun setWebAuthToken(value: String?) {
        dataStore.edit { prefs ->
            if (value != null) prefs[Keys.WEB_AUTH_TOKEN] = value
            else prefs.remove(Keys.WEB_AUTH_TOKEN)
        }
    }

    // ─── fileSortMode ────────────────────────────────────────────────────────

    val fileSortMode: Flow<String>
        get() = dataStore.data.map { it[Keys.FILE_SORT_MODE] ?: "name" }

    suspend fun setFileSortMode(value: String) {
        dataStore.edit { it[Keys.FILE_SORT_MODE] = value }
    }

    // ─── fileShowHidden ──────────────────────────────────────────────────────

    val fileShowHidden: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.FILE_SHOW_HIDDEN] ?: false }

    suspend fun setFileShowHidden(value: Boolean) {
        dataStore.edit { it[Keys.FILE_SHOW_HIDDEN] = value }
    }

    // ─── fileEditorFontSize ──────────────────────────────────────────────────

    val fileEditorFontSize: Flow<Float>
        get() = dataStore.data.map { it[Keys.FILE_EDITOR_FONT_SIZE] ?: 14f }

    suspend fun setFileEditorFontSize(value: Float) {
        dataStore.edit { it[Keys.FILE_EDITOR_FONT_SIZE] = value }
    }

    // ─── homeTabIndex ────────────────────────────────────────────────────────

    val homeTabIndex: Flow<Int>
        get() = dataStore.data.map { it[Keys.HOME_TAB_INDEX] ?: 0 }

    suspend fun setHomeTabIndex(value: Int) {
        dataStore.edit { it[Keys.HOME_TAB_INDEX] = value }
    }

    // ─── voiceButtonPos ──────────────────────────────────────────────────

    val voiceButtonPosX: Flow<Float>
        get() = dataStore.data.map { it[Keys.VOICE_BUTTON_POS_X] ?: -1f }

    val voiceButtonPosY: Flow<Float>
        get() = dataStore.data.map { it[Keys.VOICE_BUTTON_POS_Y] ?: -1f }

    suspend fun setVoiceButtonPos(x: Float, y: Float) {
        dataStore.edit { prefs ->
            prefs[Keys.VOICE_BUTTON_POS_X] = x
            prefs[Keys.VOICE_BUTTON_POS_Y] = y
        }
    }

    // The backend owns the active browser profile. This remembered choice is only
    // a per-server picker fallback and never silently overrides a running browser.
    private fun browserProfileKey(hostId: String) = stringPreferencesKey("browser_profile_$hostId")

    fun browserProfileId(hostId: String): Flow<String?> = dataStore.data.map { it[browserProfileKey(hostId)] }

    suspend fun setBrowserProfileId(hostId: String, profileId: String) {
        require(hostId.isNotBlank() && profileId.isNotBlank())
        dataStore.edit { it[browserProfileKey(hostId)] = profileId }
    }

    // ─── recent sessions (swipe navigation) ───────────────────────────────────
    // Each server owns its history. Legacy unscoped entries cannot be safely
    // attributed to a server, so new visits populate these scoped keys.
    private fun recentTerminalKey(hostId: String) = stringPreferencesKey("recent_terminal_sessions_$hostId")
    private fun recentChatKey(hostId: String) = stringPreferencesKey("recent_chat_sessions_$hostId")
    private fun recentEntries(raw: String?) = raw.orEmpty().split("|").filter(String::isNotEmpty).distinct().take(3)

    fun recentTerminalSessions(hostId: String): Flow<List<String>> =
        dataStore.data.map { recentEntries(it[recentTerminalKey(hostId)]) }

    fun recentChatSessions(hostId: String): Flow<List<String>> =
        dataStore.data.map { recentEntries(it[recentChatKey(hostId)]) }

    suspend fun pushRecentTerminalSession(hostId: String, sessionName: String) {
        dataStore.edit { prefs ->
            val key = recentTerminalKey(hostId)
            prefs[key] = (listOf(sessionName) + recentEntries(prefs[key]).filterNot { it == sessionName })
                .take(3).joinToString("|")
        }
    }

    suspend fun pushRecentChatSession(hostId: String, sessionKey: String) {
        dataStore.edit { prefs ->
            val key = recentChatKey(hostId)
            prefs[key] = (listOf(sessionKey) + recentEntries(prefs[key]).filterNot { it == sessionKey })
                .take(3).joinToString("|")
        }
    }

    /** Null catalogs are unknown; an authoritative empty catalog removes all of its targets. */
    suspend fun pruneRecentSessions(hostId: String, tmux: Set<String>? = null, acp: Set<String>? = null) {
        dataStore.edit { prefs ->
            val terminalKey = recentTerminalKey(hostId)
            if (tmux != null) prefs[terminalKey] = recentEntries(prefs[terminalKey])
                .filter { it in tmux }.joinToString("|")
            val chatKey = recentChatKey(hostId)
            prefs[chatKey] = recentEntries(prefs[chatKey]).filter { key ->
                if (key.startsWith("acp:")) acp == null || key.removePrefix("acp:") in acp
                else tmux == null || key.removePrefix("tmux:").substringBefore('\u0000') in tmux
            }.joinToString("|")
        }
    }

    // ─── chat draft messages ─────────────────────────────────────────────────
    // Keyed by "chat_draft_{sessionName}_{windowIndex}"; stored as plain strings.

    suspend fun getDraftMessage(key: String): String? {
        return dataStore.data.first()[stringPreferencesKey(key)]
    }

    suspend fun setDraftMessage(key: String, text: String) {
        dataStore.edit { prefs ->
            if (text.isEmpty()) prefs.remove(stringPreferencesKey(key))
            else prefs[stringPreferencesKey(key)] = text
        }
    }

    // ─── last session / auto-attach ──────────────────────────────────────────

    val lastSessionName: Flow<String?>
        get() = dataStore.data.map { it[Keys.LAST_SESSION_NAME] }

    suspend fun setLastSessionName(name: String?) {
        dataStore.edit { prefs ->
            if (name != null) prefs[Keys.LAST_SESSION_NAME] = name
            else prefs.remove(Keys.LAST_SESSION_NAME)
        }
    }

    val autoAttachEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.AUTO_ATTACH_ENABLED] ?: false }

    suspend fun setAutoAttachEnabled(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_ATTACH_ENABLED] = value }
    }

    // ─── screen timeout ─────────────────────────────────────────────────────

    val keepScreenOn: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.KEEP_SCREEN_ON] ?: false }

    suspend fun setKeepScreenOn(value: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }
    }

    val chatNotificationsEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[Keys.CHAT_NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setChatNotificationsEnabled(value: Boolean) {
        dataStore.edit { it[Keys.CHAT_NOTIFICATIONS_ENABLED] = value }
    }

    val agentFinishedEnabled: Flow<Boolean> get() = dataStore.data.map { it[Keys.AGENT_FINISHED_ENABLED] ?: true }
    val agentQuietEnabled: Flow<Boolean> get() = dataStore.data.map { it[Keys.AGENT_QUIET_ENABLED] ?: true }
    val agentCompletionReceipts: Flow<String> get() = dataStore.data.map { it[Keys.AGENT_COMPLETION_RECEIPTS] ?: "{}" }
    suspend fun setAgentCompletionReceipts(value: String) { dataStore.edit { it[Keys.AGENT_COMPLETION_RECEIPTS] = value } }

    val agentSignalLedgers: Flow<String> get() = dataStore.data.map { it[Keys.AGENT_SIGNAL_LEDGERS] ?: "{}" }
    suspend fun setAgentFinishedEnabled(value: Boolean) { dataStore.edit { it[Keys.AGENT_FINISHED_ENABLED] = value } }
    suspend fun setAgentQuietEnabled(value: Boolean) { dataStore.edit { it[Keys.AGENT_QUIET_ENABLED] = value } }
    suspend fun setAgentSignalLedgers(value: String) { dataStore.edit { it[Keys.AGENT_SIGNAL_LEDGERS] = value } }

    val chatReadStates: Flow<String>
        get() = dataStore.data.map { it[Keys.CHAT_READ_STATES] ?: "{}" }

    suspend fun setChatReadStates(value: String) {
        dataStore.edit { it[Keys.CHAT_READ_STATES] = value }
    }

    // ─── audio playback position ──────────────────────────────────────────────

    suspend fun getAudioPosition(audioId: String): Long {
        val key = longPreferencesKey("audio_pos_$audioId")
        return dataStore.data.first()[key] ?: 0L
    }

    suspend fun setAudioPosition(audioId: String, positionMs: Long) {
        val key = longPreferencesKey("audio_pos_$audioId")
        dataStore.edit { prefs ->
            if (positionMs <= 0L) prefs.remove(key) else prefs[key] = positionMs
        }
    }
}
