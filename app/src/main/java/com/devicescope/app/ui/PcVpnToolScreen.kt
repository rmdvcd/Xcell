package com.devicescope.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.VpnState
import com.devicescope.app.pcmovil.link.PcVpnService
import kotlinx.coroutines.launch

/**
 * VPN hacia la red de la PC.
 *
 * Se crea un `tun` IPv4 con las redes privadas marcadas y cada conexión sale por
 * el relay del agente. No hay servidor externo ni proveedor: si la PC está en la
 * misma WiFi, el túnel sirve para llegar a equipos que el teléfono no alcanza
 * (otra subred, otro segmento, un servidor con IP privada).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PcVpnToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val context = LocalContext.current
    val state by link.state.collectAsState()
    val vpnState by PcVpnService.state.collectAsState()
    val stats by PcVpnService.stats.collectAsState()
    val log by PcVpnService.log.collectAsState()
    val scope = rememberCoroutineScope()

    var routes by remember { mutableStateOf(link.store.vpnAllowedNetworks.toList().sorted()) }
    var customRoute by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var allTraffic by remember { mutableStateOf(routes.contains("0.0.0.0/0")) }
    var pendingRelay by remember { mutableStateOf<Pair<String, Int>?>(null) }

    fun persist(newRoutes: List<String>) {
        routes = newRoutes.distinct().sorted()
        link.store.vpnAllowedNetworks = routes.toSet()
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val relay = pendingRelay
        pendingRelay = null
        if (result.resultCode == Activity.RESULT_OK && relay != null) {
            PcVpnService.requestStart(context, relay.first, relay.second)
        } else if (result.resultCode != Activity.RESULT_OK) {
            message = "El sistema no autorizó la VPN."
        }
    }

    fun openTunnel(host: String, port: Int) {
        val consent = VpnService.prepare(context)
        if (consent != null) {
            pendingRelay = host to port
            consentLauncher.launch(consent)
        } else {
            PcVpnService.requestStart(context, host, port)
        }
    }

    fun startTunnel() {
        scope.launch {
            message = null
            link.requestVpnRelay()
                .onSuccess { (host, port) -> openTunnel(host, port) }
                .onFailure { message = it.localizedMessage }
        }
    }

    PcToolScreen(
        title = "VPN",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            PcSectionTitle(text = "Estado del túnel")
            when (val current = vpnState) {
                is VpnState.On -> {
                    val minutes = ((System.currentTimeMillis() - current.sinceMillis) / 60_000).coerceAtLeast(0)
                    PcMetricRow(label = "Conectada", value = "Sí", hint = "Relay ${current.endpoint}")
                    PcMetricRow(label = "Salida", value = current.host, hint = "Sockets abiertos desde la PC")
                    PcMetricRow(label = "Rutas", value = "${current.routes} redes", hint = "Activa hace $minutes min")
                    PcMetricRow(label = "Tráfico", value = stats.throughputLabel, hint = "Contadores del motor")
                }
                is VpnState.Preparing -> Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is VpnState.Error -> Text(
                    text = current.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                VpnState.Off -> Text(
                    text = "Túnel detenido.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val active = vpnState is VpnState.On
            if (!active) {
                Button(onClick = ::startTunnel, modifier = Modifier.fillMaxWidth()) {
                    Text("Activar túnel")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        PcVpnService.requestStop(context)
                        link.closeVpn()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Desactivar túnel") }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Redes que entran por el túnel")
            listOf(
                "192.168.0.0/16" to "LAN doméstica (192.168.x.x)",
                "10.0.0.0/8" to "Empresas y VPN clásicas (10.x.x.x)",
                "172.16.0.0/12" to "Docker y rangos medios (172.16–31)",
            ).forEach { (cidr, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = cidr in routes,
                        onCheckedChange = { on ->
                            persist(if (on) routes + cidr else routes - cidr)
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Todo el tráfico (0.0.0.0/0)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = allTraffic,
                    onCheckedChange = { on ->
                        allTraffic = on
                        persist(if (on) routes + "0.0.0.0/0" else routes - "0.0.0.0/0")
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customRoute,
                    onValueChange = { customRoute = it.take(20) },
                    label = { Text("Añadir red (CIDR)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val value = customRoute.trim()
                        val valid = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(/(\\d{1,2}))?$").matches(value)
                        if (valid) {
                            persist(routes + value)
                            customRoute = ""
                        } else {
                            message = "Formato esperado: 172.20.0.0/16"
                        }
                    },
                ) { Text("Añadir") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                routes.forEach { cidr ->
                    AssistChip(
                        onClick = { persist(routes - cidr) },
                        label = { Text(cidr) },
                    )
                }
            }
            Text(
                text = "Los cambios de rutas se aplican al reiniciar el túnel.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (log.isNotEmpty()) {
            PcCard {
                PcSectionTitle(text = "Registro del motor")
                log.takeLast(6).forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }

        PcNote(
            text = "Alcance real: IPv4, TCP y UDP. ICMP (ping) y IPv6 se descartan. Las conexiones abiertas antes " +
                "de activar la VPN siguen por su ruta original, y todo el tráfico del túnel sale con la IP de la PC.",
        )
        PcNote(
            text = "No es un servicio de anonimato: es tu propia PC haciendo de puerta hacia su red. Si marcas " +
                "«todo el tráfico», tu navegación pasará por la PC y por la red a la que ella esté conectada.",
            isWarning = true,
        )
    }
}
