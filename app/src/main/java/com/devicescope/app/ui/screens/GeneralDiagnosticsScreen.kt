package com.devicescope.app.ui.screens

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.devicescope.app.data.DeviceDiagnosticReader
import com.devicescope.app.data.DeviceDiagnosticSnapshot
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.data.calculateHealthScore
import com.devicescope.app.data.isRamHealthy
import com.devicescope.app.data.isStorageHealthy
import com.devicescope.app.ui.components.HealthScoreRing
import com.devicescope.app.ui.components.ScreenHeader
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GeneralDiagnosticsScreen(
    snapshot: DeviceDiagnosticSnapshot?,
    onSnapshotReady: (DeviceDiagnosticSnapshot) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenHeader(title = "Diagnóstico general", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Una revisión rápida del sistema",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Consulta modelo, Android, RAM y espacio libre con lecturas locales de Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp),
                    )
                    Column {
                        Text(
                            text = "Sin permisos especiales",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "La comprobación no necesita internet y no cambia la configuración del teléfono.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        isRunning = true
                        errorMessage = null
                        try {
                            val result = withContext(Dispatchers.IO) {
                                DeviceDiagnosticReader.read(context.applicationContext)
                            }
                            onSnapshotReady(result)
                        } catch (_: Exception) {
                            errorMessage = "No se pudo leer toda la información del sistema. Vuelve a intentarlo."
                        } finally {
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Comprobando…", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = if (snapshot == null) "Ejecutar comprobación rápida" else "Volver a comprobar",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            errorMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            snapshot?.let {
                Spacer(Modifier.height(22.dp))
                SnapshotResultCard(snapshot = it, context = context)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SnapshotResultCard(
    snapshot: DeviceDiagnosticSnapshot,
    context: Context,
) {
    val ramHealthy = isRamHealthy(snapshot)
    val storageHealthy = isStorageHealthy(snapshot)
    val overallStatus = if (ramHealthy && storageHealthy) ModuleStatus.OK else ModuleStatus.WARNING
    val score = calculateHealthScore(snapshot)
    val summary = when {
        !storageHealthy -> "Conviene revisar el espacio disponible."
        !ramHealthy -> "La memoria disponible es baja en este momento."
        else -> "Los recursos básicos están dentro de un rango saludable."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Resultado de la revisión",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(7.dp))
                    StatusPill(status = overallStatus)
                }
                HealthScoreRing(score = score, size = 76.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DiagnosticValueRow(
                icon = Icons.Filled.PhoneAndroid,
                label = "Modelo",
                detail = "Fabricante y modelo del dispositivo",
                value = snapshot.deviceName,
                status = ModuleStatus.OK,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DiagnosticValueRow(
                icon = Icons.Filled.Info,
                label = "Android",
                detail = "Versión ${snapshot.androidRelease} · API ${snapshot.apiLevel}",
                value = "API ${snapshot.apiLevel}",
                status = ModuleStatus.OK,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DiagnosticValueRow(
                icon = Icons.Filled.Memory,
                label = "RAM total",
                detail = "${formatBytes(context, snapshot.availableRamBytes)} disponible",
                value = formatBytes(context, snapshot.totalRamBytes),
                status = if (ramHealthy) ModuleStatus.OK else ModuleStatus.WARNING,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DiagnosticValueRow(
                icon = Icons.Filled.Storage,
                label = "Almacenamiento interno libre",
                detail = "de ${formatBytes(context, snapshot.totalStorageBytes)} totales",
                value = formatBytes(context, snapshot.freeStorageBytes),
                status = if (storageHealthy) ModuleStatus.OK else ModuleStatus.WARNING,
            )
        }
    }
}

@Composable
private fun DiagnosticValueRow(
    icon: ImageVector,
    label: String,
    detail: String,
    value: String,
    status: ModuleStatus,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            StatusPill(
                status = status,
                label = if (status == ModuleStatus.OK) "OK" else "Atención",
            )
        }
    }
}

private fun formatBytes(context: Context, bytes: Long): String =
    if (bytes <= 0L) "No disponible" else Formatter.formatShortFileSize(context, bytes)
