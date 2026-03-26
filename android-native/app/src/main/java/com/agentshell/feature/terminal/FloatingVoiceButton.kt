package com.agentshell.feature.terminal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.local.PreferencesDataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface PrefsEntryPoint {
    fun prefs(): PreferencesDataStore
}

@Composable
fun FloatingVoiceButton(
    isRecording: Boolean,
    isTranscribing: Boolean,
    recordingDurationSeconds: Int,
    onPressed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val prefs = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PrefsEntryPoint::class.java,
        ).prefs()
    }

    val buttonSizeDp = if (isRecording) 56.dp else 48.dp
    val buttonSizePx = with(density) { buttonSizeDp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // Default position: bottom-right with 16dp margin
        val defaultX = maxWidthPx - buttonSizePx - with(density) { 16.dp.toPx() }
        val defaultY = maxHeightPx - buttonSizePx - with(density) { 80.dp.toPx() }

        var posX by remember { mutableFloatStateOf(defaultX) }
        var posY by remember { mutableFloatStateOf(defaultY) }

        // Load saved position
        LaunchedEffect(Unit) {
            val savedX = prefs.voiceButtonPosX.first()
            val savedY = prefs.voiceButtonPosY.first()
            if (savedX >= 0f && savedY >= 0f) {
                posX = savedX.coerceIn(0f, (maxWidthPx - buttonSizePx).coerceAtLeast(0f))
                posY = savedY.coerceIn(0f, (maxHeightPx - buttonSizePx).coerceAtLeast(0f))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch { prefs.setVoiceButtonPos(posX, posY) }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            posX = (posX + dragAmount.x)
                                .coerceIn(0f, (maxWidthPx - buttonSizePx).coerceAtLeast(0f))
                            posY = (posY + dragAmount.y)
                                .coerceIn(0f, (maxHeightPx - buttonSizePx).coerceAtLeast(0f))
                        },
                    )
                },
        ) {
            AnimatedContent(
                targetState = Triple(isRecording, isTranscribing, recordingDurationSeconds),
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.8f))
                        .togetherWith(fadeOut() + scaleOut(targetScale = 0.8f))
                },
                label = "voice_button_state",
            ) { (recording, transcribing, durationSeconds) ->
                val size = if (recording) 56.dp else 48.dp
                val bgColor = when {
                    transcribing -> Color(0xFF9CA3AF)
                    recording -> Color(0xFFEF4444)
                    else -> Color(0xFF6366F1)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(size)
                            .shadow(elevation = 6.dp, shape = CircleShape)
                            .background(bgColor, CircleShape)
                            .clickable(enabled = !transcribing) { onPressed() },
                    ) {
                        when {
                            transcribing -> {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            recording -> {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = "Stop recording",
                                    tint = Color.White,
                                )
                            }
                            else -> {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Start recording",
                                    tint = Color.White,
                                )
                            }
                        }
                    }

                    if (recording) {
                        Text(
                            text = "%d:%02d".format(durationSeconds / 60, durationSeconds % 60),
                            color = Color(0xFFEF4444),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
