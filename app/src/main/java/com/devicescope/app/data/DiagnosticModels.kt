package com.devicescope.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado de una medición: solo se asigna OK/ERROR cuando hay evidencia real. */
enum class DiagnosticStatus { OK, WARNING, ERROR, UNKNOWN }

data class AuditFinding(
    val severity: DiagnosticStatus,
    val title: String,
    val detail: String,
)

data class DiagnosticMetric(
    val label: String,
    val value: String,
    val detail: String? = null,
    val status: DiagnosticStatus = DiagnosticStatus.UNKNOWN,
)

data class DiagnosticSection(
    val id: String,
    val title: String,
    val summary: String,
    val icon: String,
    val metrics: List<DiagnosticMetric>,
    val findings: List<AuditFinding> = emptyList(),
    val status: DiagnosticStatus = DiagnosticStatus.UNKNOWN,
    val note: String = "",
    val interactiveLabel: String? = null,
)

/** Un "rail" leído de sysfs: batería, cargador, BMS, regulator o zona térmica. */
data class VoltageRail(
    val name: String,
    val type: String?,
    val voltageVolts: Double?,
    val currentAmps: Double?,
    val temperatureC: Double?,
    val capacityPercent: Int?,
    val chargeFullMah: Double?,
    val chargeFullDesignMah: Double?,
    val cycleCount: Int?,
    val health: String?,
    val chargingState: String?,
    val technology: String?,
    val raw: Map<String, String>,
)

data class ThermalZone(
    val name: String,
    val type: String?,
    val temperatureC: Double?,
)

data class RawPropertyGroup(
    val name: String,
    val entries: List<Pair<String, String>>,
)

/** Permisos opcionales concedidos; condicionan lo que se puede leer. */
data class ProbePermissions(
    val readPhoneState: Boolean = false,
    val recordAudio: Boolean = false,
    val nearbyWifi: Boolean = false,
    val bluetoothConnect: Boolean = false,
)

object DiagnosticSectionIds {
    const val IDENTITY = "identity"
    const val MIC = "mic"
    const val RAM = "ram"
    const val STORAGE = "storage"
    const val ANTENNAS = "antennas"
    const val SCREEN = "screen"
    const val TOUCH = "touch"
    const val SPEAKERS = "speakers"
    const val PLUGS = "plugs"
    const val BATTERY = "battery"
    const val CABLE = "cable"
    const val NETWORK = "network"
    const val VOLTAGE = "voltage"
    const val PROPERTIES = "properties"
}

data class FullDiagnosticReport(
    val generatedAtMillis: Long,
    val sections: List<DiagnosticSection>,
    val voltageRails: List<VoltageRail>,
    val thermalZones: List<ThermalZone>,
    val auditFindings: List<AuditFinding>,
    val propertyGroups: List<RawPropertyGroup>,
    val overall: DiagnosticStatus,
) {
    fun counts(): Triple<Int, Int, Int> = Triple(
        sections.count { it.status == DiagnosticStatus.ERROR },
        sections.count { it.status == DiagnosticStatus.WARNING },
        sections.count { it.status == DiagnosticStatus.OK },
    )

    fun toText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-CU"))
            .format(Date(generatedAtMillis))
        return buildString {
            appendLine("DEVICESCOPE · DIAGNÓSTICO EXHAUSTIVO")
            appendLine("Generado: $date")
            appendLine("Estado global: ${overall.name}")
            appendLine("Secciones: ${sections.size} · Hallazgos: ${auditFindings.size}")
            appendLine()
            appendLine("=== HALLAZGOS DE COHERENCIA ===")
            if (auditFindings.isEmpty()) appendLine("Sin hallazgos.") else {
                auditFindings.forEach { appendLine("[${it.severity}] ${it.title}: ${it.detail}") }
            }
            appendLine()
            sections.forEach { section ->
                appendLine("=== ${section.title.uppercase()} [${section.status}] ===")
                appendLine(section.summary)
                section.metrics.forEach { metric ->
                    appendLine("- ${metric.label}: ${metric.value}")
                    metric.detail?.let { appendLine("    $it") }
                }
                section.findings.forEach { finding ->
                    appendLine("! [${finding.severity}] ${finding.title}: ${finding.detail}")
                }
                if (section.note.isNotBlank()) appendLine("Nota: ${section.note}")
                appendLine()
            }
            appendLine("=== MAPA DE VOLTAJES / TÉRMICO ===")
            if (voltageRails.isEmpty()) appendLine("sysfs no accesible en este equipo.") else {
                voltageRails.forEach { rail ->
                    appendLine("- ${rail.name}: tipo=${rail.type ?: "?"} V=${rail.voltageVolts ?: "?"} A=${rail.currentAmps ?: "?"} °C=${rail.temperatureC ?: "?"} cap=${rail.capacityPercent ?: "?"}% full=${rail.chargeFullMah ?: "?"} mAh diseño=${rail.chargeFullDesignMah ?: "?"} mAh ciclos=${rail.cycleCount ?: "?"} salud=${rail.health ?: "?"} estado=${rail.chargingState ?: "?"}")
                    rail.raw.forEach { (key, value) -> appendLine("    $key=$value") }
                }
            }
            thermalZones.forEach { appendLine("- ${it.name} (${it.type ?: "?"}): ${it.temperatureC ?: "?"} °C") }
            appendLine()
            appendLine("=== PROPIEDADES CRUDAS ===")
            propertyGroups.forEach { group ->
                appendLine("--- ${group.name} (${group.entries.size}) ---")
                group.entries.forEach { (key, value) -> appendLine("$key=$value") }
            }
        }
    }

    fun toJson(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("generatedAtMillis", generatedAtMillis)
        .put("overall", overall.name)
        .put(
            "findings",
            JSONArray().apply {
                auditFindings.forEach {
                    put(
                        JSONObject()
                            .put("severity", it.severity.name)
                            .put("title", it.title)
                            .put("detail", it.detail),
                    )
                }
            },
        )
        .put(
            "sections",
            JSONArray().apply {
                sections.forEach { section ->
                    put(
                        JSONObject()
                            .put("id", section.id)
                            .put("title", section.title)
                            .put("status", section.status.name)
                            .put(
                                "metrics",
                                JSONArray().apply {
                                    section.metrics.forEach {
                                        put(
                                            JSONObject()
                                                .put("label", it.label)
                                                .put("value", it.value)
                                                .put("detail", it.detail ?: JSONObject.NULL)
                                                .put("status", it.status.name),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
        .put(
            "voltageRails",
            JSONArray().apply {
                voltageRails.forEach { rail ->
                    put(
                        JSONObject()
                            .put("name", rail.name)
                            .put("type", rail.type ?: JSONObject.NULL)
                            .put("voltageVolts", rail.voltageVolts ?: JSONObject.NULL)
                            .put("currentAmps", rail.currentAmps ?: JSONObject.NULL)
                            .put("temperatureC", rail.temperatureC ?: JSONObject.NULL)
                            .put("capacityPercent", rail.capacityPercent ?: JSONObject.NULL)
                            .put("chargeFullMah", rail.chargeFullMah ?: JSONObject.NULL)
                            .put("chargeFullDesignMah", rail.chargeFullDesignMah ?: JSONObject.NULL)
                            .put("cycleCount", rail.cycleCount ?: JSONObject.NULL)
                            .put("health", rail.health ?: JSONObject.NULL)
                            .put("chargingState", rail.chargingState ?: JSONObject.NULL)
                            .put("technology", rail.technology ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put(
            "thermalZones",
            JSONArray().apply {
                thermalZones.forEach {
                    put(
                        JSONObject()
                            .put("name", it.name)
                            .put("type", it.type ?: JSONObject.NULL)
                            .put("temperatureC", it.temperatureC ?: JSONObject.NULL),
                    )
                }
            },
        )
        .toString(2)
}
