package com.agentshell.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Permission request card shown as an overlay in [ChatScreen] when the
 * ACP agent requests approval for a potentially destructive action.
 *
 * Mirrors the Flutter [AcpPermissionCard] widget.
 */
@Composable
fun AcpPermissionCard(
    permission: PendingPermission,
    onRespond: (requestId: String, optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFBE6),  // amber-50 equivalent
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header: shield icon + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFFB45309),  // amber-700
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "Permission Request: ${permission.tool}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF78350F),  // amber-900
                )
            }

            // Command preview box
            if (permission.command.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1C1C1C),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = permission.command,
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }

            // Action buttons
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                permission.options.forEach { option ->
                    val isDeny = option.id.lowercase().let {
                        it.contains("deny") || it.contains("reject") || it.contains("cancel")
                    }
                    if (isDeny) {
                        OutlinedButton(
                            onClick = { onRespond(permission.requestId, option.id) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(text = option.label)
                        }
                    } else {
                        Button(
                            onClick = { onRespond(permission.requestId, option.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF16A34A),  // green-600
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(text = option.label)
                        }
                    }
                }
            }
        }
    }
}
