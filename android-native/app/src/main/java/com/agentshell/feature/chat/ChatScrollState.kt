package com.agentshell.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.agentshell.data.model.ChatMessage

/** A separate, small final item also reaches the end of a reply taller than the screen. */
internal enum class ChatListAnchor { Unread, Streaming, End }

internal enum class ChatScrollAction { Wait, Pause, OpenUnread, OpenLatest, FollowLatest, ResumeFollowing }

internal fun chatScrollAction(
    active: Boolean,
    ready: Boolean,
    initialized: Boolean,
    following: Boolean,
    scrolling: Boolean,
    atEnd: Boolean,
    messageCount: Int,
    unreadIndex: Int,
): ChatScrollAction = when {
    !active || messageCount == 0 -> ChatScrollAction.Wait
    scrolling -> ChatScrollAction.Pause
    !ready -> ChatScrollAction.Wait
    !initialized && unreadIndex >= 0 -> ChatScrollAction.OpenUnread
    !initialized -> ChatScrollAction.OpenLatest
    following && !atEnd -> ChatScrollAction.FollowLatest
    !following && atEnd -> ChatScrollAction.ResumeFollowing
    else -> ChatScrollAction.Wait
}

@Stable
internal class ChatScrollState(
    private val list: LazyListState,
    private val following: MutableState<Boolean>,
    private val opened: MutableState<Boolean>,
) {
    val followingLatest get() = following.value
    val initialized get() = opened.value
    var atEnd by mutableStateOf(false)
        internal set
    internal var endIndex = 0

    fun jumpToLatest() {
        following.value = true
        opened.value = true
        list.requestScrollToItem(endIndex)
    }

    internal fun apply(action: ChatScrollAction, unreadIndex: Int) {
        when (action) {
            ChatScrollAction.Pause -> {
                following.value = false
                opened.value = true
            }
            ChatScrollAction.OpenUnread -> {
                following.value = false
                opened.value = true
                list.requestScrollToItem(unreadIndex)
            }
            ChatScrollAction.OpenLatest -> jumpToLatest()
            ChatScrollAction.FollowLatest -> list.requestScrollToItem(endIndex)
            ChatScrollAction.ResumeFollowing -> following.value = true
            ChatScrollAction.Wait -> Unit
        }
    }
}

private data class ScrollContent(
    val messages: List<ChatMessage>,
    val streaming: Boolean,
    val ready: Boolean,
    val active: Boolean,
    val unreadIndex: Int,
)

private data class ScrollObservation(
    val action: ChatScrollAction,
    val endIndex: Int,
    val unreadIndex: Int,
    val atEnd: Boolean,
    val latestMessage: ChatMessage?,
    val itemSizes: List<Pair<Any, Int>>,
    val viewportStart: Int,
    val viewportEnd: Int,
)

/** Follow layout/content changes without cancelable per-message scroll animations. */
@Composable
internal fun rememberChatScrollState(
    chatKey: String,
    list: LazyListState,
    messages: List<ChatMessage>,
    streaming: Boolean,
    ready: Boolean,
    active: Boolean,
    firstUnreadId: String?,
): ChatScrollState {
    val following = rememberSaveable(chatKey) { mutableStateOf(true) }
    val opened = rememberSaveable(chatKey) { mutableStateOf(false) }
    val state = remember(chatKey, list) { ChatScrollState(list, following, opened) }
    val unreadIndex by remember(messages, firstUnreadId) {
        derivedStateOf { chatUnreadIndex(messages, firstUnreadId) }
    }
    val content by rememberUpdatedState(ScrollContent(messages, streaming, ready, active, unreadIndex))

    LaunchedEffect(chatKey, list) {
        snapshotFlow {
            val data = content
            val layout = list.layoutInfo
            val unreadIndex = data.unreadIndex
            val endIndex = data.messages.size + (if (unreadIndex >= 0) 1 else 0) +
                (if (data.streaming) 1 else 0)
            val end = layout.visibleItemsInfo.lastOrNull()
            // A newly appended item may not have been measured yet. Never use
            // the previous layout's apparent bottom to resume a paused reader.
            val atEnd = layout.totalItemsCount == endIndex + 1 &&
                end?.key == ChatListAnchor.End && !list.canScrollForward
            ScrollObservation(
                action = chatScrollAction(data.active, data.ready, state.initialized,
                    state.followingLatest, list.isScrollInProgress, atEnd, data.messages.size, unreadIndex),
                endIndex = endIndex,
                unreadIndex = unreadIndex,
                atEnd = atEnd,
                latestMessage = data.messages.lastOrNull(),
                itemSizes = layout.visibleItemsInfo.map { it.key to it.size },
                viewportStart = layout.viewportStartOffset,
                viewportEnd = layout.viewportEndOffset,
            )
        }.collect { observation ->
            state.endIndex = observation.endIndex
            state.atEnd = observation.atEnd
            state.apply(observation.action, observation.unreadIndex)
        }
    }
    return state
}
