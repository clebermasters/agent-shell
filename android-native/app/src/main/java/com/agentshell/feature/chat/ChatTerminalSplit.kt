package com.agentshell.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences
import kotlin.math.roundToInt

internal fun resolveChatSplitOrientation(
    preferred: ChatSplitOrientation,
    widthDp: Int,
    heightDp: Int,
): ChatSplitOrientation = when {
    preferred != ChatSplitOrientation.AUTO -> preferred
    widthDp >= 840 || widthDp > heightDp -> ChatSplitOrientation.SIDE_BY_SIDE
    else -> ChatSplitOrientation.STACKED
}

/** Minimum sizes apply to the current viewport, without overwriting the user's saved ratio. */
internal fun calculateChatSplitSizes(availablePx: Int, chatFraction: Float, minPanePx: Int): Pair<Int, Int> {
    val available = availablePx.coerceAtLeast(0)
    val minimum = minPanePx.coerceIn(0, available / 2)
    val fraction = if (chatFraction.isFinite()) chatFraction.coerceIn(0.2f, 0.8f) else 0.5f
    val chat = (available * fraction).roundToInt().coerceIn(minimum, available - minimum)
    return chat to available - chat
}

/** Fixed composition order keeps chat scroll/input and the terminal WebView alive during swaps. */
@Composable
internal fun ChatTerminalSplit(
    preferences: ChatSplitPreferences,
    onResize: (ChatSplitOrientation, Float) -> Unit,
    onSwap: () -> Unit,
    onConfigure: () -> Unit,
    onChatInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    chat: @Composable () -> Unit,
    terminal: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    // Screen configuration, rather than the IME-reduced viewport, determines Auto layout.
    val orientation = resolveChatSplitOrientation(preferences.orientation, configuration.screenWidthDp, configuration.screenHeightDp)
    val sideBySide = orientation == ChatSplitOrientation.SIDE_BY_SIDE
    val savedFraction = preferences.fractionFor(orientation)
    var fraction by remember(orientation, savedFraction) { mutableFloatStateOf(savedFraction) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val dividerPx = with(density) { 24.dp.roundToPx() }
    val minimumPx = with(density) { (if (sideBySide) 120.dp else 80.dp).roundToPx() }
    val latestResize by rememberUpdatedState(onResize)
    val latestChatInteraction by rememberUpdatedState(onChatInteraction)
    val latestConfigure by rememberUpdatedState(onConfigure)
    val latestSwap by rememberUpdatedState(onSwap)
    val commitResize = { latestResize(orientation, fraction) }

    Layout(
        modifier = modifier.onSizeChanged { viewport = it },
        content = {
            Box(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    latestChatInteraction()
                }
            }) { chat() }
            if (preferences.enabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { latestConfigure() }, onDoubleTap = { latestSwap() })
                        }
                        .draggable(
                            state = rememberDraggableState { delta ->
                                val available = (if (sideBySide) viewport.width else viewport.height) - dividerPx
                                if (available > 0) {
                                    val direction = if (preferences.chatFirst) 1f else -1f
                                    fraction = (fraction + direction * delta / available).coerceIn(0.2f, 0.8f)
                                }
                            },
                            orientation = if (sideBySide) Orientation.Horizontal else Orientation.Vertical,
                            onDragStopped = { commitResize() },
                        )
                        .semantics {
                            contentDescription = "Resize chat and terminal"
                            stateDescription = "Chat ${(fraction * 100).roundToInt()} percent"
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0.2f..0.8f)
                            onClick(label = "Split layout options") { onConfigure(); true }
                            setProgress { value ->
                                if (value.isFinite()) {
                                    fraction = value.coerceIn(0.2f, 0.8f)
                                    commitResize()
                                    true
                                } else false
                            }
                            customActions = listOf(
                                CustomAccessibilityAction("Swap chat and terminal") { onSwap(); true },
                                CustomAccessibilityAction("Split layout options") { onConfigure(); true },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(if (sideBySide) 4.dp else 36.dp, if (sideBySide) 36.dp else 4.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                }
                Box(Modifier.fillMaxSize().clipToBounds()) { terminal() }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (!preferences.enabled) {
            val chatPane = measurables[0].measure(Constraints.fixed(width, height))
            layout(width, height) { chatPane.place(0, 0) }
        } else {
            val axis = if (sideBySide) width else height
            val divider = dividerPx.coerceAtMost(axis)
            val (chatSize, terminalSize) = calculateChatSplitSizes(axis - divider, fraction, minimumPx)
            val chatPane = measurables[0].measure(Constraints.fixed(if (sideBySide) chatSize else width, if (sideBySide) height else chatSize))
            val handle = measurables[1].measure(Constraints.fixed(if (sideBySide) divider else width, if (sideBySide) height else divider))
            val terminalPane = measurables[2].measure(Constraints.fixed(if (sideBySide) terminalSize else width, if (sideBySide) height else terminalSize))
            val firstSize = if (preferences.chatFirst) chatSize else terminalSize
            val chatOffset = if (preferences.chatFirst) 0 else firstSize + divider
            val terminalOffset = if (preferences.chatFirst) firstSize + divider else 0
            layout(width, height) {
                chatPane.place(if (sideBySide) chatOffset else 0, if (sideBySide) 0 else chatOffset)
                handle.place(if (sideBySide) firstSize else 0, if (sideBySide) 0 else firstSize)
                terminalPane.place(if (sideBySide) terminalOffset else 0, if (sideBySide) 0 else terminalOffset)
            }
        }
    }
}
