package com.devicescope.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devicescope.app.pcmovil.data.InputTarget
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.RemoteKey
import com.devicescope.app.pcmovil.link.PcRemoteInputService

/**
 * Teclado bidireccional.
 *
 * Móvil → PC: el campo de texto calcula el delta de lo escrito y envía texto o
 * retrocesos; los atajos se envían como teclas con modificadores.
 * PC → Móvil: el agente manda texto y el servicio de accesibilidad lo inyecta en
 * el campo con foco del teléfono (o lo deja en el portapapeles).
 */
@Composable
fun PcKeyboardToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val state by link.state.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val received by PcRemoteInputService.lastText.collectAsState()
    val injected by PcRemoteInputService.lastInjected.collectAsState()
    val serviceOn by PcRemoteInputService.connected.collectAsState()

    var direction by remember { mutableStateOf(InputTarget.PC) }
    var text by remember { mutableStateOf("") }
    var synced by remember { mutableStateOf("") }
    var modifiers by remember { mutableStateOf(setOf<String>()) }
    var info by remember { mutableStateOf<String?>(null) }

    fun sendToPc(newValue: String) {
        if (newValue == synced) return
        if (newValue.startsWith(synced)) {
            val added = newValue.removePrefix(synced)
            if (added.isNotBlank() || added == " ") link.sendText(added, InputTarget.PC)
        } else {
            var common = 0
            val limit = minOf(synced.length, newValue.length)
            while (common < limit && synced[common] == newValue[common]) common++
            repeat(synced.length - common) {
                link.sendKey(RemoteKey.BACKSPACE, emptySet(), InputTarget.PC)
            }
            val rest = newValue.substring(common)
            if (rest.isNotEmpty()) link.sendText(rest, InputTarget.PC)
        }
        synced = newValue
    }

    PcToolScreen(
        title = "Teclado",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            PcSectionTitle(text = "Dirección")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = direction == InputTarget.PC,
                    onClick = { direction = InputTarget.PC },
                    label = { Text("Móvil → PC") },
                )
                FilterChip(
                    selected = direction == InputTarget.PHONE,
                    onClick = { direction = InputTarget.PHONE },
                    label = { Text("PC → Móvil") },
                )
            }
            Text(
                text = if (direction == InputTarget.PC) {
                    "Lo que escribas aquí se teclea en la PC que tiene el foco."
                } else {
                    "El teclado de la PC escribe en el teléfono. Requiere el servicio de accesibilidad activo."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (direction == InputTarget.PC) {
            PcCard {
                PcSectionTitle(text = "Texto", hint = "${synced.length} caracteres enviados")
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        text = value
                        sendToPc(value)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    placeholder = { Text("Escribe y aparecerá en la PC…") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            link.sendKey(RemoteKey.ENTER, modifiers, InputTarget.PC)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Enter") }
                    OutlinedButton(
                        onClick = {
                            if (text.isNotEmpty()) {
                                link.sendKey(RemoteKey.BACKSPACE, emptySet(), InputTarget.PC)
                                text = text.dropLast(1)
                                synced = text
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Borrar") }
                    OutlinedButton(
                        onClick = {
                            text = ""
                            synced = ""
                            info = "Campo local limpio. La PC conserva su propio texto."
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Limpiar") }
                }
                info?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            PcCard {
                PcSectionTitle(text = "Modificadores")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift", "win" to "Win").forEach { (key, label) ->
                        FilterChip(
                            selected = key in modifiers,
                            onClick = {
                                modifiers = if (key in modifiers) modifiers - key else modifiers + key
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    text = "Los modificadores se aplican a las teclas de abajo, no al texto libre.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PcCard {
                PcSectionTitle(text = "Teclas y atajos")
                val groups = listOf(
                    listOf(RemoteKey.ENTER, RemoteKey.TAB, RemoteKey.ESCAPE, RemoteKey.BACKSPACE, RemoteKey.DELETE),
                    listOf(RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT),
                    listOf(RemoteKey.HOME, RemoteKey.END, RemoteKey.PAGE_UP, RemoteKey.PAGE_DOWN),
                    listOf(RemoteKey.SELECT_ALL, RemoteKey.COPY, RemoteKey.PASTE, RemoteKey.CUT),
                    listOf(RemoteKey.UNDO, RemoteKey.REDO, RemoteKey.SAVE, RemoteKey.ALT_TAB),
                    listOf(RemoteKey.PLAY_PAUSE, RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.NEXT_TRACK),
                )
                groups.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { key ->
                            KeyChip(
                                label = key.label,
                                modifier = Modifier.weight(1f),
                                onClick = { link.sendKey(key, modifiers, InputTarget.PC) },
                            )
                        }
                        if (row.size < 5) repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            PcCard {
                PcSectionTitle(text = "Portapapeles")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text.orEmpty()
                            if (clip.isEmpty()) {
                                info = "El portapapeles del teléfono está vacío."
                            } else {
                                link.setClipboard(clip)
                                info = "Enviados ${clip.length} caracteres al portapapeles de la PC."
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Móvil → PC") }
                    OutlinedButton(
                        onClick = { link.requestClipboard() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pedir el de la PC") }
                }
                Text(
                    text = "El portapapeles de la PC llega como evento y se copia en el teléfono automáticamente.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PcCard {
                PcSectionTitle(text = "Entrada desde la PC")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (serviceOn) {
                            "Servicio de accesibilidad activo: el texto se inyecta en el campo con foco."
                        } else {
                            "Servicio desactivado. Sin él, lo que escribas en la PC solo se verá en esta pantalla."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(onClick = onOpenPanel, modifier = Modifier.fillMaxWidth()) {
                    Text("Abrir ajustes del módulo")
                }
            }
            PcCard {
                PcSectionTitle(text = "Receptor", hint = "Últimas pulsaciones")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = received.ifBlank { "Todavía no llegó texto desde la PC." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = "Inyectado: ${injected.ifBlank { "—" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(received))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = received.isNotEmpty(),
                    ) { Text("Copiar") }
                    OutlinedButton(
                        onClick = { link.sendKey(RemoteKey.ENTER, emptySet(), InputTarget.PC) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Probar Enter") }
                }
            }
            PcNote(
                text = "Android no permite escribir en otras apps sin accesibilidad. Las apps que ocultan el nodo " +
                    "editable (banca, algunos juegos) no aceptan texto inyectado: es un límite del sistema, no del enlace.",
            )
        }
    }
}

@Composable
private fun KeyChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
