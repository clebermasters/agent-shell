package com.agentshell.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.agentshell.core.config.AppConfig
import com.agentshell.data.local.PreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── SettingsViewModel ────────────────────────────────────────────────────────

data class SettingsUiState(
    val openaiApiKey: String = "",
    val terminalFontSize: Float = AppConfig.DEFAULT_FONT_SIZE,
    val showVoiceButton: Boolean = true,
    val voiceAutoEnter: Boolean = false,
    val showThinking: Boolean = false,
    val showToolCalls: Boolean = false,
    val autoAttachEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val savedMessage: String? = null,
    val appVersion: String = "1.0.0",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: PreferencesDataStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                dataStore.openaiApiKey,
                dataStore.terminalFontSize,
                dataStore.showVoiceButton,
                dataStore.voiceAutoEnter,
                dataStore.showThinking,
            ) { apiKey, fontSize, showVoice, voiceAutoEnter, showThinking ->
                Triple(apiKey, fontSize, Triple(showVoice, voiceAutoEnter, showThinking))
            }.collect { (apiKey, fontSize, bools) ->
                val (showVoice, voiceAutoEnter, showThinking) = bools
                _state.update { it.copy(openaiApiKey = apiKey, terminalFontSize = fontSize, showVoiceButton = showVoice, voiceAutoEnter = voiceAutoEnter, showThinking = showThinking) }
            }
        }
        viewModelScope.launch {
            dataStore.showToolCalls.collect { v -> _state.update { it.copy(showToolCalls = v) } }
        }
        viewModelScope.launch {
            dataStore.autoAttachEnabled.collect { v -> _state.update { it.copy(autoAttachEnabled = v) } }
        }
    }

    fun setApiKey(key: String) { _state.update { it.copy(openaiApiKey = key) } }
    fun setTerminalFontSize(size: Float) { _state.update { it.copy(terminalFontSize = size) } }

    fun saveApiKey() {
        val key = _state.value.openaiApiKey.trim()
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            dataStore.setOpenaiApiKey(key)
            _state.update { it.copy(isSaving = false, savedMessage = "API key saved successfully") }
            kotlinx.coroutines.delay(2000)
            _state.update { it.copy(savedMessage = null) }
        }
    }

    fun saveTerminalFontSize(size: Float) {
        viewModelScope.launch { dataStore.setTerminalFontSize(size) }
        _state.update { it.copy(terminalFontSize = size) }
    }

    fun toggleVoiceButton(enabled: Boolean) {
        viewModelScope.launch { dataStore.setShowVoiceButton(enabled) }
        _state.update { it.copy(showVoiceButton = enabled) }
    }

    fun toggleVoiceAutoEnter(enabled: Boolean) {
        viewModelScope.launch { dataStore.setVoiceAutoEnter(enabled) }
        _state.update { it.copy(voiceAutoEnter = enabled) }
    }

    fun toggleShowThinking(enabled: Boolean) {
        viewModelScope.launch { dataStore.setShowThinking(enabled) }
        _state.update { it.copy(showThinking = enabled) }
    }

    fun toggleShowToolCalls(enabled: Boolean) {
        viewModelScope.launch { dataStore.setShowToolCalls(enabled) }
        _state.update { it.copy(showToolCalls = enabled) }
    }

    fun toggleAutoAttach(enabled: Boolean) {
        viewModelScope.launch { dataStore.setAutoAttachEnabled(enabled) }
        _state.update { it.copy(autoAttachEnabled = enabled) }
    }
}

// ─── SettingsScreen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToHostSelection: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var obscureApiKey by remember { mutableStateOf(true) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── Voice Input ───────────────────────────────────────────────────
            SectionHeader("Voice Input (Whisper)")

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("OpenAI API Key", style = MaterialTheme.typography.labelLarge)
                    Text("Required for voice input feature (Whisper)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    OutlinedTextField(
                        value = state.openaiApiKey,
                        onValueChange = { viewModel.setApiKey(it) },
                        placeholder = { Text("sk-...") },
                        visualTransformation = if (obscureApiKey) PasswordVisualTransformation() else VisualTransformation.None,
                        trailingIcon = {
                            IconButton(onClick = { obscureApiKey = !obscureApiKey }) {
                                Icon(
                                    if (obscureApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-enter transcriptions", style = MaterialTheme.typography.bodyMedium)
                            Text("Sends voice transcription immediately", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.voiceAutoEnter, onCheckedChange = { viewModel.toggleVoiceAutoEnter(it) })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Show voice button", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = state.showVoiceButton, onCheckedChange = { viewModel.toggleVoiceButton(it) })
                    }

                    Button(
                        onClick = { viewModel.saveApiKey() },
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save API Key")
                    }

                    state.savedMessage?.let {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }

            // ── Terminal ──────────────────────────────────────────────────────
            SectionHeader("Terminal")

            Card {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Font size: ${state.terminalFontSize.toInt()}sp")
                        Text("${AppConfig.MIN_FONT_SIZE.toInt()}–${AppConfig.MAX_FONT_SIZE.toInt()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Slider(
                        value = state.terminalFontSize,
                        onValueChange = { viewModel.saveTerminalFontSize(it) },
                        valueRange = AppConfig.MIN_FONT_SIZE..AppConfig.MAX_FONT_SIZE,
                        steps = ((AppConfig.MAX_FONT_SIZE - AppConfig.MIN_FONT_SIZE) / AppConfig.FONT_SIZE_STEP - 1).toInt(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-attach on open", style = MaterialTheme.typography.bodyMedium)
                            Text("Resume last session when app opens", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoAttachEnabled, onCheckedChange = { viewModel.toggleAutoAttach(it) })
                    }
                }
            }

            // ── Chat Display ─────────────────────────────────────────────────
            SectionHeader("Chat Display")

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Control how AI agent activity appears in chat", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show AI Thinking", style = MaterialTheme.typography.bodyMedium)
                            Text("Display AI reasoning in chat", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.showThinking, onCheckedChange = { viewModel.toggleShowThinking(it) })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Tool Calls", style = MaterialTheme.typography.bodyMedium)
                            Text("Display Read, Edit, Bash tool calls", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.showToolCalls, onCheckedChange = { viewModel.toggleShowToolCalls(it) })
                    }
                }
            }

            // ── Connection ───────────────────────────────────────────────────
            SectionHeader("Connection")

            OutlinedButton(
                onClick = onNavigateToHostSelection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Dns, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Host Selection")
            }

            BatteryOptimizationCard()

            // ── About ─────────────────────────────────────────────────────────
            SectionHeader("About")

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("AgentShell", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Version ${state.appVersion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Connect to your remote tmux sessions, manage dotfiles, and interact with AI agents from anywhere.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
    )
}

@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Background Reliability", style = MaterialTheme.typography.titleSmall)
            Text(
                if (ignoringBatteryOptimizations) {
                    "Battery restrictions are disabled for AgentShell. Background reconnects are less likely to be paused when the screen is off."
                } else {
                    "Battery restrictions can pause reconnects and delay chat refresh after the screen turns off. Allow unrestricted battery if you want the chat to stay more reliable in the background."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        if (ignoringBatteryOptimizations) "Status: unrestricted"
                        else "Status: battery optimized"
                    )
                },
                leadingIcon = {
                    Icon(
                        if (ignoringBatteryOptimizations) Icons.Default.CheckCircle else Icons.Default.BatterySaver,
                        contentDescription = null,
                    )
                },
            )

            if (!ignoringBatteryOptimizations) {
                Button(
                    onClick = {
                        launchSettingsIntent(
                            context,
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Allow Unrestricted Battery")
                }
            }

            OutlinedButton(
                onClick = {
                    launchSettingsIntent(
                        context,
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open Battery Settings")
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
}

private fun launchSettingsIntent(context: Context, intent: Intent) {
    val launchIntent = intent.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(launchIntent)
    }
}
