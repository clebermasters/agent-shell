package com.agentshell.feature.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val HINT_THRESHOLD = 60f
private const val SWITCH_THRESHOLD = 120f
private const val BOUNDARY_DAMPEN = 0.25f

@Composable
fun SwipeableSessionContainer(
    recentSessions: List<String>,
    currentSessionKey: String,
    sessionLabel: (String) -> String = { it },
    onSwipeToSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentIndex = recentSessions.indexOf(currentSessionKey)
    var swipeDx by remember { mutableFloatStateOf(0f) }
    var showOverlay by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf("") }
    var navigated by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.pointerInput(recentSessions, currentIndex) {
            if (recentSessions.size <= 1) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = {
                    swipeDx = 0f
                    showOverlay = false
                    navigated = false
                },
                onHorizontalDrag = { _, dragAmount ->
                    if (navigated) return@detectHorizontalDragGestures
                    // direction: negative = swipe left (next), positive = swipe right (prev)
                    val atStart = currentIndex <= 0
                    val atEnd = currentIndex >= recentSessions.size - 1
                    val dampen = when {
                        dragAmount > 0 && atStart -> BOUNDARY_DAMPEN
                        dragAmount < 0 && atEnd -> BOUNDARY_DAMPEN
                        else -> 1f
                    }
                    swipeDx += dragAmount * dampen

                    if (abs(swipeDx) > HINT_THRESHOLD) {
                        showOverlay = true
                        val nextIdx = if (swipeDx > 0) currentIndex - 1 else currentIndex + 1
                        hintText = when {
                            swipeDx > 0 && atStart -> "\u2190 (start)"
                            swipeDx < 0 && atEnd -> "(end) \u2192"
                            nextIdx in recentSessions.indices -> sessionLabel(recentSessions[nextIdx])
                            else -> ""
                        }
                    } else {
                        showOverlay = false
                    }

                    if (abs(swipeDx) > SWITCH_THRESHOLD) {
                        val nextIdx = if (swipeDx > 0) currentIndex - 1 else currentIndex + 1
                        if (nextIdx in recentSessions.indices) {
                            navigated = true
                            onSwipeToSession(recentSessions[nextIdx])
                        }
                    }
                },
                onDragEnd = {
                    swipeDx = 0f
                    showOverlay = false
                },
                onDragCancel = {
                    swipeDx = 0f
                    showOverlay = false
                },
            )
        },
    ) {
        content()

        // Dot indicator
        AnimatedVisibility(
            visible = showOverlay && recentSessions.size > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center) {
                recentSessions.forEachIndexed { i, _ ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == currentIndex) 8.dp else 6.dp)
                            .background(
                                if (i == currentIndex) Color.White else Color.White.copy(alpha = 0.4f),
                                CircleShape,
                            ),
                    )
                }
            }
        }

        // Hint overlay
        AnimatedVisibility(
            visible = showOverlay && hintText.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.7f),
            ) {
                Text(
                    hintText,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
