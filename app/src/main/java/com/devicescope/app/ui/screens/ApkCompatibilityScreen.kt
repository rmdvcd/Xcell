package com.devicescope.app.ui.screens

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devicescope.app.data.ApkCompatibilityAnalyzer
import com.devicescope.app.data.ApkCompatibilityReport
import com.devicescope.app.data.ApkCompatibilityVerdict
import com.devicescope.app.data.ApkFeatureCheck
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.data.formatGlEsVersion
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

@Composable
internal fun ColumnScope.ApkCompatibilityContent(
    analyzeApk: (Context, Uri) -> ApkCompatibilityReport = ApkCompatibilityAnalyzer::analyze,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ApkCompatibilityReport?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingExportReport by remember { mutableStateOf<ApkCompatibilityReport?>(null) }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            report = null
            analysisError = null
            exportMessage = null
            isAnalyzing = true
            scope.launch {
                try {
                    report = withContext(Dispatchers.IO) {
                        analyzeApk(context.applicationContext, uri)
                    }
                } catch (exception: Exception) {
                    analysisError = exception.localizedMessage
                        ?.takeIf(String::isNotBlank)
                        ?: "No se pudo analizar el APK seleccionado."
                } finally {
                    isAnalyzing = false
                }
            }
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { destination: Uri? ->
        val selectedReport = pendingExportReport
        if (destination == null) {
            exportMessage = "Exportación cancelada."
        } else if (selectedReport != null) {
            exportMessage = null
            scope.launch {
                try {
                    val json = selectedReport.toJson()
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw IOException("El destino no permite escribir el archivo.")
                        output.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    exportMessage = "Informe JSON guardado correctamente."
                } catch (exception: Exception) {
                    exportMessage = "No se pudo guardar el JSON: ${exception.localizedMessage ?: "error de escritura"}"
                }
            }
        }
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
            text = "Analiza un archivo APK",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Selecciona el APK que quieras revisar. DeviceScope compara su manifiesto con las capacidades de este dispositivo, sin instalar la app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(15.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Archivo de instalación",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Elige un archivo .apk desde el selector del sistema",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(13.dp))
                Button(
                    onClick = { apkPicker.launch(arrayOf(APK_MIME_TYPE, "application/octet-stream")) },
                    enabled = !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(9.dp))
                        Text("Analizando APK…")
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (report == null) "Seleccionar archivo APK" else "Seleccionar otro APK")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PrivacyCard()

        if (analysisError != null) {
            Spacer(Modifier.height(12.dp))
            MessageCard(
                title = "No se pudo analizar",
                message = analysisError.orEmpty(),
                isError = true,
            )
        }

        report?.let { apkReport ->
            Spacer(Modifier.height(17.dp))
            VerdictCard(apkReport)
            Spacer(Modifier.height(12.dp))
            ApkMetadataCard(apkReport)
            Spacer(Modifier.height(12.dp))
            if (apkReport.blockers.isNotEmpty()) {
                IssueListCard(
                    title = "Requisitos bloqueantes",
                    messages = apkReport.blockers,
                    isError = true,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (apkReport.limitations.isNotEmpty()) {
                IssueListCard(
                    title = "Limitaciones detectadas",
                    messages = apkReport.limitations,
                    isError = false,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (apkReport.requiredFeatures.isNotEmpty()) {
                RequiredFeaturesCard(apkReport.requiredFeatures)
                Spacer(Modifier.height(12.dp))
            } else {
                MessageCard(
                    title = "Características",
                    message = "El APK no declara características de hardware obligatorias en su manifiesto.",
                    isError = false,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (apkReport.blockers.isEmpty() && apkReport.limitations.isEmpty()) {
                MessageCard(
                    title = "Sin limitaciones detectadas",
                    message = "No se encontraron requisitos declarados que este dispositivo incumpla. La compatibilidad de ejecución no se puede garantizar solo con el manifiesto.",
                    isError = false,
                )
                Spacer(Modifier.height(12.dp))
            }

            OutlinedButton(
                onClick = {
                    pendingExportReport = apkReport
                    exportMessage = null
                    exportPicker.launch("informe_compatibilidad_apk.json")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.SaveAlt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Exportar informe a JSON")
            }
            if (exportMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = exportMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exportMessage?.startsWith("No se pudo") == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        MethodNote()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "El APK se analiza localmente. Se copia de forma temporal a la caché privada para leer su manifiesto y se elimina al terminar; no se instala ni se envía a Internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun VerdictCard(report: ApkCompatibilityReport) {
    val (containerColor, contentColor) = when (report.verdict) {
        ApkCompatibilityVerdict.COMPATIBLE -> MaterialTheme.colorScheme.secondaryContainer to
            MaterialTheme.colorScheme.onSecondaryContainer
        ApkCompatibilityVerdict.COMPATIBLE_WITH_LIMITATIONS -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        ApkCompatibilityVerdict.NOT_COMPATIBLE -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(17.dp)) {
            Text(
                text = report.verdict.label,
                style = MaterialTheme.typography.titleLarge,
                color = contentColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = report.apkDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = when (report.verdict) {
                    ApkCompatibilityVerdict.COMPATIBLE -> "No se detectaron requisitos obligatorios incompatibles."
                    ApkCompatibilityVerdict.COMPATIBLE_WITH_LIMITATIONS -> "No hay bloqueos declarados, pero revisa las limitaciones que aparecen a continuación."
                    ApkCompatibilityVerdict.NOT_COMPATIBLE -> "Al menos un requisito obligatorio no se cumple en este dispositivo."
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ApkMetadataCard(report: ApkCompatibilityReport) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = "Requisitos y dispositivo",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            MetadataRow("Paquete", report.packageName)
            MetadataRow("Versión", report.versionName ?: "No declarada")
            MetadataRow("Android mínimo", "API ${report.minimumSdkVersion}")
            MetadataRow(
                "Target SDK",
                report.targetSdkVersion?.let { "API $it · objetivo, no mínimo" } ?: "No declarado",
            )
            Spacer(Modifier.height(6.dp))
            MetadataRow("Dispositivo", "Android ${report.deviceAndroidVersion} · API ${report.deviceApiLevel}")
            MetadataRow(
                "RAM total",
                report.deviceTotalRamBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "No disponible",
            )
            MetadataRow("OpenGL ES", report.deviceGlEsVersion?.let(::formatGlEsVersion) ?: "No disponible")
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.9f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.6f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RequiredFeaturesCard(features: List<ApkFeatureCheck>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = "Características obligatorias",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(7.dp))
            features.forEachIndexed { index, feature ->
                RequiredFeatureRow(feature)
                if (index != features.lastIndex) Spacer(Modifier.height(3.dp))
            }
        }
    }
}

@Composable
private fun RequiredFeatureRow(feature: ApkFeatureCheck) {
    val status = when (feature.supported) {
        true -> ModuleStatus.OK
        false -> ModuleStatus.ERROR
        null -> ModuleStatus.WARNING
    }
    val statusLabel = when (feature.supported) {
        true -> "DISPONIBLE"
        false -> "FALTA"
        null -> "SIN DATOS"
    }
    val versionText = feature.minimumVersion?.let { "Versión mínima: $it" }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feature.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            if (versionText != null) {
                Text(
                    text = versionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusPill(status = status, label = statusLabel)
    }
}

@Composable
private fun IssueListCard(
    title: String,
    messages: List<String>,
    isError: Boolean,
) {
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            messages.forEach { message ->
                Text(
                    text = "• $message",
                    modifier = Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    isError: Boolean,
) {
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun MethodNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "La comparación cubre minSdkVersion y características obligatorias del manifiesto. targetSdkVersion es informativo; la RAM se valora con una heurística para gráficos avanzados. No evalúa dependencias, servicios externos, APK divididos ni garantiza el comportamiento en ejecución.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
