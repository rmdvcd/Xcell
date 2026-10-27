package com.devicescope.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.ui.graphics.vector.ImageVector

object ModuleIds {
    const val FULL_DIAGNOSTIC = "full-diagnostic"
}

/** Un único módulo: diagnóstico exhaustivo de todos los componentes. */
data class DeviceModule(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val description: String,
    val privacyNote: String,
)

val deviceModules: List<DeviceModule> = listOf(
    DeviceModule(
        id = ModuleIds.FULL_DIAGNOSTIC,
        title = "Diagnóstico exhaustivo",
        summary = "14 grupos de componentes, voltajes y auditoría de falsificaciones",
        icon = Icons.Filled.Apps,
        description = "Analiza micrófono, RAM, almacenamiento, antenas y transmisores, pantalla, táctil, bocinas, plugs, batería, cable/cargador, red (Datos/WiFi/Bluetooth), mapa de voltajes internos y todas las propiedades del equipo. Contrasta fuentes independientes para detectar valores falsificados.",
        privacyNote = "La app no tiene permiso INTERNET. No lee ICCID, IMSI, número telefónico ni coordenadas; no escanea redes. El audio de la prueba de micrófono se procesa en memoria y no se guarda. Los informes solo se guardan donde tú elijas.",
    ),
)
