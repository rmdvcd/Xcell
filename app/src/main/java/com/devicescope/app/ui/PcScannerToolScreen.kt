package com.devicescope.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.ScanHit
import com.devicescope.app.pcmovil.data.ScanRadio
import com.devicescope.app.pcmovil.data.ScanReport
import com.devicescope.app.pcmovil.link.DataScanner
import com.devicescope.app.pcmovil.link.PcProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A-D Scanner.
 *
 * Barre lo que las radios del teléfono pueden captar de verdad y, si la PC está
 * conectada, suma el barrido de la subred que hace el agente. Cada resultado
 * incluye RSSI, canal o banda, y una distancia estimada por modelo logarítmico
 * (orientativa: no está calibrada en el sitio).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcScannerToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val link = rememberPcLink()
    val linkState by link.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scanner = remember { DataScanner(context) }
    val inventory = remember { scanner.inventory() }

    var report by remember { mutableStateOf<ScanReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableStateOf(8) }
    var lanEnabled by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var filters by remember { mutableStateOf<Set<ScanRadio>>(emptySet()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        message = if (granted.values.all { it }) {
            "Permisos concedidos. Pulsa «Barrer entorno»."
        } else {
            "Sin permisos de escaneo solo se puede mostrar el inventario de radios y los sensores."
        }
    }

    fun startScan() {
        val missing = scanner.requiredPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            message = "Concede los permisos para que el barrido de radiofrecuencia funcione."
            return
        }
        scope.launch {
            scanning = true
            message = null
            val lanDeferred = if (lanEnabled && linkState is PcLinkState.Connected) {
                link.requestLanScan(deep = false)
                scope.async {
                    withTimeoutOrNull(25_000L) {
                        link.incoming.first { it.type == PcProtocol.Type.SCAN_RESULTS }
                    }
                }
            } else {
                null
            }
            val local = scanner.scan(durationSeconds * 1000L)
            val lanResponse = lanDeferred?.await()
            val lanHosts = lanResponse?.list("hosts")
                ?.mapNotNull { entry ->
                    (entry as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                }
                .orEmpty()
            val lanHits = scanner.lanHits(lanHosts)
            report = local.copy(
                hits = (local.hits + lanHits).sortedByDescending { it.rssi ?: -127 },
                perRadio = local.perRadio + (ScanRadio.LAN to lanHits.size),
                notes = local.notes + if (lanEnabled) {
                    listOf(
                        if (lanHosts.isEmpty()) {
                            "LAN: la PC no devolvió hosts (¿está en una red con clientes visibles?)."
                        } else {
                            "LAN: ${lanHosts.size} hosts vistos desde la PC, con alcance de su propia red."
                        },
                    )
                } else {
                    emptyList()
                },
            )
            scanning = false
        }
    }

    PcToolScreen(
        title = "A-D Scanner",
        onBack = onBack,
        actions = {
            PcStatusChip(state = linkState)
        },
    ) {
        PcCard {
            PcSectionTitle(text = "Radios del teléfono", hint = "${inventory.sensorCount} sensores")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RadioChip("WiFi", inventory.wifi && inventory.wifiEnabled)
                RadioChip("WiFi Direct", inventory.wifiDirect)
                RadioChip("WiFi Aware", inventory.wifiAware)
                RadioChip("BLE", inventory.ble && inventory.bluetoothEnabled)
                RadioChip("Bluetooth", inventory.classic)
                RadioChip("NFC", inventory.nfc)
                RadioChip("Telefonía", inventory.cellular)
                RadioChip("GNSS", inventory.gps)
                RadioChip("UWB", inventory.uwb)
                RadioChip("IR", inventory.infrared)
                RadioChip("FM", inventory.fmRadio)
            }
            if (inventory.missingPermissions.isNotEmpty()) {
                PcNote(
                    text = "Faltan permisos: ${inventory.missingPermissions.joinToString()}",
                    isWarning = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 8, 15, 25).forEach { seconds ->
                    FilterChip(
                        selected = durationSeconds == seconds,
                        onClick = { durationSeconds = seconds },
                        label = { Text("${seconds}s") },
                    )
                }
            }
            Button(
                onClick = { startScan() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Barriendo…" else "Barrer entorno")
            }
            if (linkState is PcLinkState.Connected) {
                PcToggleRow(
                    title = "Ampliar con la PC",
                    subtitle = if (link.capabilities.lanScan) {
                        "El agente barre su subred y suma los hosts encontrados"
                    } else {
                        "El agente no ofrece barrido de LAN"
                    },
                    checked = lanEnabled,
                    onCheckedChange = { lanEnabled = it },
                )
            } else {
                Text(
                    text = "Conecta la PC para ampliar el barrido a su red cableada o WiFi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val current = report
        if (current != null) {
            PcCard {
                PcSectionTitle(
                    text = "Resultados",
                    hint = "${current.hits.size} emisores · ${current.durationMillis / 1000} s",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanRadio.entries.forEach { radio ->
                        val count = current.perRadio[radio] ?: 0
                        if (count > 0) {
                            FilterChip(
                                selected = radio in filters,
                                onClick = {
                                    filters = if (radio in filters) filters - radio else filters + radio
                                },
                                label = { Text("${radioLabel(radio)} $count") },
                            )
                        }
                    }
                }
                val visible = current.hits.filter { filters.isEmpty() || it.radio in filters }
                if (visible.isEmpty()) {
                    Text(
                        text = "Sin emisores en este barrido.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                visible.take(80).forEach { hit ->
                    HitRow(
                        hit = hit,
                        expanded = expanded == "${hit.radio}:${hit.address}",
                        onClick = {
                            val key = "${hit.radio}:${hit.address}"
                            expanded = if (expanded == key) null else key
                        },
                    )
                }
            }
            if (current.notes.isNotEmpty()) {
                PcCard {
                    PcSectionTitle(text = "Notas del barrido")
                    current.notes.forEach {
                        Text(
                            text = "• $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { report = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Descartar resultados") }
        } else {
            PcCard {
                Text(
                    text = "Un barrido de ${durationSeconds}s escucha anuncios BLE, lee los puntos de acceso WiFi " +
                        "visibles y las celdas registradas. El WiFi se lee de la caché del sistema, así que en " +
                        "Android 13+ puede tardar unos segundos en refrescar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PcNote(
            text = "Alcance real: WiFi ≈ 40–100 m según el entorno, BLE ≈ 10–30 m, telefonía varios km, " +
                "NFC ≈ 4 cm, GNSS recibe satélites (no hay emisores cercanos que descubrir). " +
                "La distancia mostrada es una estimación por RSSI, no una medición.",
        )
        PcNote(
            text = "No se leen coordenadas ni se identifican personas: solo emisores, intensidad y características " +
                "públicas. Los resultados quedan en el teléfono.",
        )
    }
}

@Composable
private fun RadioChip(label: String, available: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (available) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (available) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun HitRow(hit: ScanHit, expanded: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = hit.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = hit.rssi?.let { "$it dBm" } ?: radioLabel(hit.radio),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        hit.rssi == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        hit.rssi >= -60 -> MaterialTheme.colorScheme.secondary
                        hit.rssi >= -80 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
            Text(
                text = "${radioLabel(hit.radio)} · ${hit.address} · ${hit.distanceLabel()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                Text(
                    text = hit.detail.ifBlank { "Sin más datos publicados por el emisor." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Visto ${hit.occurrences} vez/veces · primera vez ${PcFormat.clock(hit.firstSeenMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun radioLabel(radio: ScanRadio): String = when (radio) {
    ScanRadio.WIFI -> "WiFi"
    ScanRadio.BLUETOOTH -> "Bluetooth"
    ScanRadio.CELLULAR -> "Celular"
    ScanRadio.NFC -> "NFC"
    ScanRadio.GPS -> "GNSS"
    ScanRadio.LAN -> "LAN"
    ScanRadio.SENSOR -> "Sensor"
}
