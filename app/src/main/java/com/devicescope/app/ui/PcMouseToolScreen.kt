package com.devicescope.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.PcLinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mouse y touchpad en las dos direcciones.
 *
 * - **Touchpad**: mueve el puntero de la PC en relativo (preciso en pantallas
 *   grandes), con clic izquierdo/derecho/medio, doble clic, arrastre y rueda.
 * - **Espejo**: dibuja la pantalla de la PC y convierte el toque en posición
 *   absoluta; tocar es hacer clic donde tocaste.
 * - **PC → móvil**: el agente envía movimientos/clics que el servicio de
 *   accesibilidad convierte en gestos sobre el teléfono.
 */
@Composable
fun PcMouseToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val state by link.state.collectAsState()
    val frame by link.mirrorFrame.collectAsState()

    var speed by remember { mutableFloatStateOf(link.store.pointerSpeed) }
    var scrollMode by remember { mutableStateOf(false) }
    var mirrorOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    var quality by remember { mutableFloatStateOf(link.store.mirrorQuality.toFloat()) }
    var fps by remember { mutableFloatStateOf(link.store.mirrorFps.toFloat()) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var frames by remember { mutableStateOf(0) }
    var lastSize by remember { mutableStateOf("") }
    val mirrorRef by rememberUpdatedState(mirrorOn)

    LaunchedEffect(frame) {
        val bytes = frame ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        if (decoded != null) {
            image = decoded
            lastSize = "${decoded.width}×${decoded.height}"
            frames += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (mirrorRef) link.stopMirror()
            link.stopInputCapture()
        }
    }

    PcToolScreen(
        title = "Mouse",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PcSectionTitle(text = if (scrollMode) "Touchpad · rueda" else "Touchpad", hint = "arrastra para mover")
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(scrollMode, speed) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (scrollMode) {
                                scrollAccumulator += -dragAmount.y / 24f
                                val steps = scrollAccumulator.toInt()
                                if (steps != 0) {
                                    scrollAccumulator -= steps
                                    link.sendScroll(steps.toFloat())
                                }
                            } else {
                                link.sendMouseMove(dragAmount.x * speed, dragAmount.y * speed)
                            }
                        }
                    }
                    .pointerInput(scrollMode) {
                        detectTapGestures(
                            onTap = { if (!scrollMode) link.sendClick("left") },
                            onDoubleTap = { if (!scrollMode) link.sendClick("left", double = true) },
                            onLongPress = {
                                if (!scrollMode) {
                                    link.sendMouseButton("left", pressed = true)
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                if (!scrollMode) link.sendMouseButton("left", pressed = false)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Mouse,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (scrollMode) "Desliza arriba/abajo para hacer scroll" else "Desliza para mover · toca para clic",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scrollMode = !scrollMode },
                    modifier = Modifier.weight(1f),
                ) { Text(if (scrollMode) "Modo puntero" else "Modo rueda") }
                OutlinedButton(
                    onClick = { link.sendClick("right") },
                    modifier = Modifier.weight(1f),
                ) { Text("Clic derecho") }
                OutlinedButton(
                    onClick = { link.sendClick("middle") },
                    modifier = Modifier.weight(1f),
                ) { Text("Medio") }
            }
            Column {
                Row {
                    Text(
                        text = "Sensibilidad",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = String.format("%.2f×", speed),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Slider(
                    value = speed,
                    onValueChange = {
                        speed = it
                        link.store.pointerSpeed = it
                    },
                    valueRange = 0.3f..3f,
                )
            }
        }

        PcCard {
            PcToggleRow(
                title = "Ver la pantalla de la PC",
                subtitle = if (link.capabilities.screenMirror) {
                    "Toca directamente sobre la pantalla espejada"
                } else {
                    "El agente no tiene captura de pantalla instalada (mss o Pillow)"
                },
                checked = mirrorOn,
                onCheckedChange = { enabled ->
                    mirrorOn = enabled
                    if (enabled) {
                        frames = 0
                        link.startMirror(quality.toInt(), fps.toInt(), 0.6f)
                    } else {
                        link.stopMirror()
                        image = null
                    }
                },
            )
            if (mirrorOn) {
                Text(
                    text = "Fotogramas recibidos: $frames · resolución $lastSize",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val current = image
                if (current == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Esperando el primer fotograma…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Image(
                        bitmap = current,
                        contentDescription = "Pantalla de la PC",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(current.width.toFloat() / current.height.toFloat())
                            .clip(RoundedCornerShape(14.dp))
                            .pointerInput(current) {
                                detectTapGestures { offset ->
                                    link.sendMouseMoveAbsolute(
                                        offset.x / size.width.toFloat(),
                                        offset.y / size.height.toFloat(),
                                    )
                                    link.sendClick("left")
                                }
                            }
                            .pointerInput(current) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    link.sendMouseMoveAbsolute(
                                        change.position.x / size.width.toFloat(),
                                        change.position.y / size.height.toFloat(),
                                    )
                                }
                            },
                    )
                }
                Row {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Calidad ${quality.toInt()} · ${fps.toInt()} fps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Slider(
                    value = quality,
                    onValueChange = {
                        quality = it
                        link.store.mirrorQuality = it.toInt()
                    },
                    valueRange = 20f..85f,
                )
                Slider(
                    value = fps,
                    onValueChange = {
                        fps = it
                        link.store.mirrorFps = it.toInt()
                        link.startMirror(quality.toInt(), fps.toInt(), 0.6f)
                    },
                    valueRange = 2f..15f,
                    steps = 12,
                )
                PcNote(
                    text = "El espejo viaja como JPEG por el canal MIRROR. Más fps y calidad = más ancho de banda " +
                        "en la WiFi; 8 fps y calidad 55 suele ser suficiente para tocar con precisión.",
                )
            }
        }

        PcCard {
            PcSectionTitle(text = "Puntero de la PC sobre el móvil")
            PcToggleRow(
                title = if (capturing) "Capturando tu teclado y mouse" else "Captura desactivada",
                subtitle = "Mientras esté activo, lo que escribas y los clics en la PC llegan al teléfono",
                checked = capturing,
                onCheckedChange = { enabled ->
                    capturing = enabled && link.startInputCapture()
                    if (!enabled) link.stopInputCapture()
                },
            )
            Text(
                text = "Con el servicio de accesibilidad activo, el agente puede enviar toques, dobles toques y " +
                    "deslizamientos que se dibujan sobre la pantalla del teléfono. Es el sentido inverso del touchpad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        link.sendGesture("tap", 0.5f, 0.5f, 0.5f, 0.5f, 60L)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Probar toque") }
                OutlinedButton(
                    onClick = {
                        link.sendGesture("swipe", 0.5f, 0.8f, 0.5f, 0.2f, 320L)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Probar deslizar") }
            }
        }
    }
}
