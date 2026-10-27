package com.devicescope.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devicescope.app.data.AuditFinding
import com.devicescope.app.data.DiagnosticMetric
import com.devicescope.app.data.DiagnosticSection
import com.devicescope.app.data.DiagnosticStatus

fun statusLabel(status: DiagnosticStatus): String = when (status) {
    DiagnosticStatus.OK -> "OK"
    DiagnosticStatus.WARNING -> "Advertencia"
    DiagnosticStatus.ERROR -> "Error"
    DiagnosticStatus.UNKNOWN -> "Sin datos"
}

@Composable
fun SectionIcon(key: String, modifier: Modifier = Modifier) {
    Icon(
        imageVector = iconForKey(key),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(22.dp),
    )
}

private fun iconForKey(key: String): ImageVector = when (key) {
    "identity" -> Icons.Filled.Info
    "mic" -> Icons.Filled.PlayArrow
    "ram" -> Icons.Filled.Memory
    "storage" -> Icons.Filled.Storage
    "antennas" -> Icons.Filled.CellTower
    "screen" -> Icons.Filled.PhoneAndroid
    "touch" -> Icons.Filled.PhoneAndroid
    "speakers" -> Icons.Filled.PlayArrow
    "plugs" -> Icons.Filled.Info
    "battery" -> Icons.Filled.BatteryChargingFull
    "cable" -> Icons.Filled.BatteryChargingFull
    "network" -> Icons.Filled.Wifi
    "voltage" -> Icons.Filled.CellTower
    "properties" -> Icons.Filled.Apps
    else -> Icons.Filled.Info
}

@Composable
fun SectionCard(
    section: DiagnosticSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRunTest: (String) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionIcon(section.icon)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                StatusPill(label = statusLabel(section.status), status = section.status)
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                section.metrics.forEach { MetricRow(it) }
                if (section.findings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FindingsBlock(section.findings)
                }
                if (section.note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    NoteBlock(section.note)
                }
                section.interactiveLabel?.let { label ->
                    Spacer(Modifier.height(12.dp))
                    ActionButton(text = label) { onRunTest(section.id) }
                }
                extra()
            }
        }
    }
}

@Composable
fun MetricRow(metric: DiagnosticMetric) {
    val valueColor = when (metric.status) {
        DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = metric.label,
            modifier = Modifier.weight(0.95f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1.6f)) {
            Text(
                text = metric.value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                fontWeight = if (metric.status == DiagnosticStatus.OK) FontWeight.Normal else FontWeight.SemiBold,
            )
            metric.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun FindingsBlock(findings: List<AuditFinding>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        findings.forEach { finding ->
            val color = when (finding.severity) {
                DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
                DiagnosticStatus.WARNING -> MaterialTheme.colorScheme.tertiary
                DiagnosticStatus.OK -> MaterialTheme.colorScheme.primary
                DiagnosticStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (finding.severity == DiagnosticStatus.OK) Icons.Filled.Info else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(
                        text = "${statusLabel(finding.severity)} · ${finding.title}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                    )
                    Text(
                        text = finding.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun NoteBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text)
    }
}
