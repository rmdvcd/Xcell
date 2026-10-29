package com.devicescope.app.pcmovil.data

import com.devicescope.app.pcmovil.link.PcProtocol

/** Rol de un extremo del enlace. */
enum class PcRole { PHONE, PC }

/** Dispositivo emparejado que se guarda en el teléfono. */
data class PcDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = PcProtocol.DEFAULT_PORT,
    val token: String? = null,
    val platform: String = "PC",
    val agentVersion: String = "",
    val lastSeenMillis: Long = 0L,
    val autoConnect: Boolean = false,
) {
    val paired: Boolean get() = !token.isNullOrBlank()

    val endpoint: String get() = "$address:$port"

    fun toJsonMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "address" to address,
        "port" to port,
        "token" to token,
        "platform" to platform,
        "agentVersion" to agentVersion,
        "lastSeen" to lastSeenMillis,
        "auto" to autoConnect,
    )

    companion object {
        fun fromJsonMap(map: Map<String, Any?>): PcDevice? {
            val address = map["address"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: address
            return PcDevice(
                id = map["id"]?.toString() ?: "${address}:${(map["port"] as? Number)?.toInt() ?: PcProtocol.DEFAULT_PORT}",
                name = name,
                address = address,
                port = (map["port"] as? Number)?.toInt() ?: PcProtocol.DEFAULT_PORT,
                token = map["token"]?.toString()?.takeIf { it.isNotBlank() },
                platform = map["platform"]?.toString() ?: "PC",
                agentVersion = map["agentVersion"]?.toString().orEmpty(),
                lastSeenMillis = (map["lastSeen"] as? Number)?.toLong() ?: 0L,
                autoConnect = map["auto"] == true,
            )
        }
    }
}

/** Estado del enlace, compartido por toda la UI del módulo. */
sealed interface PcLinkState {
    data object Idle : PcLinkState
    data class Discovering(val found: Int) : PcLinkState
    data class Connecting(val endpoint: String) : PcLinkState
    data class PairingRequired(
        val endpoint: String,
        val deviceName: String,
        val attemptsLeft: Int?,
        val message: String? = null,
    ) : PcLinkState
    data class Connected(val info: PcAgentInfo) : PcLinkState
    data class Failed(val reason: String) : PcLinkState
    data object Disconnected : PcLinkState
}

/** Información que el agente declara al conectar. */
data class PcAgentInfo(
    val name: String,
    val platform: String,
    val version: String,
    val fingerprint: String,
    val endpoint: String,
    val capabilities: PcCapabilities,
    val pairedAtMillis: Long = System.currentTimeMillis(),
)

data class PcCapabilities(
    val keyboard: Boolean = true,
    val mouse: Boolean = true,
    val relativeMouse: Boolean = true,
    val screenMirror: Boolean = false,
    val screenView: Boolean = false,
    val micCapture: Boolean = false,
    val speakerPlayback: Boolean = false,
    val files: Boolean = false,
    val vpnRelay: Boolean = false,
    val lanScan: Boolean = false,
    val clipboard: Boolean = false,
    val cameraWebcam: Boolean = false,
) {
    fun summary(): String = buildList {
        if (keyboard) add("teclado")
        if (mouse) add("mouse")
        if (screenMirror) add("espejo")
        if (screenView) add("pantalla")
        if (micCapture) add("micrófono")
        if (speakerPlayback) add("audio")
        if (files) add("archivos")
        if (vpnRelay) add("VPN")
        if (lanScan) add("escaneo LAN")
        if (cameraWebcam) add("webcam")
    }.joinToString(" · ").ifBlank { "solo control básico" }
}

/** Snapshot de recursos de la PC (PC-Resources). */
data class PcMetrics(
    val timestampMillis: Long = 0L,
    val cpuPercent: Double = 0.0,
    val cpuName: String = "",
    val cpuCores: Int = 0,
    val cpuFreqMhz: Double = 0.0,
    val cpuTempC: Double? = null,
    val gpuPercent: Double? = null,
    val gpuName: String = "",
    val gpuTempC: Double? = null,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
    val swapUsedBytes: Long = 0L,
    val swapTotalBytes: Long = 0L,
    val disks: List<PcDisk> = emptyList(),
    val netRxBytesPerSec: Double = 0.0,
    val netTxBytesPerSec: Double = 0.0,
    val netInterface: String = "",
    val netIp: String = "",
    val wifiSsid: String = "",
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val uptimeSeconds: Long = 0L,
    val processCount: Int = 0,
    val threadCount: Int = 0,
    val loadAverage: Double? = null,
    val hostname: String = "",
    val osName: String = "",
    val topProcesses: List<PcProcess> = emptyList(),
) {
    val ramPercent: Float
        get() = if (ramTotalBytes <= 0L) 0f else (ramUsedBytes.toDouble() / ramTotalBytes).toFloat()
}

data class PcDisk(
    val mount: String,
    val usedBytes: Long,
    val totalBytes: Long,
)

data class PcProcess(
    val pid: Int,
    val name: String,
    val cpuPercent: Double,
    val ramBytes: Long,
)

/** Historial corto para los gráficos de PC-Resources. */
data class PcMetricsSample(
    val cpu: Float,
    val ram: Float,
    val gpu: Float,
    val rx: Float,
    val tx: Float,
)

/** Resultado del barrido A-D Scanner. */
enum class ScanRadio { WIFI, BLUETOOTH, CELLULAR, NFC, GPS, LAN, SENSOR }

enum class ScanKind { ACCESS_POINT, BLE_DEVICE, CLASSIC_DEVICE, CELL_TOWER, LAN_HOST, SENSOR }

data class ScanHit(
    val radio: ScanRadio,
    val kind: ScanKind,
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val distanceMeters: Double? = null,
    val detail: String = "",
    val channels: List<Int> = emptyList(),
    val secure: Boolean? = null,
    val firstSeenMillis: Long = System.currentTimeMillis(),
    val lastSeenMillis: Long = System.currentTimeMillis(),
    val occurrences: Int = 1,
    val openPorts: List<Int> = emptyList(),
) {
    /** Estimación logarítmica de distancia: solo orientativa y sin calibración fina. */
    fun distanceLabel(): String = when {
        rssi == null -> "sin RSSI"
        rssi >= -45 -> "< 1 m"
        rssi >= -60 -> "≈ 1–3 m"
        rssi >= -70 -> "≈ 3–8 m"
        rssi >= -80 -> "≈ 8–15 m"
        rssi >= -90 -> "≈ 15–30 m"
        else -> "> 30 m"
    }
}

/** Resumen del barrido, con notas sobre los límites de cada sensor. */
data class ScanReport(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val hits: List<ScanHit>,
    val perRadio: Map<ScanRadio, Int>,
    val notes: List<String> = emptyList(),
)

/** Estado de la VPN del teléfono. */
sealed interface VpnState {
    data object Off : VpnState
    data class Preparing(val message: String) : VpnState
    data class On(val endpoint: String, val host: String, val sinceMillis: Long, val routes: Int) : VpnState
    data class Error(val reason: String) : VpnState
}

/** Elemento del explorador de archivos remoto. */
data class RemoteFile(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)

/** Progreso de una transferencia. */
data class TransferProgress(
    val direction: TransferDirection,
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: TransferState,
    val message: String = "",
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

enum class TransferDirection { TO_PC, TO_PHONE }

enum class TransferState { QUEUED, RUNNING, DONE, ERROR }

/** Acción del ratón remoto dentro de la herramienta Mouse. */
enum class MouseAction { MOVE, BUTTON_DOWN, BUTTON_UP, CLICK, DOUBLE_CLICK, SCROLL, TEXT }

/** Destino de los eventos de entrada. */
enum class InputTarget(val wire: String) {
    PC("pc"),
    PHONE("phone"),
    ;

    companion object {
        fun from(value: String?): InputTarget =
            entries.firstOrNull { it.wire == value } ?: PC
    }
}

/** Acción disponible en el teclado remoto. */
enum class RemoteKey(val wire: String, val label: String) {
    ENTER("enter", "Enter"),
    TAB("tab", "Tab"),
    BACKSPACE("backspace", "⌫"),
    DELETE("delete", "Supr"),
    ESCAPE("escape", "Esc"),
    SPACE("space", "Espacio"),
    UP("up", "↑"),
    DOWN("down", "↓"),
    LEFT("left", "←"),
    RIGHT("right", "→"),
    HOME("home", "Inicio"),
    END("end", "Fin"),
    PAGE_UP("page_up", "RePág"),
    PAGE_DOWN("page_down", "AvPág"),
    CTRL("ctrl", "Ctrl"),
    ALT("alt", "Alt"),
    SHIFT("shift", "Shift"),
    WIN("win", "Win"),
    COPY("copy", "Copiar"),
    PASTE("paste", "Pegar"),
    CUT("cut", "Cortar"),
    UNDO("undo", "Deshacer"),
    REDO("redo", "Rehacer"),
    SAVE("save", "Guardar"),
    SELECT_ALL("select_all", "Todo"),
    ALT_TAB("alt_tab", "Alt+Tab"),
    VOLUME_UP("volume_up", "Vol +"),
    VOLUME_DOWN("volume_down", "Vol −"),
    PLAY_PAUSE("play_pause", "Play/Pausa"),
    NEXT_TRACK("next_track", "Sig."),
    PREV_TRACK("prev_track", "Ant."),
    BACK("back", "Atrás"),
    HOME_SCREEN("home_screen", "Inicio"),
    RECENTS("recents", "Recientes"),
    SCREEN_OFF("screen_off", "Apagar"),
}
