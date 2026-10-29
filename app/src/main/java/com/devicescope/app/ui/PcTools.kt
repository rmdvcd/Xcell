package com.devicescope.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.graphics.vector.ImageVector

object PcRoutes {
    const val PANEL = "pcMovil"
    const val KEYBOARD = "pcKeyboard"
    const val MOUSE = "pcMouse"
    const val CAMERA = "pcCamera"
    const val AUDIO = "pcAudio"
    const val TRANSFER = "pcTransfer"
    const val VPN = "pcVpn"
    const val RESOURCES = "pcResources"
    const val SCANNER = "pcScanner"
}

/** Una herramienta del módulo PC-Móvil. */
data class PcTool(
    val id: String,
    val title: String,
    val summary: String,
    val detail: String,
    val route: String,
    val icon: ImageVector,
    val requiresLink: Boolean,
)

val pcTools: List<PcTool> = listOf(
    PcTool(
        id = "keyboard",
        title = "Teclado",
        summary = "Escribe en la PC desde el móvil o en el móvil desde la PC",
        detail = "Dos direcciones: el teclado del teléfono escribe en la PC (texto, atajos, flechas, multimedia) " +
            "y el teclado de la PC escribe en el teléfono mediante el servicio de accesibilidad. " +
            "La inyección en el móvil funciona en campos de texto estándar.",
        route = PcRoutes.KEYBOARD,
        icon = Icons.Filled.Keyboard,
        requiresLink = true,
    ),
    PcTool(
        id = "mouse",
        title = "Mouse",
        summary = "Touchpad de la PC y puntero de la PC sobre el móvil",
        detail = "Touchpad relativo con clic izquierdo/derecho, arrastre y rueda; también espejo de la pantalla " +
            "de la PC para tocar directamente sobre ella. En sentido inverso, el mouse de la PC mueve el " +
            "puntero del teléfono con gestos de accesibilidad.",
        route = PcRoutes.MOUSE,
        icon = Icons.Filled.TouchApp,
        requiresLink = true,
    ),
    PcTool(
        id = "camera",
        title = "Cámara",
        summary = "La cámara del móvil como webcam de la PC",
        detail = "Camera2 en JPEG y envío continuo por el enlace. El agente lo publica en una ventana para " +
            "videollamadas o en /dev/video* si tienes v4l2loopback instalado.",
        route = PcRoutes.CAMERA,
        icon = Icons.Filled.Videocam,
        requiresLink = true,
    ),
    PcTool(
        id = "audio",
        title = "Micrófono y altavoces",
        summary = "El móvil como micrófono y como altavoz de la PC",
        detail = "Micrófono del teléfono hacia la PC (PCM16 mono 48 kHz) y audio de la PC hacia los altavoces " +
            "del teléfono, con medidores de nivel en vivo.",
        route = PcRoutes.AUDIO,
        icon = Icons.Filled.Mic,
        requiresLink = true,
    ),
    PcTool(
        id = "transfer",
        title = "DataTransfer",
        summary = "Archivos entre el móvil y la PC en ambos sentidos",
        detail = "Explorador de carpetas de la PC, descarga con el selector de documentos del sistema y envío " +
            "de archivos del teléfono a la carpeta de descargas del agente. Sin nube.",
        route = PcRoutes.TRANSFER,
        icon = Icons.Filled.SwapHoriz,
        requiresLink = true,
    ),
    PcTool(
        id = "vpn",
        title = "VPN",
        summary = "Entra en la red de la PC desde el móvil",
        detail = "Túnel real sobre VpnService: el teléfono enruta las redes privadas que elijas hacia el relay " +
            "del agente, que abre los sockets desde la PC. IPv4, TCP y UDP; sin ICMP.",
        route = PcRoutes.VPN,
        icon = Icons.Filled.VpnKey,
        requiresLink = true,
    ),
    PcTool(
        id = "resources",
        title = "PC-Resources",
        summary = "CPU, RAM, GPU, discos y red de la PC en vivo",
        detail = "Panel compacto con gauges, sparklines y procesos con más carga. El agente muestrea cada " +
            "segundo (ajustable) y el teléfono solo dibuja.",
        route = PcRoutes.RESOURCES,
        icon = Icons.Filled.Insights,
        requiresLink = true,
    ),
    PcTool(
        id = "scanner",
        title = "A-D Scanner",
        summary = "Barrido de WiFi, Bluetooth, celular y sensores",
        detail = "Descubre emisores y receptores alrededor del teléfono con el alcance real de sus radios, " +
            "estima distancia por RSSI y puede ampliar el barrido a la subred de la PC con el agente.",
        route = PcRoutes.SCANNER,
        icon = Icons.Filled.Sensors,
        requiresLink = false,
    ),
)

/** Icono usado en la tarjeta del módulo dentro del dashboard. */
val pcModuleIcon: ImageVector = Icons.Filled.Bolt
