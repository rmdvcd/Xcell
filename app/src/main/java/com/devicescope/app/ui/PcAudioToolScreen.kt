package com.devicescope.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.link.PcAudioBridge

/**
 * Micrófono y altavoces.
 *
 * - Micrófono del teléfono → la PC lo reproduce como micrófono virtual del
 *   sistema (el agente necesita `sounddevice`).
 * - Audio de la PC → altavoces del teléfono, capturando la salida del sistema
 *   por loopback (WASAPI en Windows, monitor de PulseAudio en Linux).
 */
@Composable
fun PcAudioToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val context = LocalContext.current
    val state by link.state.collectAsState()
    val bridge = remember { PcAudioBridge(context, link) }
    val bridgeRef by rememberUpdatedState(bridge)
    val micActive by bridge.micActive.collectAsState()
    val speakerActive by bridge.speakerActive.collectAsState()
    val micLevel by bridge.micLevel.collectAsState()
    val speakerLevel by bridge.speakerLevel.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            error = bridge.startMic().exceptionOrNull()?.localizedMessage
        } else {
            error = "Sin permiso de micrófono no se puede enviar audio a la PC."
        }
    }

    DisposableEffect(Unit) {
        onDispose { bridgeRef.stopAll() }
    }

    PcToolScreen(
        title = "Micrófono y altavoces",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            PcSectionTitle(text = "Micrófono del móvil → PC", hint = "PCM16 mono 48 kHz")
            PcToggleRow(
                title = if (micActive) "Enviando audio" else "Micrófono apagado",
                subtitle = if (link.capabilities.micCapture) {
                    "El agente reproduce el audio en la PC"
                } else {
                    "El agente no tiene reproducción de audio instalada (sounddevice)"
                },
                checked = micActive,
                onCheckedChange = { enabled ->
                    error = null
                    if (enabled) {
                        if (!bridge.hasRecordPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            error = bridge.startMic().exceptionOrNull()?.localizedMessage
                        }
                    } else {
                        bridge.stopMic()
                    }
                },
            )
            LevelMeter(level = micLevel, label = "Nivel del micrófono", color = MaterialTheme.colorScheme.primary)
            if (!link.capabilities.micCapture) {
                PcNote(
                    text = "Instala sounddevice en la PC (pip install sounddevice) y reinicia el agente para " +
                        "escuchar el micrófono del teléfono como entrada de audio.",
                    isWarning = true,
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Audio de la PC → altavoces del móvil")
            PcToggleRow(
                title = if (speakerActive) "Reproduciendo en el teléfono" else "Altavoces en silencio",
                subtitle = if (link.capabilities.speakerPlayback) {
                    "Captura de la salida del sistema en la PC"
                } else {
                    "El agente no puede capturar su salida de audio"
                },
                checked = speakerActive,
                onCheckedChange = { enabled ->
                    error = null
                    if (enabled) {
                        error = bridge.startSpeaker().exceptionOrNull()?.localizedMessage
                    } else {
                        bridge.stopSpeaker()
                    }
                },
            )
            LevelMeter(level = speakerLevel, label = "Nivel recibido", color = MaterialTheme.colorScheme.secondary)
            if (!link.capabilities.speakerPlayback) {
                PcNote(
                    text = "La captura de salida necesita un dispositivo de loopback: «Mezcla estéreo» en Windows " +
                        "o el monitor del sink en PipeWire/PulseAudio. Se configura en el agente.",
                    isWarning = true,
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Latencia")
            Text(
                text = "Los bloques son de 20 ms y se envían por el mismo socket del control. En una WiFi decente " +
                    "la latencia extremo a extremo queda entre 150 y 400 ms: sirve para hablar y para escuchar " +
                    "música de fondo, no para monitoreo en vivo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        PcNote(
            text = "El audio se procesa en memoria: nada se graba en el teléfono ni en la PC. Al cerrar esta " +
                "pantalla, el micrófono y la reproducción se detienen.",
        )
    }
}

@Composable
private fun LevelMeter(level: Float, label: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = PcFormat.percent(level * 100.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            LinearProgressIndicator(
                progress = { level.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .padding(top = 4.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surface,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        }
    }
}
