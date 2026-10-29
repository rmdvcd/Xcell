package com.devicescope.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.PcMetrics
import com.devicescope.app.pcmovil.data.PcMetricsSample

/**
 * PC-Resources: panel compacto de la PC en tiempo real.
 *
 * El agente muestrea CPU, GPU, RAM, discos, red, batería y procesos; el teléfono
 * solo pinta lo que llega, así que el consumo del móvil es mínimo.
 */
@Composable
fun PcResourcesToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val state by link.state.collectAsState()
    val metrics by link.metrics.collectAsState()
    val history by link.history.collectAsState()
    var interval by remember { mutableFloatStateOf(link.store.metricsIntervalMillis.toFloat()) }

    PcToolScreen(
        title = "PC-Resources",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        val current = metrics
        if (current == null) {
            PcCard {
                Text(
                    text = "Esperando métricas de la PC…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { link.requestStatsInterval() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pedir ahora") }
            }
            return@PcToolScreen
        }

        PcCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.hostname.ifBlank { "PC" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = listOfNotNull(
                            current.osName.takeIf { it.isNotBlank() },
                            current.cpuName.takeIf { it.isNotBlank() },
                            "${current.cpuCores} núcleos",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "act. ${PcFormat.clock(current.timestampMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Gauge(
                    label = "CPU",
                    value = current.cpuPercent,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Gauge(
                    label = "RAM",
                    value = (current.ramPercent * 100f).toDouble(),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Gauge(
                    label = "GPU",
                    value = current.gpuPercent ?: 0.0,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
            }
            PcSparkline(
                values = history.map { it.cpu },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                maxValue = 100f,
            )
            Row {
                Text(
                    text = "Frecuencia ${PcFormat.megahertz(current.cpuFreqMhz)} · ${current.cpuTempC?.let { PcFormat.temperature(it) } ?: "sin sensor"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (current.loadAverage != null) {
                    Text(
                        text = String.format("carga %.2f", current.loadAverage),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PcCard {
            PcSectionTitle(text = "Memoria")
            PcProgress(
                fraction = current.ramPercent,
                label = "RAM en uso",
                valueLabel = "${PcFormat.bytes(current.ramUsedBytes)} / ${PcFormat.bytes(current.ramTotalBytes)}",
            )
            if (current.swapTotalBytes > 0) {
                PcProgress(
                    fraction = (current.swapUsedBytes.toDouble() / current.swapTotalBytes).toFloat(),
                    label = "Swap",
                    valueLabel = "${PcFormat.bytes(current.swapUsedBytes)} / ${PcFormat.bytes(current.swapTotalBytes)}",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            PcMetricRow(
                label = "Procesos",
                value = "${current.processCount}",
                hint = "${current.threadCount} hilos · ${current.topProcesses.size} con más carga",
            )
            current.topProcesses.take(5).forEach { process ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = process.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${PcFormat.percent(process.cpuPercent)} · ${PcFormat.bytes(process.ramBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PcCard {
            PcSectionTitle(text = "Discos")
            if (current.disks.isEmpty()) {
                Text(
                    text = "El agente no informó volúmenes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.disks.take(4).forEach { disk ->
                val fraction = if (disk.totalBytes > 0) {
                    disk.usedBytes.toFloat() / disk.totalBytes
                } else {
                    0f
                }
                PcProgress(
                    fraction = fraction,
                    label = disk.mount,
                    valueLabel = "${PcFormat.bytes(disk.usedBytes)} / ${PcFormat.bytes(disk.totalBytes)}",
                    color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Red")
            PcMetricRow(label = "Interfaz", value = current.netInterface.ifBlank { "—" })
            PcMetricRow(label = "IP local", value = current.netIp.ifBlank { "—" })
            if (current.wifiSsid.isNotBlank()) {
                PcMetricRow(label = "WiFi", value = current.wifiSsid)
            }
            PcMetricRow(
                label = "Descarga",
                value = PcFormat.rate(current.netRxBytesPerSec),
            )
            PcMetricRow(
                label = "Subida",
                value = PcFormat.rate(current.netTxBytesPerSec),
            )
            val networkMax = maxOf(
                history.maxOfOrNull { it.rx } ?: 0f,
                history.maxOfOrNull { it.tx } ?: 0f,
                1f,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PcSparkline(
                    values = history.map { it.rx },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    maxValue = networkMax,
                    color = MaterialTheme.colorScheme.primary,
                )
                PcSparkline(
                    values = history.map { it.tx },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    maxValue = networkMax,
                    color = MaterialTheme.colorScheme.secondary,
                    fill = false,
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Sistema")
            PcMetricRow(label = "Encendida hace", value = PcFormat.uptime(current.uptimeSeconds))
            if (current.gpuName.isNotBlank()) {
                PcMetricRow(
                    label = "GPU",
                    value = current.gpuName,
                    hint = current.gpuTempC?.let { PcFormat.temperature(it) },
                )
            }
            current.batteryPercent?.let { battery ->
                PcMetricRow(
                    label = "Batería",
                    value = "$battery%",
                    hint = when (current.batteryCharging) {
                        true -> "cargando"
                        false -> "descargando"
                        null -> null
                    },
                )
            }
            Column {
                Row {
                    Text(
                        text = "Frecuencia de muestreo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(interval / 1000f).let { String.format("%.1f s", it) }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Slider(
                    value = interval,
                    onValueChange = {
                        interval = it
                        link.store.metricsIntervalMillis = it.toInt()
                    },
                    onValueChangeFinished = { link.requestStatsInterval() },
                    valueRange = 500f..5000f,
                )
            }
        }

        PcNote(
            text = "Todo llega por el enlace local y se mantiene solo en memoria: el historial son los últimos " +
                "90 muestreos y se borra al desconectar.",
        )
    }
}

@Composable
private fun Gauge(
    label: String,
    value: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = PcFormat.percent(value),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        PcProgress(
            fraction = (value / 100.0).toFloat(),
            color = color,
        )
    }
}
