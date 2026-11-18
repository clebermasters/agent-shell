package com.agentshell.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.agentshell.data.model.ChatTarget
import com.agentshell.feature.browser.BrowserScreen
import com.agentshell.feature.chat.ChatScreen
import com.agentshell.feature.chat.ChatViewModel
import com.agentshell.feature.chat.DeploymentWorkspace
import com.agentshell.feature.deployment.DeploymentViewModel
import java.io.IOException

internal data class DeploymentChatRoute(
    val sessionName: String,
    val windowIndex: Int,
    val isAcp: Boolean,
    val cwd: String,
    val isSwipe: Boolean,
)

/** Keep the original destination and its live pane in place when the user adds or removes the second pane. */
@Composable
internal fun DeploymentDestination(
    nav: NavHostController,
    navigation: NavigationViewModel,
    deployment: DeploymentViewModel,
    chat: DeploymentChatRoute? = null,
) {
    val preferences by deployment.state.collectAsStateWithLifecycle()
    val hostId = preferences?.hostId
    val routeChat = remember(chat, hostId) {
        chat?.let { ChatTarget.create(hostId.orEmpty(), it.sessionName, it.windowIndex, it.isAcp, it.cwd) }
    }
    val paneModels = if (chat == null) viewModel<DeploymentPaneModels>() else null
    var verifiedChat by remember(hostId) {
        mutableStateOf(paneModels?.verifiedChat?.takeIf { it.hostId == hostId })
    }
    var chooseChat by rememberSaveable(hostId) { mutableStateOf(false) }
    var dismissedRestore by rememberSaveable(hostId) { mutableStateOf(false) }
    val target = routeChat ?: verifiedChat
    val storedChat = preferences?.chat
    val layout = preferences?.layout
    val paired = layout?.enabled == true && target != null && target.key == storedChat?.key
    val restoreBrowser = chat == null && layout?.enabled == true && verifiedChat == null && !dismissedRestore

    LaunchedEffect(layout?.enabled) {
        if (layout?.enabled == false) paneModels?.release()
    }

    val openDeployment: (() -> Unit)? = if (hostId == null) null else {
        {
            if (routeChat != null) deployment.openChat(routeChat)
            else chooseChat = true
        }
    }
    // Moving a pane keeps its WebView, effects, chat list position and input in the same composition.
    val latestOpen by rememberUpdatedState(openDeployment)
    val latestTarget by rememberUpdatedState(target)
    val chatPane = remember(nav, chat) {
        movableContentOf<Boolean> { embedded ->
            val current = latestTarget
            if (current != null) {
                val chatModel: ChatViewModel = if (paneModels == null) hiltViewModel() else {
                    val parent = checkNotNull(LocalViewModelStoreOwner.current) as HasDefaultViewModelProviderFactory
                    val owner = remember(parent, current.key) { paneModels.owner(parent, current.key) }
                    hiltViewModel<ChatViewModel>(owner, key = "deployment-chat-${current.key}").also { model ->
                        SideEffect {
                            paneModels.activeChat = model
                            paneModels.verifiedChat = current
                        }
                    }
                }
                ChatScreen(
                    sessionName = current.sessionName,
                    windowIndex = current.windowIndex,
                    isAcp = current.isAcp,
                    cwd = current.cwd,
                    isSwipeNavigation = chat?.isSwipe == true,
                    onNavigateBack = { nav.popBackStack() },
                    onSwitchToTerminal = { name ->
                        nav.navigate(Routes.terminal(name, windowIndex = current.windowIndex)) { popUpTo(Routes.HOME) }
                    },
                    onNavigateToFileBrowser = { nav.navigate(Routes.fileBrowser(it)) },
                    onOpenServerPath = { nav.navigate(Routes.fileBrowser(path = "/", openPath = it)) },
                    onSwipeToChatSession = { name, index, direct ->
                        nav.navigate(Routes.chat(name, index, isAcp = direct, isSwipeNav = true)) { popUpTo(Routes.HOME) }
                    },
                    viewModel = chatModel,
                    onOpenDeployment = latestOpen,
                    embedded = embedded,
                )
            }
        }
    }
    val browserPane = remember(nav) {
        movableContentOf<Boolean> { embedded ->
            BrowserScreen(onBack = { nav.popBackStack() }, onOpenDeployment = latestOpen, embedded = embedded)
        }
    }

    if (paired && layout != null) {
        DeploymentWorkspace(
            preferences = layout,
            onOrientation = deployment::setOrientation,
            onResize = deployment::resize,
            onSwap = deployment::swapPanes,
            onReset = deployment::resetSizes,
            onBack = { nav.popBackStack() },
            onExit = { deployment.closeSplit() },
            chat = { chatPane(true) },
            browser = { browserPane(true) },
        )
    } else if (chat == null) {
        browserPane(false)
    } else {
        chatPane(false)
    }

    if ((chooseChat || restoreBrowser) && hostId != null) {
        DeploymentChatPicker(
            hostId, storedChat, navigation,
            onDismiss = {
                chooseChat = false
                dismissedRestore = true
            },
            onChoose = {
                verifiedChat = it
                chooseChat = false
                dismissedRestore = false
                if (it.key != storedChat?.key || layout?.enabled != true) deployment.openChat(it)
            },
        )
    }
}

/** A chat added from the browser has a shorter lifetime than its browser navigation entry. */
internal class DeploymentPaneModels : ViewModel() {
    private val store = ViewModelStore()
    private var targetKey: String? = null
    var activeChat: ChatViewModel? = null
    var verifiedChat: ChatTarget? = null

    fun owner(parent: HasDefaultViewModelProviderFactory, key: String): ViewModelStoreOwner {
        if (targetKey != null && targetKey != key) release()
        targetKey = key
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory by parent {
            override val viewModelStore = store
        }
    }

    fun release() {
        activeChat?.unwatchChatLog()
        activeChat = null
        store.clear()
        targetKey = null
        verifiedChat = null
    }

    override fun onCleared() = release()
}

@Composable
private fun DeploymentChatPicker(
    hostId: String,
    saved: ChatTarget?,
    navigation: NavigationViewModel,
    onDismiss: () -> Unit,
    onChoose: (ChatTarget) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val generation by navigation.chatConnectionGeneration.collectAsStateWithLifecycle()
    var choices by remember(hostId, attempt, generation) { mutableStateOf<DeploymentChatChoices?>(null) }
    val choose by rememberUpdatedState(onChoose)
    LaunchedEffect(hostId, attempt, generation) {
        val loaded = try {
            if (saved != null) navigation.findDeploymentChat(saved)?.let {
                choose(it)
                return@LaunchedEffect
            }
            navigation.deploymentChatChoices(hostId)
        } catch (_: IOException) {
            DeploymentChatChoices(incomplete = true)
        }
        val remembered = if (saved != null) loaded.chats.firstOrNull { it.key == saved.key } else loaded.lastVisited
        if (remembered != null) choose(remembered) else choices = loaded
    }
    val loaded = choices
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a chat") },
        text = {
            Column {
                when {
                    loaded == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    loaded.incomplete -> Text("Some chats could not be loaded. The list refreshes when the server reconnects.")
                    loaded.chats.isEmpty() -> Text("No chats are available on this server.")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(loaded?.chats.orEmpty(), key = { it.key }) { target ->
                        Column(Modifier.fillMaxWidth().clickable { onChoose(target) }.padding(vertical = 10.dp)) {
                            Text(if (target.isAcp) target.title.ifBlank { target.sessionName } else target.sessionName)
                            val subtitle = if (target.isAcp) target.cwd.ifBlank { "Direct chat" }
                                else "Window ${target.windowIndex}" + target.title.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                            Text(subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { if (loaded != null) TextButton(onClick = { attempt++ }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
