package com.agentshell.feature.splitscreen

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One stable capture keeps paging state out of the large composable's local data-flow graph. */
@Stable
internal class PanelChatHistoryState {
    var cacheReady by mutableStateOf(false)
    var cachedConversationKey by mutableStateOf<String?>(null)
    var oldestCachedPosition by mutableStateOf<Int?>(null)
    var hasOlderCachedMessages by mutableStateOf(false)
    var cacheComplete by mutableStateOf(false)
    var remoteHasMore by mutableStateOf(false)
    var remoteOffset by mutableStateOf(0)
    var remoteGapIds by mutableStateOf<Set<String>>(emptySet())
    var loadingOlder by mutableStateOf(false)
    var pagingVersion by mutableStateOf(0)
    var cacheRevision by mutableStateOf(0L)
    var cacheDirty by mutableStateOf(false)
}
