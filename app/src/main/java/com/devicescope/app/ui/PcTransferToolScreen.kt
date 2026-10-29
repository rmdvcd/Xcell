package com.devicescope.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
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
import com.devicescope.app.pcmovil.data.RemoteFile
import com.devicescope.app.pcmovil.data.TransferDirection
import com.devicescope.app.pcmovil.data.TransferState
import com.devicescope.app.pcmovil.link.PcFileTransfers
import kotlinx.coroutines.launch

/**
 * Explorador y transferencia de archivos con la PC.
 *
 * Descarga: el archivo viaja por el canal FILES y se guarda donde el usuario
 * elija con el selector del sistema (sin permiso de almacenamiento).
 * Subida: se elige con el selector y el agente lo deja en su carpeta de
 * descargas (`DeviceScope` dentro del home del usuario).
 */
@Composable
fun PcTransferToolScreen(onBack: () -> Unit, onOpenPanel: () -> Unit) {
    val link = rememberPcLink()
    val context = LocalContext.current
    val state by link.state.collectAsState()
    val scope = rememberCoroutineScope()
    val transfers = remember { PcFileTransfers(context, link) }
    val progress by transfers.progress.collectAsState()

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }

    fun browse(target: String) {
        scope.launch {
            loading = true
            val result = link.listRemote(target)
            loading = false
            result.onSuccess {
                entries = it.sortedWith(compareByDescending<RemoteFile> { it.directory }.thenBy { it.name.lowercase() })
                path = target
                message = null
            }.onFailure {
                message = it.localizedMessage
            }
        }
    }

    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val file = pendingDownload
        pendingDownload = null
        if (destination == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = transfers.pull(file.path, destination)
            result.onSuccess { history = (history + "↓ $it").takeLast(8) }
                .onFailure { message = it.localizedMessage }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = transfers.push(uri, null)
            result.onSuccess { history = (history + "↑ $it").takeLast(8) }
                .onFailure { message = it.localizedMessage }
        }
    }

    PcToolScreen(
        title = "DataTransfer",
        onBack = onBack,
        actions = { PcStatusChip(state = state) },
    ) {
        if (state !is PcLinkState.Connected) {
            PcNeedsConnection(onConnect = onOpenPanel)
            return@PcToolScreen
        }

        PcCard {
            PcSectionTitle(text = "Carpeta de la PC", hint = if (path.isBlank()) "inicio" else path)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val parent = path.trimEnd('/', '\\').substringBeforeLast('/', "")
                        browse(if (path.isBlank()) "" else parent)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = path.isNotBlank(),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Subir")
                }
                OutlinedButton(
                    onClick = { browse(path) },
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                ) { Text(if (loading) "Leyendo…" else "Actualizar") }
                Button(
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Subir archivo") }
            }
            if (entries.isEmpty()) {
                Text(
                    text = if (loading) "Leyendo carpetas…" else "Carpeta vacía o sin acceso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entries.take(60).forEach { entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.directory) {
                                browse(entry.path)
                            } else {
                                pendingDownload = entry
                                downloadLauncher.launch(entry.name)
                            }
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.directory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (entry.directory) {
                                    "carpeta"
                                } else {
                                    "${PcFormat.bytes(entry.sizeBytes)} · ${PcFormat.dateTime(entry.modifiedMillis)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = if (entry.directory) "Abrir" else "Bajar",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        val current = progress
        if (current != null) {
            PcCard {
                PcSectionTitle(
                    text = if (current.direction == TransferDirection.TO_PHONE) {
                        "Recibiendo de la PC"
                    } else {
                        "Enviando a la PC"
                    },
                    hint = current.state.name.lowercase(),
                )
                Text(
                    text = current.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PcProgress(
                    fraction = current.fraction,
                    label = "${PcFormat.bytes(current.bytesDone)} de ${PcFormat.bytes(current.bytesTotal)}",
                    valueLabel = PcFormat.percent((current.fraction * 100f).toDouble()),
                )
                if (current.state == TransferState.ERROR) {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            PcCard {
                PcSectionTitle(text = "Historial de esta sesión")
                history.reversed().forEach { item ->
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        PcNote(
            text = "Los archivos van directos entre el teléfono y la PC por el socket del enlace: no hay nube " +
                "ni servidor intermedio. La descarga se escribe primero en caché y solo se copia al destino " +
                "elegido si llega completa.",
        )
    }
}
