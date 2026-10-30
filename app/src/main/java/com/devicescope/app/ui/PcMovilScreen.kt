package com.devicescope.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcDevice
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.PcMetrics
import com.devicescope.app.pcmovil.link.PcLinkManager
import com.devicescope.app.pcmovil.link.PcProtocol
import com.devicescope.app.pcmovil.link.PcRemoteInputService
import kotlinx.coroutines.launch

/**
 * Panel del módulo PC-Móvil: emparejamiento y catálogo de herramientas.
 *
 * El emparejamiento es local: descubrimiento UDP en la LAN, PIN de 6 dígitos que
 * muestra el agente y un token que queda guardado en el teléfono. No hay cuenta,
 * ni nube, ni intermediario.
 */
@Composable
fun PcMovilScreen(
    onBack: () -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val link = rememberPcLink()
    val state by link.state.collectAsState()
    val metrics by link.metrics.collectAsState()
    val discovering by link.discovering.collectAsState()
    val log by link.log.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var announcements by remember { mutableStateOf<List<PcProtocol.AgentAnnouncement>>(emptyList()) }
    var devices by remember { mutableStateOf(link.devices()) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(PcProtocol.DEFAULT_PORT.toString()) }
    var pairingPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lastDiscoveryAt by remember { mutableStateOf(0L) }
    var connectionHelp by remember { mutableStateOf(false) }

    fun refreshDevices() {
        devices = link.devices()
    }

    fun startDiscovery() {
        scope.launch {
            message = null
            announcements = link.discover()
            lastDiscoveryAt = System.currentTimeMillis()
            refreshDevices()
            if (announcements.isEmpty()) {
                message = "No se encontró ningún agente en la red. Comprueba que el agente esté en marcha, " +
                    "USB compartido, punto de acceso o misma Wi-Fi; permite 8788/UDP en el firewall."
            }
        }
    }

    fun connectTo(host: String, targetPort: Int, name: String?) {
        scope.launch {
            message = null
            val result = link.connect(host, targetPort, deviceName = name)
            if (result.isFailure) {
                message = result.exceptionOrNull()?.localizedMessage
            }
            refreshDevices()
        }
    }

    val pairingState = state as? PcLinkState.PairingRequired

    PcToolScreen(
        title = "PC-Móvil",
        onBack = onBack,
        actions = {
            if (state is PcLinkState.Connected) {
                PcTextButton(text = "Desconectar", onClick = {
                    link.disconnect()
                    refreshDevices()
                })
            } else {
                PcStatusChip(state = state)
            }
            Spacer(Modifier.width(4.dp))
        },
    ) {
        PcCard {
            PcSectionTitle(text = "USB → punto de acceso → misma Wi-Fi")
            TextButton(onClick = { connectionHelp = !connectionHelp }) {
                Text(if (connectionHelp) "Ocultar ayuda" else "Configurar conexiones y uso por herramienta")
            }
            if (connectionHelp) {
            Text("1. USB: conecta el cable y activa Compartir Internet por USB.")
            Text("2. Punto de acceso: actívalo en el móvil y conecta la PC.")
            Text("3. Misma Wi-Fi: conecta ambos al mismo router.")
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }) { Text("Configurar conexión") }
            Text("Inicia el agente en la PC y pulsa Buscar. Las rutas detectadas se ordenan por esa prioridad; elige la PC para conectar. Si no aparece, usa su IP local.")
            Text("Cámara, audio, espejo y archivos: prefiere USB por estabilidad; luego punto de acceso. Teclado, mouse y recursos funcionan también por Wi-Fi. VPN usa el enlace elegido. El escáner de radios y el diagnóstico no necesitan PC; el barrido LAN sí.")
            }
        }

        when (state) {
            is PcLinkState.Connected -> ConnectedPanel(
                link = link,
                state = state,
                metrics = metrics,
                onDisconnect = {
                    link.disconnect()
                    refreshDevices()
                },
                onForget = {
                    link.currentEndpoint?.let { endpoint ->
                        link.devices().firstOrNull { it.endpoint == endpoint }?.let { link.store.remove(it.id) }
                    }
                    link.disconnect()
                    refreshDevices()
                },
            )

            else -> ConnectPanel(
                state = state,
                address = address,
                port = port,
                announcements = announcements,
                devices = devices,
                discovering = discovering,
                message = message,
                lastDiscoveryAt = lastDiscoveryAt,
                onAddressChange = { address = it },
                onPortChange = { port = it },
                onDiscover = { startDiscovery() },
                onConnectManual = {
                    val host = address.trim()
                    val targetPort = port.toIntOrNull() ?: PcProtocol.DEFAULT_PORT
                    if (host.isEmpty()) {
                        message = "Escribe la IP de la PC (por ejemplo 192.168.1.42)."
                    } else {
                        connectTo(host, targetPort, null)
                    }
                },
                onConnectAnnouncement = { announcement ->
                    address = announcement.address
                    port = announcement.port.toString()
                    connectTo(announcement.address, announcement.port, announcement.name)
                },
                onConnectDevice = { device -> connectTo(device.address, device.port, device.name) },
                onForgetDevice = { device ->
                    link.store.remove(device.id)
                    refreshDevices()
                },
                onRefresh = { refreshDevices() },
            )
        }

        if (state is PcLinkState.Connecting || discovering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (discovering) "Barriendo la red local…" else "Estableciendo el enlace…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PcSectionTitle(text = "Herramientas", hint = "${pcTools.size} disponibles")
        pcTools.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        connected = state is PcLinkState.Connected,
                        onClick = { onOpenTool(tool.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        PcSectionTitle(text = "Estado del enlace")
        PcCard {
            PcMetricRow(
                label = "Este teléfono",
                value = link.phoneName,
                hint = "Huella ${link.phoneFingerprint}",
            )
            PcMetricRow(
                label = "Equipos recordados",
                value = devices.size.toString(),
                hint = devices.joinToString { it.name }.ifBlank { "Ninguno todavía" },
            )
            val serviceOn = PcRemoteInputService.connected.collectAsState().value
            PcMetricRow(
                label = "Entrada desde la PC",
                value = if (serviceOn) "Activa" else "Desactivada",
                hint = "Servicio de accesibilidad para escribir y tocar el móvil desde la PC",
                valueColor = if (serviceOn) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (serviceOn) "Revisar accesibilidad" else "Activar entrada desde la PC")
            }
            if (log.isNotEmpty()) {
                Text(
                    text = "Registro",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                log.takeLast(4).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        PcNote(text = "Tráfico sin cifrar. Usa solo conexiones de confianza.", isWarning = true)
    }

    if (pairingState != null) {
        AlertDialog(
            onDismissRequest = { link.disconnect() },
            title = { Text("Emparejar con ${pairingState.deviceName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Escribe el PIN de 6 dígitos que muestra el agente en la PC.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pairingPin,
                        onValueChange = { value ->
                            pairingPin = value.filter { it.isDigit() }.take(6)
                        },
                        label = { Text("PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    pairingState.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    pairingState.attemptsLeft?.let {
                        Text(
                            text = "Intentos restantes: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pin = pairingPin
                        scope.launch {
                            val result = link.pair(pin)
                            pairingPin = ""
                            if (result.isFailure) {
                                message = result.exceptionOrNull()?.localizedMessage
                            }
                            refreshDevices()
                        }
                    },
                    enabled = pairingPin.length >= 4,
                ) { Text("Emparejar") }
            },
            dismissButton = {
                TextButton(onClick = { link.disconnect() }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun ToolCard(
    tool: PcTool,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = connected || !tool.requiresLink
    Surface(
        modifier = modifier
            .height(150.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(13.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = tool.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = tool.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!enabled) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Conecta la PC",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ConnectedPanel(
    link: PcLinkManager,
    state: PcLinkState,
    metrics: PcMetrics?,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    val agent = link.connectedAgent
    PcCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent?.name ?: "PC",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        agent?.platform,
                        agent?.version?.takeIf { it.isNotBlank() }?.let { "agente $it" },
                        agent?.endpoint,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PcStatusChip(state = state)
        }
        Text(
            text = "Capacidades: ${agent?.capabilities?.summary() ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (metrics != null) {
            PcProgress(
                fraction = (metrics.cpuPercent / 100.0).toFloat(),
                label = "CPU",
                valueLabel = PcFormat.percent(metrics.cpuPercent),
            )
            PcProgress(
                fraction = metrics.ramPercent,
                label = "RAM",
                valueLabel = "${PcFormat.bytes(metrics.ramUsedBytes)} / ${PcFormat.bytes(metrics.ramTotalBytes)}",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Desconectar")
            }
            OutlinedButton(onClick = onForget, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Olvidar")
            }
        }
    }
}

@Composable
private fun ConnectPanel(
    state: PcLinkState,
    address: String,
    port: String,
    announcements: List<PcProtocol.AgentAnnouncement>,
    devices: List<PcDevice>,
    discovering: Boolean,
    message: String?,
    lastDiscoveryAt: Long,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDiscover: () -> Unit,
    onConnectManual: () -> Unit,
    onConnectAnnouncement: (PcProtocol.AgentAnnouncement) -> Unit,
    onConnectDevice: (PcDevice) -> Unit,
    onForgetDevice: (PcDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    PcCard {
        Text(
            text = "Conectar con la PC",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "En la PC ejecuta «python pc-agent/agent.py» y mira el PIN que imprime. " +
                "Prioridad: USB → punto de acceso → misma Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDiscover,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pc-discover"),
            enabled = !discovering,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (discovering) "Buscando…" else "Buscar en la red local")
        }
        if (lastDiscoveryAt > 0L) {
            Text(
                text = "Último barrido ${PcFormat.clock(lastDiscoveryAt)} · ${announcements.size} agente(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        announcements.forEach { announcement ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConnectAnnouncement(announcement) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = announcement.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${announcement.address}:${announcement.port} · ${announcement.platform}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Conectar",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    PcCard {
        PcSectionTitle(text = "Dirección manual")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = address,
                onValueChange = onAddressChange,
                label = { Text("IP o nombre") },
                singleLine = true,
                modifier = Modifier.weight(2f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { value -> onPortChange(value.filter { it.isDigit() }.take(5)) },
                label = { Text("Puerto") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        FilledTonalButton(onClick = onConnectManual, modifier = Modifier.fillMaxWidth()) {
            Text("Conectar a esa dirección")
        }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        (state as? PcLinkState.Failed)?.let { failed ->
            Text(
                text = failed.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (devices.isNotEmpty()) {
        PcCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PcSectionTitle(text = "Equipos recordados", hint = "${devices.size}")
                Spacer(Modifier.weight(1f))
                PcIconAction(Icons.Filled.Refresh, description = "Actualizar lista", onClick = onRefresh)
            }
            devices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${device.endpoint} · ${if (device.paired) "emparejado" else "sin token"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onConnectDevice(device) }) { Text("Conectar") }
                    TextButton(onClick = { onForgetDevice(device) }) { Text("Olvidar") }
                }
            }
        }
    }
}
