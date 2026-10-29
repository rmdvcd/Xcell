package com.devicescope.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Devices
import androidx.compose.ui.graphics.vector.ImageVector

object ModuleIds {
    const val FULL_DIAGNOSTIC = "full-diagnostic"
    const val PC_MOVIL = "pc-movil"
}

/** Un módulo del producto: diagnóstico offline y herramientas PC-Móvil. */
data class DeviceModule(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val description: String,
    val privacyNote: String,
    val cta: String,
)

val deviceModules: List<DeviceModule> = listOf(
    DeviceModule(
        id = ModuleIds.FULL_DIAGNOSTIC,
        title = "Diagnóstico exhaustivo",
        summary = "14 grupos de componentes, voltajes y auditoría de falsificaciones",
        icon = Icons.Filled.Apps,
        description = "Analiza micrófono, RAM, almacenamiento, antenas y transmisores, pantalla, táctil, bocinas, plugs, batería, cable/cargador, red (Datos/WiFi/Bluetooth), mapa de voltajes internos y todas las propiedades del equipo. Contrasta fuentes independientes para detectar valores falsificados.",
        privacyNote = "El diagnóstico no usa la red: no tiene permiso INTERNET. No lee ICCID, IMSI, número telefónico ni coordenadas. El audio de la prueba de micrófono se procesa en memoria y no se guarda. Los informes solo se guardan donde tú elijas.",
        cta = "Iniciar diagnóstico exhaustivo",
    ),
    DeviceModule(
        id = ModuleIds.PC_MOVIL,
        title = "PC-Móvil",
        summary = "Teclado, mouse, cámara, audio, archivos, VPN, recursos y escáner",
        icon = Icons.Filled.Devices,
        description = "Convierte el teléfono en una extensión de tu PC por la red local: teclado y mouse en ambos sentidos, cámara como webcam, micrófono y altavoces, transferencia de archivos, VPN hacia la red de la PC, panel de recursos en vivo y barrido de radiofrecuencia del entorno.",
        privacyNote = "Este módulo sí usa la red, pero solo tu LAN: no hay nube, cuentas ni telemetría. Emparejas con un PIN y el token se puede olvidar desde aquí. La cámara y el micrófono solo se activan mientras la herramienta está abierta.",
        cta = "Abrir PC-Móvil",
    ),
)
