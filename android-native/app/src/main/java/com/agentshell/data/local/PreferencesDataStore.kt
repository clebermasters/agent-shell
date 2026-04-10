package com.agentshell.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    // ─── themeMode ───────────────────────────────────────────────────────────

    val themeMode: Flow<String>
        get() = dataStore.data.map { it[Keys.THEME_MODE] ?: "system" }

    suspend fun setThemeMode(value: String) {
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

    // ─── recent sessions (swipe navigation) ───────────────────────────────────
    // Stored as pipe-delimited strings, max 3 entries each.

    private val recentTerminalKey = stringPreferencesKey("recent_terminal_sessions")
    private val recentChatKey = stringPreferencesKey("recent_chat_sessions")

    suspend fun getRecentTerminalSessions(): List<String> {
        val raw = dataStore.data.first()[recentTerminalKey] ?: return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    suspend fun pushRecentTerminalSession(sessionName: String) {
        dataStore.edit { prefs ->
            val current = (prefs[recentTerminalKey] ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()
            current.remove(sessionName)
            current.add(0, sessionName)
            prefs[recentTerminalKey] = current.take(3).joinToString("|")
        }
    }

    suspend fun getRecentChatSessions(): List<String> {
        val raw = dataStore.data.first()[recentChatKey] ?: return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    suspend fun pushRecentChatSession(key: String) {
        dataStore.edit { prefs ->
            val current = (prefs[recentChatKey] ?: "").split("|").filter { it.isNotEmpty() }.toMutableList()
            current.remove(key)
            current.add(0, key)
            prefs[recentChatKey] = current.take(3).joinToString("|")
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
