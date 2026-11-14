package com.agentshell.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp

/** Keep the editor usable in narrow split panes without shrinking action touch targets. */
@Composable
internal fun ChatComposerLayout(
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
    attach: @Composable () -> Unit,
    voice: (@Composable () -> Unit)?,
    send: @Composable () -> Unit,
) {
    // Stable composition slots preserve editor focus and selection when its layout changes.
    Layout(
        modifier = modifier,
        content = {
            field()
            attach()
            if (voice != null) voice()
            send()
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val buttonSize = 48.dp.roundToPx().coerceAtMost(width)
        val gap = 4.dp.roundToPx()
        val buttons = measurables.drop(1).map { it.measure(Constraints.fixed(buttonSize, buttonSize)) }
        val narrow = width < 320.dp.roundToPx()
        if (narrow) {
            val columns = (width / buttonSize.coerceAtLeast(1)).coerceAtLeast(1)
            val rows = (buttons.size + columns - 1) / columns
            val actionsHeight = rows * buttonSize
            val fieldHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - actionsHeight - gap).coerceAtLeast(0) else Constraints.Infinity
            val editor = measurables[0].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = fieldHeight))
            layout(width, constraints.constrainHeight(editor.height + gap + actionsHeight)) {
                editor.placeRelative(0, 0)
                buttons.forEachIndexed { index, button ->
                    val row = index / columns
                    val inRow = index % columns
                    val count = (buttons.size - row * columns).coerceAtMost(columns)
                    val x = if (count > 1) inRow * (width - buttonSize) / (count - 1) else width - buttonSize
                    button.placeRelative(x, editor.height + gap + row * buttonSize)
                }
            }
        } else {
            val fieldWidth = (width - buttons.sumOf { it.width } - gap).coerceAtLeast(0)
            val editor = measurables[0].measure(Constraints(minWidth = fieldWidth, maxWidth = fieldWidth, maxHeight = constraints.maxHeight))
            val height = constraints.constrainHeight(maxOf(editor.height, buttonSize))
            layout(width, height) {
                buttons[0].placeRelative(0, height - buttonSize)
                editor.placeRelative(buttonSize, height - editor.height)
                buttons.drop(1).forEachIndexed { index, button ->
                    button.placeRelative(buttonSize + fieldWidth + gap + index * buttonSize, height - buttonSize)
                }
            }
        }
    }
}
