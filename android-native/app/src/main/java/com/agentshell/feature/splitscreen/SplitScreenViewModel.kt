package com.agentshell.feature.splitscreen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.PanelEntity
import com.agentshell.data.model.PanelLayoutEntity
import com.agentshell.data.model.TmuxSession
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.acpListSessions
import com.agentshell.data.remote.requestSessions
import com.agentshell.data.repository.PanelLayoutRepository
import com.agentshell.data.services.TerminalService
import com.agentshell.feature.splitscreen.model.PanelState
import com.agentshell.feature.splitscreen.model.PanelType
import com.agentshell.feature.splitscreen.model.SplitScreenUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SplitScreenViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val layoutRepository: PanelLayoutRepository,
    private val terminalService: TerminalService,
    private val webSocketService: WebSocketService,
) : ViewModel() {

    private val _state = MutableStateFlow(SplitScreenUiState())
    val state: StateFlow<SplitScreenUiState> = _state.asStateFlow()

    init {
        observeLayouts()
        observeSessions()
        loadInitialLayout()
    }

    // ── Layout loading ──────────────────────────────────────────────────

    private fun loadInitialLayout() {
        val layoutId = savedStateHandle.get<String>("layoutId")
        if (layoutId != null) {
            loadLayout(layoutId)
        } else {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun loadLayout(layoutId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val layout = layoutRepository.getLayout(layoutId)
            if (layout != null) {
                layoutRepository.markUsed(layoutId)
                val panels = layout.panels.sortedBy { it.position }.map { entity ->
                    PanelState(
                        id = entity.id,
                        position = entity.position,
                        panelType = PanelType.fromValue(entity.panelType),
                        sessionName = entity.sessionName,
                        windowIndex = entity.windowIndex,
                        isAcp = entity.isAcp,
                    )
                }
                _state.update {
                    it.copy(
                        layoutId = layoutId,
                        layoutName = layout.layout.name,
                        panels = panels,
                        isLoading = false,
                        focusedPanelId = panels.firstOrNull()?.id,
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // ── Layout persistence ──────────────────────────────────────────────

    fun saveLayout(name: String) {
        viewModelScope.launch {
            val s = _state.value
            val layoutId = s.layoutId ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val layoutEntity = PanelLayoutEntity(
                id = layoutId,
                name = name,
                panelCount = s.panels.size,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
            )
            val panelEntities = s.panels.map { panel ->
                PanelEntity(
                    id = panel.id,
                    layoutId = layoutId,
                    position = panel.position,
                    panelType = panel.panelType.value,
                    sessionName = panel.sessionName,
                    windowIndex = panel.windowIndex,
                    isAcp = panel.isAcp,
                )
            }
            layoutRepository.saveLayout(layoutEntity, panelEntities)
            _state.update { it.copy(layoutId = layoutId, layoutName = name, showLayoutEditor = false) }
        }
    }

    fun deleteLayout(layoutId: String) {
        viewModelScope.launch {
            layoutRepository.deleteLayout(layoutId)
            val s = _state.value
            if (s.layoutId == layoutId) {
                _state.update { it.copy(layoutId = null, layoutName = "", panels = emptyList()) }
            }
        }
    }

    // ── Panel management ────────────────────────────────────────────────

    fun addPanel(panelType: PanelType, sessionName: String, windowIndex: Int = 0, isAcp: Boolean = false) {
        _state.update { s ->
            val newPanel = PanelState(
                id = UUID.randomUUID().toString(),
                position = s.panels.size,
                panelType = panelType,
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = isAcp,
            )
            val focused = s.focusedPanelId ?: newPanel.id
            s.copy(panels = s.panels + newPanel, focusedPanelId = focused)
        }
    }

    fun removePanel(panelId: String) {
        terminalService.detachKeyed(panelId)
        _state.update { s ->
            val updated = s.panels.filter { it.id != panelId }
                .mapIndexed { i, p -> p.copy(position = i) }
            val focused = if (s.focusedPanelId == panelId) updated.firstOrNull()?.id else s.focusedPanelId
            val maximized = if (s.maximizedPanelId == panelId) null else s.maximizedPanelId
            s.copy(panels = updated, focusedPanelId = focused, maximizedPanelId = maximized)
        }
    }

    fun changePanelSession(panelId: String, panelType: PanelType, sessionName: String, windowIndex: Int = 0, isAcp: Boolean = false) {
        terminalService.detachKeyed(panelId)
        _state.update { s ->
            s.copy(
                panels = s.panels.map { p ->
                    if (p.id == panelId) p.copy(
                        panelType = panelType,
                        sessionName = sessionName,
                        windowIndex = windowIndex,
                        isAcp = isAcp,
                    ) else p
                },
                showSessionPicker = false,
                pickerTargetPanelId = null,
            )
        }
    }

    fun swapPanels(fromPosition: Int, toPosition: Int) {
        _state.update { s ->
            val mutablePanels = s.panels.toMutableList()
            val fromIdx = mutablePanels.indexOfFirst { it.position == fromPosition }
            val toIdx = mutablePanels.indexOfFirst { it.position == toPosition }
            if (fromIdx < 0 || toIdx < 0) return@update s
            mutablePanels[fromIdx] = mutablePanels[fromIdx].copy(position = toPosition)
            mutablePanels[toIdx] = mutablePanels[toIdx].copy(position = fromPosition)
            s.copy(panels = mutablePanels.sortedBy { it.position })
        }
    }

    // ── Focus & maximize ────────────────────────────────────────────────

    fun setFocusedPanel(panelId: String) {
        _state.update { it.copy(focusedPanelId = panelId) }
    }

    fun toggleMaximize(panelId: String) {
        _state.update { s ->
            if (s.maximizedPanelId == panelId) {
                s.copy(maximizedPanelId = null)
            } else {
                s.copy(maximizedPanelId = panelId)
            }
        }
    }

    fun restoreFromMaximize() {
        _state.update { it.copy(maximizedPanelId = null) }
    }

    // ── Session picker ──────────────────────────────────────────────────

    fun openSessionPicker(targetPanelId: String) {
        webSocketService.requestSessions()
        webSocketService.acpListSessions()
        _state.update { it.copy(showSessionPicker = true, pickerTargetPanelId = targetPanelId) }
    }

    fun closeSessionPicker() {
        _state.update { it.copy(showSessionPicker = false, pickerTargetPanelId = null) }
    }

    // ── Layout editor / list ────────────────────────────────────────────

    fun openLayoutEditor() {
        _state.update { it.copy(showLayoutEditor = true) }
    }

    fun closeLayoutEditor() {
        _state.update { it.copy(showLayoutEditor = false) }
    }

    fun openLayoutList() {
        _state.update { it.copy(showLayoutList = true) }
    }

    fun closeLayoutList() {
        _state.update { it.copy(showLayoutList = false) }
    }

    fun toggleEditing() {
        _state.update { it.copy(isEditing = !it.isEditing) }
    }

    // ── New layout from scratch ─────────────────────────────────────────

    fun createEmptyLayout(panelCount: Int) {
        val panels = (0 until panelCount).map { i ->
            PanelState(
                id = UUID.randomUUID().toString(),
                position = i,
                panelType = PanelType.TERMINAL,
                sessionName = "",
            )
        }
        _state.update {
            it.copy(
                layoutId = null,
                layoutName = "",
                panels = panels,
                focusedPanelId = panels.firstOrNull()?.id,
                maximizedPanelId = null,
                showLayoutEditor = false,
                isEditing = true,
            )
        }
    }

    // ── Observers ───────────────────────────────────────────────────────

    private fun observeLayouts() {
        viewModelScope.launch {
            layoutRepository.allLayouts.collect { layouts ->
                _state.update { it.copy(availableLayouts = layouts) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun observeSessions() {
        viewModelScope.launch {
            webSocketService.messages.collect { message ->
                when (message["type"] as? String) {
                    "sessions-list", "session_list" -> {
                        val raw = message["sessions"] as? List<*> ?: return@collect
                        val sessions = raw.mapNotNull { parseTmuxSession(it as? Map<String, Any?>) }
                        _state.update { it.copy(tmuxSessions = sessions) }
                    }
                    "acp-sessions-listed" -> {
                        val raw = message["sessions"] as? List<*> ?: return@collect
                        val sessions = raw.mapNotNull { parseAcpSession(it as? Map<String, Any?>) }
                        _state.update { it.copy(acpSessions = sessions) }
                    }
                }
            }
        }
    }

    private fun parseTmuxSession(map: Map<String, Any?>?): TmuxSession? {
        val m = map ?: return null
        return TmuxSession(
            name = m["name"] as? String ?: return null,
            attached = m["attached"] as? Boolean ?: false,
            windows = (m["windows"] as? Number)?.toInt() ?: 1,
            created = m["created"] as? String,
        )
    }

    private fun parseAcpSession(map: Map<String, Any?>?): AcpSession? {
        val m = map ?: return null
        return AcpSession(
            sessionId = m["sessionId"] as? String ?: return null,
            cwd = m["cwd"] as? String ?: "",
            title = m["title"] as? String ?: "",
            updatedAt = m["updatedAt"] as? String ?: "",
            provider = m["provider"] as? String,
        )
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        terminalService.detachAllKeyed()
    }
}
