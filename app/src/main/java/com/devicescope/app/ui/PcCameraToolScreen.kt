package com.devicescope.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.link.PcCameraStreamer

/**
 * Cámara del móvil como webcam.
 *
 * El agente abre una ventana con la imagen (y si tienes `v4l2loopback`, la
 * publica como `/dev/videoN`, que es lo que ven Zoom, Meet u OBS). Todo el vídeo
 * va por la LAN: nada se sube a Internet.
 */
@Composable
fun PcCameraToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val context = LocalContext.current
    val state by link.state.collectAsState()
    val streamer = remember { PcCameraStreamer(context, link) }
    val cameraState by streamer.state.collectAsState()
    val streamerRef by rememberUpdatedState(streamer)

    var fps by remember { mutableFloatStateOf(5f) }
    var quality by remember { mutableFloatStateOf(65f) }
    var useBackCamera by remember { mutableStateOf(true) }
    var permissionError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) permissionError = "Sin permiso de cámara no se puede transmitir."
    }

    DisposableEffect(Unit) {
        onDispose { streamerRef.stop() }
    }

    PcToolScreen(
        title = "Cámara",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            PcSectionTitle(
                text = "Cámara como webcam",
                hint = "${streamer.cameraCount()} cámara(s)",
            )
            Text(
                text = when (val current = cameraState) {
                    is PcCameraStreamer.CameraState.Streaming ->
                        "Transmitiendo ${current.width}×${current.height} · ${current.frames} fotogramas · " +
                            PcFormat.bytes(current.bytes)
                    is PcCameraStreamer.CameraState.Starting -> current.detail
                    is PcCameraStreamer.CameraState.Error -> current.reason
                    PcCameraStreamer.CameraState.Idle -> "Detenida"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (cameraState is PcCameraStreamer.CameraState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!link.capabilities.cameraWebcam) {
                PcNote(
                    text = "El agente no anunció soporte de cámara: actualízalo o instala sus dependencias " +
                        "opcionales. Aun así el teléfono puede enviar fotogramas; la PC solo los mostrará si " +
                        "puede escribir una ventana.",
                    isWarning = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { useBackCamera = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Trasera") }
                OutlinedButton(
                    onClick = { useBackCamera = false },
                    modifier = Modifier.weight(1f),
                ) { Text("Frontal") }
            }
            Column {
                Row {
                    Text(
                        text = "Fotogramas por segundo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${fps.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = fps,
                    onValueChange = {
                        fps = it
                        streamer.setFps(it.toInt())
                    },
                    valueRange = 1f..12f,
                    steps = 10,
                )
                Row {
                    Text(
                        text = "Calidad JPEG",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${quality.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 30f..92f,
                )
            }
            val streaming = cameraState is PcCameraStreamer.CameraState.Streaming
            Button(
                onClick = {
                    if (streaming) {
                        streamer.stop()
                    } else {
                        if (!streamer.hasCameraPermission) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                            as? android.hardware.camera2.CameraManager
                        val ids = runCatching { manager?.cameraIdList?.toList().orEmpty() }
                            .getOrDefault(emptyList())
                        val chosen = if (useBackCamera) {
                            ids.firstOrNull { id ->
                                runCatching {
                                    manager?.getCameraCharacteristics(id)
                                        ?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                                }.getOrNull() == true
                            } ?: ids.firstOrNull()
                        } else {
                            ids.firstOrNull { id ->
                                runCatching {
                                    manager?.getCameraCharacteristics(id)
                                        ?.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                                }.getOrNull() == true
                            } ?: ids.firstOrNull()
                        }
                        val result = streamer.start(chosen, fps.toInt(), quality.toInt())
                        permissionError = result.exceptionOrNull()?.localizedMessage
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (streaming) "Detener transmisión" else "Iniciar transmisión")
            }
            permissionError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        PcNote(
            text = "En la PC: el agente abre una ventana de vista previa. Para usarla como cámara del sistema, " +
                "instala v4l2loopback (Linux) o usa OBS Virtual Camera y apunta la ventana del agente.",
        )
        PcNote(
            text = "Los fotogramas solo viajan a la PC emparejada, comprimidos en JPEG, y no se guardan en el " +
                "teléfono ni en el agente.",
        )
    }
}
