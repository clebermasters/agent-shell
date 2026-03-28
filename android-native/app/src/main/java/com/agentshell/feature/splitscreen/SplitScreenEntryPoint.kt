package com.agentshell.feature.splitscreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.ChatRepository
import com.agentshell.data.services.TerminalService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SplitScreenEntryPoint {
    fun terminalService(): TerminalService
    fun webSocketService(): WebSocketService
    fun chatRepository(): ChatRepository
}

@Composable
fun rememberSplitScreenServices(): SplitScreenEntryPoint {
    val context = LocalContext.current.applicationContext
    return remember {
        EntryPointAccessors.fromApplication(context, SplitScreenEntryPoint::class.java)
    }
}
