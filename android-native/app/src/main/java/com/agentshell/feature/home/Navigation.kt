package com.agentshell.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentshell.feature.cron.CronViewModel
import com.agentshell.feature.alerts.AlertsScreen
import com.agentshell.feature.chat.ChatScreen
import com.agentshell.feature.cron.CronJobEditorScreen
import com.agentshell.feature.cron.CronScreen
import com.agentshell.feature.debug.DebugScreen
import com.agentshell.feature.dotfiles.DotfileEditorScreen
import com.agentshell.feature.dotfiles.DotfileTemplatesScreen
import com.agentshell.feature.dotfiles.DotfilesScreen
import com.agentshell.feature.file_browser.FileBrowserScreen
import com.agentshell.feature.splitscreen.SplitScreenScreen
import com.agentshell.feature.hosts.HostSelectionScreen
import com.agentshell.feature.sessions.SessionsScreen
import com.agentshell.feature.settings.SettingsScreen
import com.agentshell.feature.system.SystemScreen
import com.agentshell.feature.terminal.TerminalScreen

object Routes {
    const val HOME = "home"
    const val TERMINAL = "terminal/{sessionName}?isSwipeNav={isSwipeNav}"
    const val CHAT = "chat/{sessionName}/{windowIndex}?isAcp={isAcp}&isSwipeNav={isSwipeNav}"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val ALERTS = "alerts"
    const val FILE_BROWSER = "file_browser?path={path}"
    const val HOST_SELECTION = "host_selection"
    const val CRON_EDITOR = "cron_editor?jobId={jobId}"
    const val DOTFILE_EDITOR = "dotfile_editor"
    const val DOTFILE_TEMPLATES = "dotfile_templates"
    const val SPLIT_SCREEN = "split_screen?layoutId={layoutId}"
    const val LOGIN = "login"

    fun splitScreen(layoutId: String? = null) = if (layoutId != null) "split_screen?layoutId=$layoutId" else "split_screen"
    fun terminal(sessionName: String, isSwipeNav: Boolean = false) = "terminal/$sessionName?isSwipeNav=$isSwipeNav"
    fun chat(sessionName: String, windowIndex: Int, isAcp: Boolean = false, isSwipeNav: Boolean = false) = "chat/$sessionName/$windowIndex?isAcp=$isAcp&isSwipeNav=$isSwipeNav"
    fun fileBrowser(path: String = "/") = "file_browser?path=$path"
    fun cronEditor(jobId: String? = null) = if (jobId != null) "cron_editor?jobId=$jobId" else "cron_editor"
    fun dotfileEditor() = DOTFILE_EDITOR
}

@Composable
fun AgentShellNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        // ── Home shell (tabs: Sessions, Cron, Dotfiles, System) ──────────────
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToTerminal = { name -> navController.navigate(Routes.terminal(name)) },
                onNavigateToChat = { name, idx -> navController.navigate(Routes.chat(name, idx)) },
                onNavigateToAcpChat = { id, _ -> navController.navigate(Routes.chat(id, 0, isAcp = true)) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToAlerts = { navController.navigate(Routes.ALERTS) },
                onNavigateToHosts = { navController.navigate(Routes.HOST_SELECTION) },
                onNavigateToSplitScreen = { layoutId -> navController.navigate(Routes.splitScreen(layoutId)) },
                sessionsContent = {
                    SessionsScreen(
                        onNavigateToTerminal = { name -> navController.navigate(Routes.terminal(name)) },
                        onNavigateToChat = { name, idx -> navController.navigate(Routes.chat(name, idx)) },
                        onNavigateToAcpChat = { id, _ -> navController.navigate(Routes.chat(id, 0, isAcp = true)) },
                        onNavigateToHosts = { navController.navigate(Routes.HOST_SELECTION) },
                    )
                },
                cronContent = {
                    CronScreen(
                        onNavigateToEditor = { job ->
                            navController.navigate(Routes.cronEditor(job?.id))
                        },
                    )
                },
                dotfilesContent = {
                    DotfilesScreen(
                        onNavigateToEditor = { _ ->
                            // selectFile() already called in DotfilesScreen — just navigate
                            navController.navigate(Routes.dotfileEditor())
                        },
                        onNavigateToTemplates = {
                            navController.navigate(Routes.DOTFILE_TEMPLATES)
                        },
                        onNavigateToBrowse = {
                            navController.navigate(Routes.fileBrowser("/"))
                        },
                    )
                },
                systemContent = {
                    SystemScreen()
                },
            )
        }

        // ── Terminal ──────────────────────────────────────────────────────────
        composable(
            route = Routes.TERMINAL,
            arguments = listOf(
                navArgument("sessionName") { type = NavType.StringType },
                navArgument("isSwipeNav") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { backStackEntry ->
            val sessionName = backStackEntry.arguments?.getString("sessionName") ?: ""
            val isSwipeNav = backStackEntry.arguments?.getBoolean("isSwipeNav") ?: false
            TerminalScreen(
                sessionName = sessionName,
                isSwipeNavigation = isSwipeNav,
                onNavigateBack = { navController.popBackStack() },
                onSwitchToChat = { name, idx ->
                    navController.navigate(Routes.chat(name, idx)) { popUpTo(Routes.HOME) }
                },
                onNavigateToFileBrowser = { path ->
                    navController.navigate(Routes.fileBrowser(path))
                },
                onSwipeToSession = { name ->
                    navController.navigate(Routes.terminal(name, isSwipeNav = true)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        // ── Chat ──────────────────────────────────────────────────────────────
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionName") { type = NavType.StringType },
                navArgument("windowIndex") { type = NavType.IntType; defaultValue = 0 },
                navArgument("isAcp") { type = NavType.BoolType; defaultValue = false },
                navArgument("isSwipeNav") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { backStackEntry ->
            val sessionName = backStackEntry.arguments?.getString("sessionName") ?: ""
            val windowIndex = backStackEntry.arguments?.getInt("windowIndex") ?: 0
            val isAcp = backStackEntry.arguments?.getBoolean("isAcp") ?: false
            val isSwipeNav = backStackEntry.arguments?.getBoolean("isSwipeNav") ?: false
            ChatScreen(
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = isAcp,
                isSwipeNavigation = isSwipeNav,
                onNavigateBack = { navController.popBackStack() },
                onSwitchToTerminal = { name ->
                    navController.navigate(Routes.terminal(name)) { popUpTo(Routes.HOME) }
                },
                onNavigateToFileBrowser = { path ->
                    navController.navigate(Routes.fileBrowser(path))
                },
                onSwipeToChatSession = { name, idx, isAcpNav ->
                    navController.navigate(Routes.chat(name, idx, isAcp = isAcpNav, isSwipeNav = true)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }

        // ── Cron Job Editor ──────────────────────────────────────────────────
        composable(
            route = Routes.CRON_EDITOR,
            arguments = listOf(
                navArgument("jobId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val jobId = backStackEntry.arguments?.getString("jobId")
            val homeEntry = remember(navController) { navController.getBackStackEntry(Routes.HOME) }
            val cronViewModel: CronViewModel = hiltViewModel(homeEntry)
            val cronState by cronViewModel.state.collectAsState()
            val existingJob = jobId?.let { id -> cronState.jobs.firstOrNull { it.id == id } }
            CronJobEditorScreen(
                existingJob = existingJob,
                onNavigateUp = { navController.popBackStack() },
            )
        }

        // ── Dotfile Editor ───────────────────────────────────────────────────
        composable(Routes.DOTFILE_EDITOR) {
            DotfileEditorScreen(
                onNavigateUp = { navController.popBackStack() },
            )
        }

        // ── Dotfile Templates ────────────────────────────────────────────────
        composable(Routes.DOTFILE_TEMPLATES) {
            DotfileTemplatesScreen(
                onNavigateUp = { navController.popBackStack() },
            )
        }

        // ── Settings ──────────────────────────────────────────────────────────
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateToHostSelection = { navController.navigate(Routes.HOST_SELECTION) },
            )
        }

        // ── Debug ─────────────────────────────────────────────────────────────
        composable(Routes.DEBUG) {
            DebugScreen(
                onNavigateUp = { navController.popBackStack() },
            )
        }

        // ── Alerts ────────────────────────────────────────────────────────────
        composable(Routes.ALERTS) {
            AlertsScreen()
        }

        // ── File Browser ──────────────────────────────────────────────────────
        composable(
            route = Routes.FILE_BROWSER,
            arguments = listOf(
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = "/"
                    nullable = true
                },
            ),
        ) { backStackEntry ->
            val path = backStackEntry.arguments?.getString("path") ?: "/"
            FileBrowserScreen(
                initialPath = path,
                onOpenFile = { entry ->
                    // TODO: open file viewer/preview
                },
                onNavigateUp = { navController.popBackStack() },
            )
        }

        // ── Host Selection ────────────────────────────────────────────────────
        composable(Routes.HOST_SELECTION) {
            HostSelectionScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── Split Screen ─────────────────────────────────────────────────────
        composable(
            route = Routes.SPLIT_SCREEN,
            arguments = listOf(
                navArgument("layoutId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val layoutId = backStackEntry.arguments?.getString("layoutId")
            SplitScreenScreen(
                layoutId = layoutId,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ── Login ─────────────────────────────────────────────────────────────
        composable(Routes.LOGIN) {
            PlaceholderScreen("Login")
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label) }
}
