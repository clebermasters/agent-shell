package com.agentshell.feature.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.SystemStats

/**
 * A thin strip shown at the top of HomeScreen when system resources are high.
 *
 * Thresholds (mirroring Flutter logic):
 *   CPU  > 90 %
 *   RAM  > 90 %
 *   Disk > 90 %
 *
 * Tap the banner to switch to the System tab.
 */
@Composable
fun AlertBanner(
    stats: SystemStats?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alerts = buildAlerts(stats)
    val visible = alerts.isNotEmpty()

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF9C4)) // light yellow
                .clickable(onClick = onTap)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Warning",
                tint = Color(0xFFF57F17),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = alerts.joinToString("  •  "),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF5D4037),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun buildAlerts(stats: SystemStats?): List<String> {
    if (stats == null) return emptyList()
    val list = mutableListOf<String>()
    if (stats.cpuUsage > 90.0) list.add("CPU ${stats.cpuUsage.toInt()}%")
    if (stats.memoryUsage > 90.0) list.add("RAM ${stats.memoryUsage.toInt()}%")
    if (stats.diskUsage > 90.0) list.add("Disk ${stats.diskUsage.toInt()}%")
    return list
}
