package com.agentshell.feature.splitscreen.model

import com.agentshell.data.model.AcpSession
import com.agentshell.data.model.PanelLayoutWithPanels
import com.agentshell.data.model.TmuxSession

data class PanelState(
    val id: String,
    val position: Int,
    val panelType: PanelType,
    val sessionName: String,
    val windowIndex: Int = 0,
    val isAcp: Boolean = false,
)

data class SplitScreenUiState(
    val layoutId: String? = null,
    val layoutName: String = "",
    val panels: List<PanelState> = emptyList(),
    val focusedPanelId: String? = null,
    val maximizedPanelId: String? = null,
    val availableLayouts: List<PanelLayoutWithPanels> = emptyList(),
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val showSessionPicker: Boolean = false,
    val pickerTargetPanelId: String? = null,
    val showLayoutList: Boolean = false,
    val showLayoutEditor: Boolean = false,
    val tmuxSessions: List<TmuxSession> = emptyList(),
    val acpSessions: List<AcpSession> = emptyList(),
)
