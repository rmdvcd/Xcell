package com.devicescope.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.devicescope.app.data.DiagnosticAudit
import com.devicescope.app.data.DiagnosticMetric
import com.devicescope.app.data.DiagnosticSectionIds
import com.devicescope.app.data.DiagnosticStatus
import com.devicescope.app.data.FullDiagnosticBuilder
import com.devicescope.app.data.FullDiagnosticReport
import com.devicescope.app.data.RawPropertyGroup
import com.devicescope.app.data.ThermalZone
import com.devicescope.app.data.VoltageRail
import com.devicescope.app.ui.components.NoteBlock
import com.devicescope.app.ui.components.ScreenHeader
import com.devicescope.app.ui.components.SectionCard
import com.devicescope.app.ui.components.StatusPill
import com.devicescope.app.ui.components.statusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

@Composable
fun FullDiagnosticScreen(
    onBack: () -> Unit,
    onReportReady: (FullDiagnosticReport) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<FullDiagnosticReport?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var sessionResults by remember { mutableStateOf(mapOf<String, List<DiagnosticMetric>>()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var activeTest by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var lastGeneratedAt by remember { mutableStateOf<Long?>(null) }

    fun runDiagnostic() {
        scope.launch {
            isLoading = true
            exportMessage = null
            val permissions = FullDiagnosticBuilder.permissionsFrom(context)
            val newReport = withContext(Dispatchers.IO) {
                FullDiagnosticBuilder.build(context, permissions)
            }
            report = newReport
            lastGeneratedAt = newReport.generatedAtMillis
            isLoading = false
            onReportReady(newReport)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> runDiagnostic() }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(FullDiagnosticBuilder.requestedPermissions().toTypedArray())
    }

    suspend fun writeToUri(uri: Uri, content: String): String = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("El destino no permite escribir.")
        output.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        "Informe guardado correctamente."
    }

    val textPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination == null) {
            exportMessage = "Guardado cancelado."
        } else {
            scope.launch {
                val current = report
                val text = current?.let {
                    it.copy(
                        sections = it.sections.map { section ->
                            val merged = section.metrics + sessionResults[section.id].orEmpty()
                            section.copy(metrics = merged, status = DiagnosticAudit.sectionStatus(merged, section.findings))
                        },
                        overall = DiagnosticAudit.overall(
                            it.auditFindings,
                            it.sections.map { section ->
                                val merged = section.metrics + sessionResults[section.id].orEmpty()
                                section.copy(metrics = merged, status = DiagnosticAudit.sectionStatus(merged, section.findings))
                            },
                        ),
                    ).toText()
                } ?: "No hay informe todavía."
                exportMessage = runCatching { writeToUri(destination, text) }
                    .getOrElse { "No se pudo guardar: ${it.localizedMessage ?: "error"}" }
            }
        }
    }

    val jsonPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { destination ->
        if (destination == null) {
            exportMessage = "Guardado cancelado."
        } else {
            scope.launch {
                val current = report
                val json = current?.toJson() ?: "{}"
                exportMessage = runCatching { writeToUri(destination, json) }
                    .getOrElse { "No se pudo guardar: ${it.localizedMessage ?: "error"}" }
            }
        }
    }

    val displaySections = remember(report, sessionResults) {
        report?.sections?.map { section ->
            val merged = section.metrics + sessionResults[section.id].orEmpty()
            section.copy(metrics = merged, status = DiagnosticAudit.sectionStatus(merged, section.findings))
        }.orEmpty()
    }
    val overall = report?.let {
        DiagnosticAudit.overall(it.auditFindings, displaySections)
    } ?: DiagnosticStatus.UNKNOWN

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ScreenHeader(title = "Diagnóstico exhaustivo", onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            SummaryCard(
                report = report,
                displaySections = displaySections,
                overall = overall,
                isLoading = isLoading,
                generatedAt = lastGeneratedAt,
                exportMessage = exportMessage,
                onRun = { runDiagnostic() },
                onExportText = { textPicker.launch("diagnostico_devicescope.txt") },
                onExportJson = { jsonPicker.launch("diagnostico_devicescope.json") },
            )
            Spacer(Modifier.height(18.dp))

            if (report == null && !isLoading) {
                NoteBlock("Pulsa «Analizar equipo» para leer todas las fuentes. Si deniegas permisos, las secciones afectadas mostrarán «Requiere permiso» en lugar de datos inventados.")
                Spacer(Modifier.height(14.dp))
            }

            displaySections.forEach { section ->
                SectionCard(
                    section = section,
                    expanded = expanded.contains(section.id),
                    onToggle = {
                        expanded = if (expanded.contains(section.id)) {
                            expanded - section.id
                        } else {
                            expanded + section.id
                        }
                    },
                    onRunTest = { id ->
                        if (id == DiagnosticSectionIds.VOLTAGE) {
                            runDiagnostic()
                        } else {
                            activeTest = id
                        }
                    },
                    extra = {
                        when (section.id) {
                            DiagnosticSectionIds.VOLTAGE -> VoltageDetails(
                                rails = report?.voltageRails.orEmpty(),
                                zones = report?.thermalZones.orEmpty(),
                            )

                            DiagnosticSectionIds.PROPERTIES -> PropertiesDetails(
                                groups = report?.propertyGroups.orEmpty(),
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = "Sin permiso INTERNET, sin telemetría, sin coordenadas, sin ICCID/IMSI. Los permisos opcionales se usan solo para micrófono, señal celular, SSID en Android 13+ y Bluetooth. El audio de las pruebas no se guarda.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    when (activeTest) {
        DiagnosticSectionIds.MIC -> MicTestDialog(
            onDismiss = { activeTest = null },
            onResult = { metrics ->
                sessionResults = sessionResults + (DiagnosticSectionIds.MIC to metrics)
                activeTest = null
            },
        )

        DiagnosticSectionIds.SPEAKERS -> SpeakerTestDialog(
            onDismiss = { activeTest = null },
            onResult = { metrics ->
                sessionResults = sessionResults + (DiagnosticSectionIds.SPEAKERS to metrics)
                activeTest = null
            },
        )

        DiagnosticSectionIds.TOUCH -> TouchGridDialog(
            onDismiss = { activeTest = null },
            onResult = { metrics ->
                sessionResults = sessionResults + (DiagnosticSectionIds.TOUCH to metrics)
                activeTest = null
            },
        )

        DiagnosticSectionIds.SCREEN -> ColorTestDialog(
            onDismiss = { activeTest = null },
            onResult = { metrics ->
                sessionResults = sessionResults + (DiagnosticSectionIds.SCREEN to metrics)
                activeTest = null
            },
        )

        DiagnosticSectionIds.CABLE -> ChargeMeasureDialog(
            onDismiss = { activeTest = null },
            onResult = { metrics ->
                sessionResults = sessionResults + (DiagnosticSectionIds.CABLE to metrics)
                activeTest = null
            },
        )
    }
}

@Composable
private fun SummaryCard(
    report: FullDiagnosticReport?,
    displaySections: List<com.devicescope.app.data.DiagnosticSection>,
    overall: DiagnosticStatus,
    isLoading: Boolean,
    generatedAt: Long?,
    exportMessage: String?,
    onRun: () -> Unit,
    onExportText: () -> Unit,
    onExportJson: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ESTADO GLOBAL",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    modifier = Modifier.weight(1f),
                )
                StatusPill(label = statusLabel(overall), status = overall)
            }
            Spacer(Modifier.height(10.dp))
            if (report == null) {
                Text(
                    text = if (isLoading) "Analizando todas las fuentes del equipo…" else "Aún no se ha ejecutado el diagnóstico.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    text = "${displaySections.size} secciones · ${displaySections.count { it.status == DiagnosticStatus.ERROR }} errores · ${displaySections.count { it.status == DiagnosticStatus.WARNING }} advertencias · ${displaySections.count { it.status == DiagnosticStatus.OK }} OK",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                generatedAt?.let {
                    Text(
                        text = "Último análisis: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                    Text(if (report == null) "Analizar equipo" else "Volver a analizar")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportText, enabled = report != null, modifier = Modifier.weight(1f)) {
                    Text("Guardar TXT")
                }
                OutlinedButton(onClick = onExportJson, enabled = report != null, modifier = Modifier.weight(1f)) {
                    Text("Guardar JSON")
                }
            }
            exportMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun VoltageDetails(
    rails: List<VoltageRail>,
    zones: List<ThermalZone>,
) {
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Rieles de alimentación (${rails.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (rails.isEmpty()) {
        Text(
            text = "sysfs no devolvió nodos; solo están disponibles los valores de BatteryManager.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    rails.take(24).forEach { rail ->
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = rail.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = listOfNotNull(
                        rail.type?.let { "tipo=$it" },
                        rail.voltageVolts?.let { "V=${it.f3()} V" },
                        rail.currentAmps?.let { "I=${it.f3()} A" },
                        rail.temperatureC?.let { "T=${it.f1()} °C" },
                        rail.capacityPercent?.let { "cap=$it %" },
                        rail.chargeFullMah?.let { "full=${it.f0()} mAh" },
                        rail.chargeFullDesignMah?.let { "diseño=${it.f0()} mAh" },
                        rail.cycleCount?.let { "ciclos=$it" },
                        rail.health?.let { "salud=$it" },
                        rail.chargingState?.let { "estado=$it" },
                    ).joinToString(" · ").ifBlank { "Sin valores numéricos legibles" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rail.raw.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = rail.raw.entries.joinToString(" | ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                    )
                }
            }
        }
    }
    if (rails.size > 24) {
        Spacer(Modifier.height(6.dp))
        Text("Y ${rails.size - 24} rieles más (en el informe exportado).", style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "Zonas térmicas (${zones.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    zones.take(20).forEach { zone ->
        Text(
            text = "• ${zone.name} (${zone.type ?: "?"}): ${zone.temperatureC?.f1() ?: "?"} °C",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PropertiesDetails(
    groups: List<RawPropertyGroup>,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Buscar clave o valor") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    val normalized = query.trim()
    groups.forEach { group ->
        val filtered = if (normalized.isBlank()) {
            group.entries
        } else {
            group.entries.filter { (key, value) ->
                key.contains(normalized, ignoreCase = true) || value.contains(normalized, ignoreCase = true)
            }
        }
        if (filtered.isEmpty()) return@forEach
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "${group.name} · ${filtered.size} entradas",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                filtered.take(40).forEach { (key, value) ->
                    Text(
                        text = "$key = $value",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (filtered.size > 40) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "… ${filtered.size - 40} entradas más (incluidas en el informe exportado).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun Double.f0(): String = roundTo(0).toString()
private fun Double.f1(): String = roundTo(1).toString()
private fun Double.f3(): String = roundTo(3).toString()

private fun Double.roundTo(decimals: Int): Double {
    val factor = when (decimals) {
        0 -> 1.0
        1 -> 10.0
        else -> 1000.0
    }
    return kotlin.math.round(this * factor) / factor
}
