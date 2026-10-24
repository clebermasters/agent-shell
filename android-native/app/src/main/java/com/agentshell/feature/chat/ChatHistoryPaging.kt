package com.agentshell.feature.chat

/** An interrupted backfill may contain disconnected old and recent windows. */
internal fun preferRemoteChatHistory(connected: Boolean, cacheComplete: Boolean, remoteHasMore: Boolean, hasGap: Boolean): Boolean =
    connected && remoteHasMore && (!cacheComplete || hasGap)

/** Only a complete overlapping cache proves that every displayed row advances the remote cursor. */
internal fun remoteChatHistoryOffset(parsedCount: Int, displayedCount: Int, cacheComplete: Boolean, overlaps: Boolean): Int =
    if (cacheComplete && overlaps) maxOf(parsedCount, displayedCount) else parsedCount

internal fun advanceRemoteChatHistoryOffset(previousOffset: Int, receivedCount: Int, displayedCount: Int, cacheComplete: Boolean): Int =
    if (cacheComplete) maxOf(previousOffset + receivedCount, displayedCount) else previousOffset + receivedCount
