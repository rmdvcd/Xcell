package com.devicescope.app.pcmovil.link

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.devicescope.app.pcmovil.data.InputTarget
import com.devicescope.app.pcmovil.data.PcAgentInfo
import com.devicescope.app.pcmovil.data.PcCapabilities
import com.devicescope.app.pcmovil.data.PcDevice
import com.devicescope.app.pcmovil.data.PcDisk
import com.devicescope.app.pcmovil.data.PcLinkState
import com.devicescope.app.pcmovil.data.PcMetrics
import com.devicescope.app.pcmovil.data.PcMetricsSample
import com.devicescope.app.pcmovil.data.PcProcess
import com.devicescope.app.pcmovil.data.PcDeviceStore
import com.devicescope.app.pcmovil.data.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Núcleo del módulo PC-Móvil.
 *
 * Mantiene una sola conexión TCP con el agente, el estado del enlace, las
 * métricas de la PC y los flujos de datos (pantalla, audio, archivos, VPN).
 * Es un singleton creado con el `applicationContext` para que el servicio de
 * accesibilidad y la UI compartan el mismo enlace.
 *
 * Límites reales del enlace, documentados también en la UI:
 * - Solo LAN/Tailscale: no hay nube ni relé externo.
 * - El tráfico va sin cifrar a nivel de transporte; el PIN y el token solo
 *   impiden que un vecino de red use la PC sin permiso. Úsalo en redes propias.
 */
class PcLinkManager private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PcDeviceStore(appContext)

    private val _state = MutableStateFlow<PcLinkState>(PcLinkState.Idle)
    val state: StateFlow<PcLinkState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow<PcMetrics?>(null)
    val metrics: StateFlow<PcMetrics?> = _metrics.asStateFlow()

    private val _history = MutableStateFlow<List<PcMetricsSample>>(emptyList())
    val history: StateFlow<List<PcMetricsSample>> = _history.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Último frame de la pantalla de la PC; conflado para no acumular memoria. */
    private val _mirrorFrame = MutableStateFlow<ByteArray?>(null)
    val mirrorFrame: StateFlow<ByteArray?> = _mirrorFrame.asStateFlow()

    /** Mensajes del agente que la UI (o el servicio de accesibilidad) consume. */
    private val _incoming = MutableSharedFlow<PcEnvelope>(extraBufferCapacity = 128)
    val incoming: SharedFlow<PcEnvelope> = _incoming.asSharedFlow()

    private val _clipboard = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val clipboard: SharedFlow<String> = _clipboard.asSharedFlow()

    private val _events = MutableSharedFlow<PcEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PcEvent> = _events.asSharedFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    @Volatile
    private var connection: PcConnection? = null

    @Volatile
    private var currentDevice: PcDevice? = null

    @Volatile
    private var agent: PcAgentInfo? = null

    private var heartbeatJob: Job? = null
    private val connecting = AtomicBoolean(false)
    private var lastMessageAt = 0L
    private var pendingPin: String? = null

    /** Identidad del teléfono tal como se anuncia en la LAN (sin datos personales). */
    val phoneName: String get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    val phoneFingerprint: String by lazy {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")
        PcProtocol.deviceFingerprint(Build.MODEL.orEmpty(), Build.BRAND.orEmpty(), androidId)
    }

    val connectionOrNull: PcConnection? get() = connection?.takeIf { it.alive }

    val connectedAgent: PcAgentInfo? get() = agent?.takeIf { connectionOrNull != null }

    val currentEndpoint: String? get() = currentDevice?.endpoint

    val capabilities: PcCapabilities get() = agent?.capabilities ?: PcCapabilities()

    // ---------------------------------------------------------------- enlace

    fun devices(): List<PcDevice> = store.devices()

    suspend fun discover(timeoutMillis: Long = 2_500L): List<PcProtocol.AgentAnnouncement> {
        _discovering.value = true
        return try {
            PcDiscovery(appContext).scan(timeoutMillis)
        } finally {
            _discovering.value = false
        }
    }

    /**
     * Abre el enlace. Si [pin] es nulo, se intenta con el token guardado y, si el
     * agente pide emparejamiento, el estado pasa a [PcLinkState.PairingRequired].
     */
    suspend fun connect(
        address: String,
        port: Int = PcProtocol.DEFAULT_PORT,
        pin: String? = null,
        deviceName: String? = null,
    ): Result<PcAgentInfo> = withContext(Dispatchers.IO) {
        if (!connecting.compareAndSet(false, true)) {
            return@withContext Result.failure(PcLinkException("Ya hay una conexión en curso"))
        }
        try {
            closeConnection("reconexión")
            pendingPin = pin
            _state.value = PcLinkState.Connecting("$address:$port")
            appendLog("Conectando a $address:$port")
            val socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                soTimeout = 0
            }
            val newConnection = PcConnection(socket, scope)
            newConnection.start(scope)
            connection = newConnection
            observe(newConnection)

            val device = PcDevice(
                id = "$address:$port",
                name = deviceName ?: address,
                address = address,
                port = port,
                token = store.device("$address:$port")?.token,
                platform = "PC",
            )
            store.upsert(device)
            currentDevice = device

            val handshake = newConnection.request(
                type = PcProtocol.Type.HELLO,
                data = helloPayload(device.token),
                timeoutMillis = HANDSHAKE_TIMEOUT_MS.toLong(),
                expected = setOf(
                    PcProtocol.Type.HELLO_ACK,
                    PcProtocol.Type.PAIR_OK,
                    PcProtocol.Type.PAIR_FAIL,
                    PcProtocol.Type.PAIR_REQUIRED,
                ),
            )
            finishHandshake(handshake, device)
        } catch (error: Exception) {
            val reason = describe(error)
            appendLog("Error: $reason")
            closeConnection(reason)
            _state.value = PcLinkState.Failed(reason)
            Result.failure(error)
        } finally {
            connecting.set(false)
        }
    }

    /** Completa el emparejamiento con el PIN de 6 dígitos que muestra la PC. */
    suspend fun pair(pin: String): Result<PcAgentInfo> = withContext(Dispatchers.IO) {
        val active = connection ?: return@withContext Result.failure(PcLinkException("Sin conexión"))
        val device = currentDevice ?: return@withContext Result.failure(PcLinkException("Sin equipo"))
        try {
            val response = active.request(
                type = PcProtocol.Type.PAIR,
                data = mapOf(
                    "pin" to pin.trim(),
                    "device" to deviceDescriptor(),
                ),
                timeoutMillis = 10_000,
                expected = setOf(PcProtocol.Type.PAIR_OK, PcProtocol.Type.PAIR_FAIL),
            )
            if (response.type == PcProtocol.Type.PAIR_FAIL) {
                val reason = response.string("reason") ?: "PIN incorrecto"
                val attempts = (response.data["attempts_left"] as? Number)?.toInt()
                _state.value = PcLinkState.PairingRequired(
                    endpoint = device.endpoint,
                    deviceName = device.name,
                    attemptsLeft = attempts,
                    message = reason,
                )
                return@withContext Result.failure(PcLinkException(reason))
            }
            pendingPin = null
            finishHandshake(response, device)
        } catch (error: Exception) {
            val reason = describe(error)
            _state.value = PcLinkState.PairingRequired(
                endpoint = device.endpoint,
                deviceName = device.name,
                attemptsLeft = null,
                message = reason,
            )
            Result.failure(error)
        }
    }

    private fun finishHandshake(response: PcEnvelope, device: PcDevice): Result<PcAgentInfo> {
        if (response.type == PcProtocol.Type.PAIR_REQUIRED || response.type == PcProtocol.Type.PAIR_FAIL) {
            val reason = response.string("reason") ?: response.string("message")
                ?: "Escribe el PIN que muestra el agente en la PC"
            _state.value = PcLinkState.PairingRequired(
                endpoint = device.endpoint,
                deviceName = response.map("agent")["name"]?.toString() ?: device.name,
                attemptsLeft = (response.data["attempts_left"] as? Number)?.toInt(),
                message = reason,
            )
            return Result.failure(PcLinkException(reason))
        }
        val agentMap = response.map("agent")
        val info = PcAgentInfo(
            name = agentMap["name"]?.toString()?.takeIf { it.isNotBlank() } ?: device.name,
            platform = agentMap["platform"]?.toString() ?: "PC",
            version = agentMap["version"]?.toString().orEmpty(),
            fingerprint = agentMap["fp"]?.toString().orEmpty(),
            endpoint = device.endpoint,
            capabilities = parseCapabilities(response.map("capabilities")),
        )
        agent = info
        val token = response.string("token")
        when {
            !token.isNullOrBlank() -> {
                store.updateToken(device.id, token, info)
                if (response.type == PcProtocol.Type.PAIR_OK) appendLog("Emparejado con ${info.name}")
            }
            !device.token.isNullOrBlank() -> store.updateToken(device.id, device.token, info)
            else -> store.upsert(device.copy(lastSeenMillis = System.currentTimeMillis()))
        }
        currentDevice = store.device(device.id) ?: device
        _state.value = PcLinkState.Connected(info)
        appendLog("Conectado a ${info.name} (${info.platform})")
        startHeartbeat()
        requestStatsInterval()
        _events.tryEmit(PcEvent.Connected(info))
        return Result.success(info)
    }

    private fun parseCapabilities(map: Map<String, Any?>): PcCapabilities = PcCapabilities(
        keyboard = map["keyboard"] != false,
        mouse = map["mouse"] != false,
        relativeMouse = map["relative_mouse"] != false,
        screenMirror = map["screen_mirror"] == true,
        screenView = map["screen_view"] == true,
        micCapture = map["mic"] == true,
        speakerPlayback = map["speaker"] == true,
        files = map["files"] == true,
        vpnRelay = map["vpn"] == true,
        lanScan = map["lan_scan"] == true,
        clipboard = map["clipboard"] == true,
        cameraWebcam = map["camera"] == true,
    )

    private fun helloPayload(token: String?): Map<String, Any?> = mapOf(
        "device" to deviceDescriptor(),
        "token" to token,
        "app" to "DeviceScope/${Build.VERSION.RELEASE}",
    )

    private fun deviceDescriptor(): Map<String, Any?> = mapOf(
        "name" to phoneName,
        "fp" to phoneFingerprint,
        "platform" to "Android ${Build.VERSION.RELEASE}",
        "model" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        "pin" to pendingPin,
    )

    private fun observe(active: PcConnection) {
        scope.launch {
            active.messages.collect { message ->
                lastMessageAt = System.currentTimeMillis()
                handleMessage(message)
            }
        }
        scope.launch {
            active.messages.collect { envelope ->
                if (connection === active) _incoming.tryEmit(envelope)
            }
        }
        scope.launch {
            active.mirrorFrame.collect { frame ->
                if (connection === active) _mirrorFrame.value = frame
            }
        }
        scope.launch {
            active.closed.collect { reason ->
                if (connection === active) {
                    val wasConnected = agent != null
                    agent = null
                    connection = null
                    heartbeatJob?.cancel()
                    if (wasConnected) {
                        appendLog("Enlace cerrado: $reason")
                        _mirrorFrame.value = null
                        _state.value = PcLinkState.Failed(reason)
                        _events.tryEmit(PcEvent.Disconnected(reason))
                    }
                }
            }
        }
    }

    private fun handleMessage(message: PcEnvelope) {
        when (message.type) {
            PcProtocol.Type.STATS -> _metrics.value = parseMetrics(message)
                .also { appendHistory(it) }

            PcProtocol.Type.ERROR -> {
                val reason = message.string("reason") ?: "error del agente"
                appendLog("Agente: $reason")
                _events.tryEmit(PcEvent.Notice(reason, isError = true))
            }

            PcProtocol.Type.PAIR_OK -> appendLog("Emparejamiento aceptado")
            PcProtocol.Type.PAIR_REQUIRED -> Unit
            PcProtocol.Type.CLIPBOARD -> {
                val text = message.string("text")
                if (!text.isNullOrEmpty()) _clipboard.tryEmit(text)
            }
            PcProtocol.Type.VPN_STATE -> {
                val state = message.string("state")
                val port = message.int("relay_port")
                _events.tryEmit(PcEvent.VpnRelay(port, state.orEmpty()))
            }
            PcProtocol.Type.DISCONNECT -> {
                appendLog("La PC cerró la sesión")
            }
            else -> Unit
        }
    }

    private fun parseMetrics(message: PcEnvelope): PcMetrics {
        val disks = message.list("disks").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PcDisk(
                mount = map["mount"]?.toString() ?: "?",
                usedBytes = (map["used"] as? Number)?.toLong() ?: 0L,
                totalBytes = (map["total"] as? Number)?.toLong() ?: 0L,
            )
        }
        val processes = message.list("top").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            PcProcess(
                pid = (map["pid"] as? Number)?.toInt() ?: 0,
                name = map["name"]?.toString() ?: "?",
                cpuPercent = (map["cpu"] as? Number)?.toDouble() ?: 0.0,
                ramBytes = (map["ram"] as? Number)?.toLong() ?: 0L,
            )
        }
        return PcMetrics(
            timestampMillis = System.currentTimeMillis(),
            cpuPercent = message.double("cpu"),
            cpuName = message.stringOr("cpu_name", ""),
            cpuCores = message.int("cores"),
            cpuFreqMhz = message.double("freq"),
            cpuTempC = (message.data["cpu_temp"] as? Number)?.toDouble(),
            gpuPercent = (message.data["gpu"] as? Number)?.toDouble(),
            gpuName = message.stringOr("gpu_name", ""),
            gpuTempC = (message.data["gpu_temp"] as? Number)?.toDouble(),
            ramUsedBytes = message.long("ram_used"),
            ramTotalBytes = message.long("ram_total"),
            swapUsedBytes = message.long("swap_used"),
            swapTotalBytes = message.long("swap_total"),
            disks = disks,
            netRxBytesPerSec = message.double("net_rx"),
            netTxBytesPerSec = message.double("net_tx"),
            netInterface = message.stringOr("net_if", ""),
            netIp = message.stringOr("net_ip", ""),
            wifiSsid = message.stringOr("wifi", ""),
            batteryPercent = (message.data["battery"] as? Number)?.toInt(),
            batteryCharging = message.data["charging"] as? Boolean,
            uptimeSeconds = message.long("uptime"),
            processCount = message.int("processes"),
            threadCount = message.int("threads"),
            loadAverage = (message.data["load"] as? Number)?.toDouble(),
            hostname = message.stringOr("host", ""),
            osName = message.stringOr("os", ""),
            topProcesses = processes.sortedByDescending { it.cpuPercent }.take(6),
        )
    }

    private fun appendHistory(metrics: PcMetrics) {
        val sample = PcMetricsSample(
            cpu = metrics.cpuPercent.toFloat(),
            ram = metrics.ramPercent * 100f,
            gpu = (metrics.gpuPercent ?: 0.0).toFloat(),
            rx = metrics.netRxBytesPerSec.toFloat(),
            tx = metrics.netTxBytesPerSec.toFloat(),
        )
        _history.value = (_history.value + sample).takeLast(HISTORY_SIZE)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastMessageAt = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val active = connection ?: break
                if (!active.alive) break
                if (System.currentTimeMillis() - lastMessageAt > STALE_MS) {
                    appendLog("La PC dejó de responder")
                    _state.value = PcLinkState.Failed("La PC dejó de responder")
                    closeConnection("sin respuesta")
                    break
                }
                runCatching { active.sendRaw(PcProtocol.Type.PING) }
            }
        }
    }

    fun requestStatsInterval() {
        val active = connection ?: return
        runCatching {
            active.sendRaw(
                PcProtocol.Type.STATS_REQUEST,
                data = mapOf("interval_ms" to store.metricsIntervalMillis),
            )
        }
    }

    fun disconnect() {
        appendLog("Desconectando")
        closeConnection("desconectado por el usuario")
        agent = null
        _metrics.value = null
        _history.value = emptyList()
        _state.value = PcLinkState.Disconnected
    }

    private fun closeConnection(reason: String) {
        heartbeatJob?.cancel()
        runCatching { connection?.close() }
        connection = null
        agent = null
    }

    // ------------------------------------------------------------- entrada

    private fun send(
        type: String,
        data: Map<String, Any?>,
    ): Boolean {
        val active = connectionOrNull ?: return false
        return runCatching {
            active.sendRaw(type = type, data = data)
            true
        }.getOrElse {
            appendLog("Fallo al enviar '$type': ${describe(it)}")
            false
        }
    }

    fun sendText(text: String, target: InputTarget = InputTarget.PC): Boolean =
        send(PcProtocol.Type.TEXT, mapOf("target" to target.wire, "text" to text))

    fun sendKey(key: RemoteKey, modifiers: Set<String> = emptySet(), target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.KEY,
            mapOf(
                "target" to target.wire,
                "key" to key.wire,
                "modifiers" to modifiers.toList(),
            ),
        )

    fun sendRawKey(keyCode: Int, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.KEY,
            mapOf("target" to target.wire, "key" to "raw", "code" to keyCode),
        )

    fun sendMouseMove(dx: Float, dy: Float, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.MOUSE,
            mapOf(
                "target" to target.wire,
                "action" to "move",
                "dx" to dx,
                "dy" to dy,
                "absolute" to false,
            ),
        )

    fun sendMouseMoveAbsolute(x: Float, y: Float, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.MOUSE,
            mapOf(
                "target" to target.wire,
                "action" to "move",
                "x" to x.coerceIn(0f, 1f),
                "y" to y.coerceIn(0f, 1f),
                "absolute" to true,
            ),
        )

    fun sendMouseButton(button: String, pressed: Boolean, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.MOUSE,
            mapOf(
                "target" to target.wire,
                "action" to if (pressed) "down" else "up",
                "button" to button,
            ),
        )

    fun sendClick(button: String = "left", double: Boolean = false, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.MOUSE,
            mapOf(
                "target" to target.wire,
                "action" to if (double) "double" else "click",
                "button" to button,
            ),
        )

    fun sendScroll(dy: Float, target: InputTarget = InputTarget.PC): Boolean =
        send(
            PcProtocol.Type.SCROLL,
            mapOf("target" to target.wire, "dy" to dy, "unit" to "steps"),
        )

    fun sendGesture(
        action: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMillis: Long,
    ): Boolean = send(
        PcProtocol.Type.GESTURE,
        mapOf(
            "target" to InputTarget.PHONE.wire,
            "action" to action,
            "x" to startX,
            "y" to startY,
            "x2" to endX,
            "y2" to endY,
            "duration" to durationMillis,
        ),
    )

    /** La PC empieza a mandar lo que se teclea y se hace clic en su pantalla. */
    fun startInputCapture(): Boolean =
        send(PcProtocol.Type.CAPTURE_START, mapOf("device" to deviceDescriptor()))

    fun stopInputCapture(): Boolean = send(PcProtocol.Type.CAPTURE_STOP, emptyMap())

    fun setClipboard(text: String, paste: Boolean = false): Boolean =
        send(
            PcProtocol.Type.CLIPBOARD,
            mapOf("target" to InputTarget.PC.wire, "text" to text, "action" to if (paste) "paste" else "set"),
        )

    fun requestClipboard(): Boolean = send(PcProtocol.Type.CLIPBOARD, mapOf("action" to "get"))

    // ------------------------------------------------------- espejo de pantalla

    fun startMirror(quality: Int = store.mirrorQuality, fps: Int = store.mirrorFps, scale: Float = 0.6f): Boolean {
        store.mirrorQuality = quality
        store.mirrorFps = fps
        connectionOrNull?.clearMirror()
        _mirrorFrame.value = null
        return send(
            PcProtocol.Type.MIRROR_START,
            mapOf("quality" to quality, "fps" to fps, "scale" to scale),
        )
    }

    fun stopMirror(): Boolean {
        connectionOrNull?.clearMirror()
        _mirrorFrame.value = null
        return send(PcProtocol.Type.MIRROR_STOP, emptyMap())
    }

    // ------------------------------------------------------------- cámara

    fun startCamera(width: Int, height: Int, quality: Int): Boolean = send(
        PcProtocol.Type.CAMERA_START,
        mapOf("width" to width, "height" to height, "quality" to quality),
    )

    fun stopCamera(): Boolean = send(PcProtocol.Type.CAMERA_STOP, emptyMap())

    fun sendCameraFrame(jpeg: ByteArray): Boolean {
        val active = connectionOrNull ?: return false
        return runCatching {
            active.sendBinary(PcProtocol.Channel.CAMERA, PcProtocol.BinHeader.KIND_FRAME, jpeg)
            true
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- audio

    fun startMic(sampleRate: Int): Boolean = send(
        PcProtocol.Type.AUDIO_START,
        mapOf("direction" to "mic", "sample_rate" to sampleRate, "channels" to 1, "bits" to 16),
    )

    fun stopMic(): Boolean = send(PcProtocol.Type.AUDIO_STOP, mapOf("direction" to "mic"))

    fun sendMicChunk(pcm: ByteArray): Boolean {
        val active = connectionOrNull ?: return false
        return runCatching {
            active.sendBinary(PcProtocol.Channel.MIC, PcProtocol.BinHeader.KIND_AUDIO, pcm)
            true
        }.getOrDefault(false)
    }

    fun startSpeaker(sampleRate: Int): Boolean = send(
        PcProtocol.Type.AUDIO_START,
        mapOf("direction" to "speaker", "sample_rate" to sampleRate, "channels" to 2, "bits" to 16),
    )

    fun stopSpeaker(): Boolean = send(PcProtocol.Type.AUDIO_STOP, mapOf("direction" to "speaker"))

    // ------------------------------------------------------------ archivos

    suspend fun listRemote(path: String): Result<List<com.devicescope.app.pcmovil.data.RemoteFile>> =
        withContext(Dispatchers.IO) {
            val active = connection ?: return@withContext Result.failure(PcLinkException("Sin conexión"))
            runCatching {
                val response = active.request(
                    PcProtocol.Type.FILE_LIST,
                    data = mapOf("path" to path),
                    timeoutMillis = 12_000,
                    expected = setOf(PcProtocol.Type.FILE_LIST, PcProtocol.Type.FILE_ERROR),
                )
                if (response.type == PcProtocol.Type.FILE_ERROR) {
                    throw PcLinkException(response.string("reason") ?: "No se pudo listar")
                }
                response.list("entries").mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    com.devicescope.app.pcmovil.data.RemoteFile(
                        name = map["name"]?.toString() ?: "?",
                        path = map["path"]?.toString() ?: "",
                        directory = map["dir"] == true,
                        sizeBytes = (map["size"] as? Number)?.toLong() ?: 0L,
                        modifiedMillis = (map["modified"] as? Number)?.toLong() ?: 0L,
                    )
                }
            }
        }

    // ---------------------------------------------------------------- VPN

    suspend fun requestVpnRelay(): Result<Pair<String, Int>> = withContext(Dispatchers.IO) {
        val active = connection ?: return@withContext Result.failure(PcLinkException("Sin conexión"))
        runCatching {
            val response = active.request(
                PcProtocol.Type.VPN_HELLO,
                data = mapOf("device" to deviceDescriptor()),
                timeoutMillis = 10_000,
                expected = setOf(PcProtocol.Type.VPN_OPENED, PcProtocol.Type.ERROR),
            )
            if (response.type == PcProtocol.Type.ERROR) {
                throw PcLinkException(response.string("reason") ?: "El agente no ofrece túnel")
            }
            val host = response.stringOr("host", currentDevice?.address ?: "")
            val port = response.int("relay_port")
            if (host.isBlank() || port <= 0) throw PcLinkException("Respuesta de túnel inválida")
            host to port
        }
    }

    fun closeVpn(): Boolean = send(PcProtocol.Type.VPN_CLOSE, emptyMap())

    // ------------------------------------------------------- escaneo en la PC

    suspend fun requestLanScan(deep: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val active = connection ?: return@withContext Result.failure(PcLinkException("Sin conexión"))
        runCatching {
            active.sendRaw(
                PcProtocol.Type.SCAN_START,
                data = mapOf("kind" to "lan", "deep" to deep),
            )
        }
    }

    // ------------------------------------------------------------- interno

    private fun appendLog(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$stamp] $line").takeLast(60)
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "La PC no respondió a tiempo"
        is java.net.ConnectException -> "Conexión rechazada: ¿está el agente en marcha y el puerto abierto?"
        is java.net.UnknownHostException -> "Dirección desconocida"
        else -> error.localizedMessage ?: error::class.java.simpleName
    }

    /** Eventos que la UI consume una sola vez. */
    sealed interface PcEvent {
        data class Connected(val info: PcAgentInfo) : PcEvent
        data class Disconnected(val reason: String) : PcEvent
        data class Notice(val message: String, val isError: Boolean = false) : PcEvent
        data class VpnRelay(val port: Int, val state: String) : PcEvent
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val HANDSHAKE_TIMEOUT_MS = 12_000
        private const val HEARTBEAT_MS = 10_000L
        private const val STALE_MS = 45_000L
        private const val HISTORY_SIZE = 90

        @Volatile
        private var instance: PcLinkManager? = null

        fun get(context: Context): PcLinkManager =
            instance ?: synchronized(this) {
                instance ?: PcLinkManager(context.applicationContext).also { instance = it }
            }

        fun peek(): PcLinkManager? = instance
    }
}
