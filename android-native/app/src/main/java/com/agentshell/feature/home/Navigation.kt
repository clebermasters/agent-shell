package com.agentshell.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentshell.feature.alerts.AlertsScreen
import com.agentshell.feature.chat.ChatScreen
import com.agentshell.feature.cron.CronJobEditorScreen
import com.agentshell.feature.cron.CronScreen
import com.agentshell.feature.debug.DebugScreen
import com.agentshell.feature.dotfiles.DotfileEditorScreen
import com.agentshell.feature.dotfiles.DotfileTemplatesScreen
import com.agentshell.feature.dotfiles.DotfilesScreen
import com.agentshell.feature.file_browser.FileBrowserScreen
import com.agentshell.feature.hosts.HostSelectionScreen
import com.agentshell.feature.sessions.SessionsScreen
import com.agentshell.feature.settings.SettingsScreen
import com.agentshell.feature.system.SystemScreen
import com.agentshell.feature.terminal.TerminalScreen

object Routes {
    const val HOME = "home"
    const val TERMINAL = "terminal/{sessionName}"
    const val CHAT = "chat/{sessionName}/{windowIndex}"
    const val SETTINGS = "settings"
    const val DEBUG = "debug"
    const val ALERTS = "alerts"
    const val FILE_BROWSER = "file_browser?path={path}"
    const val HOST_SELECTION = "host_selection"
    const val CRON_EDITOR = "cron_editor?jobId={jobId}"
    const val DOTFILE_EDITOR = "dotfile_editor"
    const val DOTFILE_TEMPLATES = "dotfile_templates"
    const val LOGIN = "login"

    fun terminal(sessionName: String) = "terminal/$sessionName"
    fun chat(sessionName: String, windowIndex: Int) = "chat/$sessionName/$windowIndex"
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
                onNavigateToAcpChat = { id, _ -> navController.navigate(Routes.chat(id, 0)) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToAlerts = { navController.navigate(Routes.ALERTS) },
                onNavigateToHosts = { navController.navigate(Routes.HOST_SELECTION) },
                sessionsContent = {
                    SessionsScreen(
                        onNavigateToTerminal = { name -> navController.navigate(Routes.terminal(name)) },
                        onNavigateToChat = { name, idx -> navController.navigate(Routes.chat(name, idx)) },
                        onNavigateToAcpChat = { id, _ -> navController.navigate(Routes.chat(id, 0)) },
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
            arguments = listOf(navArgument("sessionName") { type = NavType.StringType }),
        ) { backStackEntry ->
            val sessionName = backStackEntry.arguments?.getString("sessionName") ?: ""
            TerminalScreen(
                sessionName = sessionName,
                onNavigateBack = { navController.popBackStack() },
                onSwitchToChat = { name, idx ->
                    navController.navigate(Routes.chat(name, idx)) { popUpTo(Routes.HOME) }
                },
            )
        }

        // ── Chat ──────────────────────────────────────────────────────────────
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("sessionName") { type = NavType.StringType },
                navArgument("windowIndex") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { backStackEntry ->
            val sessionName = backStackEntry.arguments?.getString("sessionName") ?: ""
            val windowIndex = backStackEntry.arguments?.getInt("windowIndex") ?: 0
            ChatScreen(
                sessionName = sessionName,
                windowIndex = windowIndex,
                isAcp = false,
                onNavigateBack = { navController.popBackStack() },
                onSwitchToTerminal = { name ->
                    navController.navigate(Routes.terminal(name)) { popUpTo(Routes.HOME) }
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
        ) {
            // CronJobEditorScreen uses shared CronViewModel — existingJob comes from ViewModel state
            CronJobEditorScreen(
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
