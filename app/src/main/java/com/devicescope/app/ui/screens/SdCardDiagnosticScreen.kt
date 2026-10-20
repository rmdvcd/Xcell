package com.devicescope.app.ui.screens

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private const val SPEED_TEST_BYTES = 5_000_000

private data class RemovableVolume(
    val id: String,
    val label: String,
    val state: String,
    val totalBytes: Long?,
    val availableBytes: Long?,
    val appDirectory: File?,
    val writable: Boolean,
)

private data class SdSpeedResult(
    val writeMbPerSecond: Double,
    val readMbPerSecond: Double,
    val bytes: Long,
)

@Composable
internal fun ColumnScope.SdCardDiagnosticContent() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var volumes by remember { mutableStateOf<List<RemovableVolume>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    var testingVolumeId by remember { mutableStateOf<String?>(null) }
    var speedResults by remember { mutableStateOf<Map<String, SdSpeedResult>>(emptyMap()) }
    var speedErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(refreshKey) {
        isLoading = true
        volumes = withContext(Dispatchers.IO) { findRemovableVolumes(context) }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Almacenamiento extraíble",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "DeviceScope lista los volúmenes extraíbles que Android detecta y consulta su espacio con StatFs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(19.dp),
        ) {
            Row(
                modifier = Modifier.padding(15.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "La prueba crea un archivo temporal de 5 MB dentro del espacio privado de la app en la SD, lo escribe, lo lee y lo elimina. No se necesita permiso general de archivos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Volúmenes extraíbles",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = { refreshKey++ }, enabled = !isLoading && testingVolumeId == null) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text("Actualizar")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (isLoading && volumes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (volumes.isEmpty()) {
            EmptyStorageCard()
        } else {
            volumes.forEach { volume ->
                RemovableVolumeCard(
                    volume = volume,
                    isTesting = testingVolumeId == volume.id,
                    speedResult = speedResults[volume.id],
                    speedError = speedErrors[volume.id],
                    testEnabled = testingVolumeId == null,
                    onTestSpeed = {
                        val directory = volume.appDirectory
                        if (directory != null && volume.writable) {
                            testingVolumeId = volume.id
                            speedErrors = speedErrors - volume.id
                            speedResults = speedResults - volume.id
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { runSdSpeedTest(directory) }
                                    speedResults = speedResults + (volume.id to result)
                                } catch (exception: Exception) {
                                    speedErrors = speedErrors + (volume.id to (exception.message ?: "No se pudo probar la tarjeta."))
                                } finally {
                                    testingVolumeId = null
                                    refreshKey++
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(13.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text = "La velocidad depende del lector, la clase de la tarjeta, el espacio libre y el estado de montaje. El resultado no se guarda; el archivo temporal se borra al finalizar. La caché del sistema puede influir en la lectura.",
                modifier = Modifier.padding(15.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EmptyStorageCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.SdStorage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "No se detectaron tarjetas extraíbles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Inserta una tarjeta compatible y toca Actualizar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RemovableVolumeCard(
    volume: RemovableVolume,
    isTesting: Boolean,
    speedResult: SdSpeedResult?,
    speedError: String?,
    testEnabled: Boolean,
    onTestSpeed: () -> Unit,
) {
    val mounted = volume.totalBytes != null && volume.availableBytes != null
    val status = if (mounted && volume.writable) ModuleStatus.OK else ModuleStatus.WARNING

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SdStorage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = volume.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = volume.state.ifBlank { "Estado no disponible" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(status = status, label = if (status == ModuleStatus.OK) "Montada" else "Revisar")
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            VolumeSpaceRow("Espacio total", volume.totalBytes)
            VolumeSpaceRow("Disponible", volume.availableBytes)

            if (speedResult != null) {
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(13.dp)) {
                        Text(
                            text = "Prueba completada · ${Formatter.formatShortFileSize(LocalContext.current, speedResult.bytes)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Escritura ${formatSpeed(speedResult.writeMbPerSecond)} MB/s · Lectura ${formatSpeed(speedResult.readMbPerSecond)} MB/s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (speedError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = speedError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(11.dp))
            Button(
                onClick = onTestSpeed,
                enabled = volume.writable && !isTesting && testEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(9.dp))
                    Text("Probando 5 MB…")
                } else {
                    Text(if (volume.writable) "Probar velocidad" else "Tarjeta no disponible para escritura")
                }
            }
        }
    }
}

@Composable
private fun VolumeSpaceRow(label: String, bytes: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = bytes?.let { Formatter.formatShortFileSize(LocalContext.current, it) } ?: "No disponible",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun findRemovableVolumes(context: Context): List<RemovableVolume> {
    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
    val externalFilesDirs = context.getExternalFilesDirs(null)
    val removable = storageManager.getStorageVolumes().filter { it.isRemovable }

    return removable.mapIndexed { index, volume ->
        val description = runCatching { volume.getDescription(context) }.getOrNull().orEmpty()
        val label = description.ifBlank { "Almacenamiento extraíble ${index + 1}" }
        val state = runCatching { volume.state }.getOrNull().orEmpty()
        val appDirectory = findAppDirectory(volume, externalFilesDirs, index)
        val mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
        if (mounted && appDirectory != null && !appDirectory.exists()) {
            appDirectory.mkdirs()
        }
        val statPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.directory?.absolutePath }.getOrNull()
        } else {
            null
        } ?: appDirectory?.absolutePath
        val stat = if (mounted && statPath != null) {
            runCatching { StatFs(statPath) }.getOrNull()
        } else {
            null
        }
        val id = volume.uuid?.takeIf(String::isNotBlank) ?: "${label}_$index"
        RemovableVolume(
            id = id,
            label = label,
            state = state,
            totalBytes = stat?.totalBytes,
            availableBytes = stat?.availableBytes,
            appDirectory = appDirectory,
            writable = mounted && state == Environment.MEDIA_MOUNTED && appDirectory?.canWrite() == true,
        )
    }
}

private fun findAppDirectory(
    volume: StorageVolume,
    externalFilesDirs: Array<File?>,
    removableIndex: Int,
): File? {
    val directories = externalFilesDirs.filterNotNull()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val rootPath = runCatching { volume.directory?.canonicalPath }.getOrNull()
        val pathMatch = directories.firstOrNull { directory ->
            rootPath != null && runCatching { directory.canonicalPath.startsWith(rootPath) }.getOrDefault(false)
        }
        if (pathMatch != null) return pathMatch
    }
    val uuid = volume.uuid?.takeIf(String::isNotBlank)
    if (uuid != null) {
        directories.firstOrNull { it.absolutePath.contains(uuid, ignoreCase = true) }?.let { return it }
    }
    return externalFilesDirs.getOrNull(removableIndex + 1)
}

private fun runSdSpeedTest(directory: File): SdSpeedResult {
    if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("No se pudo preparar el espacio privado de la tarjeta.")
    }
    if (!directory.canWrite()) throw IOException("La tarjeta está protegida contra escritura.")

    val temporaryFile = File.createTempFile("devicescope-speed-", ".tmp", directory)
    try {
        val buffer = ByteArray(64 * 1024) { 0x5A.toByte() }
        val writeStarted = SystemClock.elapsedRealtimeNanos()
        FileOutputStream(temporaryFile).use { output ->
            var remaining = SPEED_TEST_BYTES
            while (remaining > 0) {
                val count = minOf(buffer.size, remaining)
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        val writeSeconds = ((SystemClock.elapsedRealtimeNanos() - writeStarted) / 1_000_000_000.0).coerceAtLeast(0.001)

        val readStarted = SystemClock.elapsedRealtimeNanos()
        var bytesRead = 0L
        BufferedInputStream(FileInputStream(temporaryFile)).use { input ->
            val readBuffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(readBuffer)
                if (count < 0) break
                bytesRead += count
            }
        }
        if (bytesRead != SPEED_TEST_BYTES.toLong()) {
            throw IOException("La lectura del archivo temporal quedó incompleta.")
        }
        val readSeconds = ((SystemClock.elapsedRealtimeNanos() - readStarted) / 1_000_000_000.0).coerceAtLeast(0.001)
        val megabytes = SPEED_TEST_BYTES / 1_000_000.0
        return SdSpeedResult(
            writeMbPerSecond = megabytes / writeSeconds,
            readMbPerSecond = megabytes / readSeconds,
            bytes = bytesRead,
        )
    } finally {
        temporaryFile.delete()
    }
}

private fun formatSpeed(speed: Double): String = String.format(java.util.Locale.getDefault(), "%.1f", speed)
