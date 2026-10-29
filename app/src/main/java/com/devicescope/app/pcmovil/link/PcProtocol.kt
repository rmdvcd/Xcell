package com.devicescope.app.pcmovil.link

/**
 * Protocolo del enlace PC-Móvil.
 *
 * Un mismo protocolo sirve para los dos sentidos: el teléfono puede ser el
 * "host" (PC ↔ móvil) y el agente de PC habla exactamente los mismos mensajes.
 * Transporte: TCP con una conexión de control por XML-free JSON de una línea
 * (`\n` como separador) y conexiones de datos por canal (binario con cabecera).
 *
 * Formato de línea:
 * `{"v":1,"type":"mouse","ch":"input","id":42,"data":{...}}`
 */
object PcProtocol {
    const val VERSION = 1

    /** Puerto de control/datos que abre el agente de PC o el teléfono. */
    const val DEFAULT_PORT = 8787

    /** Puerto UDP de descubrimiento en la LAN. */
    const val DISCOVERY_PORT = 8788

    const val DISCOVERY_MAGIC = "DEVICESCOPE_PCMOVIL_DISCOVER_V1"

    /** Prefijo de la respuesta del agente al descubrimiento. */
    const val DISCOVERY_REPLY_PREFIX = "DEVICESCOPE_PCMOVIL_AGENT_V1"

    const val MAX_LINE_BYTES = 512 * 1024

    /** Tipos de mensaje de control. */
    object Type {
        const val HELLO = "hello"
        const val HELLO_ACK = "hello_ack"
        const val PAIR = "pair"
        const val PAIR_OK = "pair_ok"
        const val PAIR_FAIL = "pair_fail"
        const val PAIR_REQUIRED = "pair_required"
        const val PING = "ping"
        const val PONG = "pong"
        const val ERROR = "error"
        const val DISCONNECT = "disconnect"

        // Entrada remota.
        const val KEY = "key"
        const val TEXT = "text"
        const val MOUSE = "mouse"
        const val SCROLL = "scroll"
        const val GESTURE = "gesture"
        const val CLIPBOARD = "clipboard"
        const val INPUT_ACK = "input_ack"

        /** Pide a la PC que capture su teclado/mouse y los envíe al teléfono. */
        const val CAPTURE_START = "capture_start"
        const val CAPTURE_STOP = "capture_stop"

        // Recursos de la PC.
        const val STATS = "stats"
        const val STATS_REQUEST = "stats_request"

        // Espejo de pantalla (frames binarios por el canal mirror).
        const val MIRROR_START = "mirror_start"
        const val MIRROR_STOP = "mirror_stop"
        const val MIRROR_INFO = "mirror_info"
        const val MIRROR_QUALITY = "mirror_quality"

        // Cámara del teléfono como webcam de la PC.
        const val CAMERA_START = "camera_start"
        const val CAMERA_STOP = "camera_stop"
        const val CAMERA_INFO = "camera_info"
        const val CAMERA_FRAME_ACK = "camera_frame_ack"

        // Audio bidireccional.
        const val AUDIO_START = "audio_start"
        const val AUDIO_STOP = "audio_stop"
        const val AUDIO_CONFIG = "audio_config"
        const val AUDIO_LEVEL = "audio_level"

        // Archivos.
        const val FILE_LIST = "file_list"
        const val FILE_PULL = "file_pull"
        const val FILE_START = "file_start"
        const val FILE_END = "file_end"
        const val FILE_ERROR = "file_error"

        // VPN.
        const val VPN_HELLO = "vpn_hello"
        const val VPN_OPEN = "vpn_open"
        const val VPN_OPENED = "vpn_opened"
        const val VPN_CLOSE = "vpn_close"
        const val VPN_STATE = "vpn_state"
        const val VPN_ROUTES = "vpn_routes"

        // A-D Scanner.
        const val SCAN_START = "scan_start"
        const val SCAN_STOP = "scan_stop"
        const val SCAN_PROGRESS = "scan_progress"
        const val SCAN_RESULTS = "scan_results"
        const val SCAN_NOTES = "scan_notes"
    }

    /** Canales lógicos: control JSON o flujo binario. */
    enum class Channel(val wire: String, val binary: Boolean) {
        CONTROL("control", false),
        INPUT("input", false),
        METRICS("metrics", false),
        MIRROR("mirror", true),
        CAMERA("camera", true),
        MIC("mic", true),
        SPEAKER("speaker", true),
        FILES("files", true),
        ;

        companion object {
            fun from(value: String?): Channel =
                entries.firstOrNull { it.wire == value } ?: CONTROL
        }
    }

    /** Cabecera del canal binario: `[canal(1)][tipo(1)][longitud(4 BE)][payload]`. */
    object BinHeader {
        const val SIZE = 6
        const val KIND_FRAME = 0x01
        const val KIND_AUDIO = 0x02
        const val KIND_FILE_CHUNK = 0x03
        const val KIND_CONTROL = 0x04
        const val KIND_PING = 0x05

        fun encode(channel: Channel, kind: Int, length: Int): ByteArray = byteArrayOf(
            channel.ordinal.toByte(),
            kind.toByte(),
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )

        fun channelOf(header: ByteArray): Channel =
            Channel.entries.getOrElse(header[0].toInt() and 0xFF) { Channel.CONTROL }

        fun kindOf(header: ByteArray): Int = header[1].toInt() and 0xFF

        fun lengthOf(header: ByteArray): Int =
            ((header[2].toInt() and 0xFF) shl 24) or
                ((header[3].toInt() and 0xFF) shl 16) or
                ((header[4].toInt() and 0xFF) shl 8) or
                (header[5].toInt() and 0xFF)
    }

    /** Identificador de dispositivo: estable y sin datos personales. */
    fun deviceFingerprint(model: String, brand: String, androidId: String): String =
        "DS-" + stableHash("$brand|$model|$androidId").uppercase()

    fun encode(
        type: String,
        id: Long? = null,
        channel: Channel? = null,
        data: Map<String, Any?> = emptyMap(),
    ): String {
        val payload = LinkedHashMap<String, Any?>()
        payload["v"] = VERSION
        payload["type"] = type
        if (id != null) payload["id"] = id
        if (channel != null) payload["ch"] = channel.wire
        if (data.isNotEmpty()) payload["data"] = data
        return MiniJson.encode(payload)
    }

    fun decode(line: String): PcEnvelope? = runCatching { PcEnvelope.from(MiniJson.parse(line)) }.getOrNull()

    /** Emisor de identificadores correlativos por conexión. */
    class IdGenerator(private val start: Long = 1L) {
        private var next = start

        fun nextId(): Long = next++

        fun peek(): Long = next
    }

    /** Respuesta de descubrimiento UDP emitida por el agente. */
    fun encodeDiscoveryReply(
        name: String,
        port: Int,
        fingerprint: String,
        platform: String,
        requiresPin: Boolean,
    ): String = MiniJson.encode(
        mapOf(
            "magic" to DISCOVERY_REPLY_PREFIX,
            "name" to name,
            "port" to port,
            "fp" to fingerprint,
            "platform" to platform,
            "pin" to requiresPin,
            "v" to VERSION,
        ),
    )

    /** Datos leídos de un anuncio de descubrimiento. */
    data class AgentAnnouncement(
        val name: String,
        val port: Int,
        val fingerprint: String,
        val platform: String,
        val requiresPin: Boolean,
        val address: String = "",
    )

    fun decodeDiscoveryReply(text: String, address: String): AgentAnnouncement? {
        val parsed = runCatching { MiniJson.parse(text) as? Map<*, *> }.getOrNull() ?: return null
        if (parsed["magic"] != DISCOVERY_REPLY_PREFIX) return null
        val port = (parsed["port"] as? Number)?.toInt() ?: return null
        return AgentAnnouncement(
            name = parsed["name"]?.toString()?.takeIf { it.isNotBlank() } ?: "PC",
            port = port,
            fingerprint = parsed["fp"]?.toString().orEmpty(),
            platform = parsed["platform"]?.toString() ?: "desconocido",
            requiresPin = parsed["pin"] != false,
            address = address,
        )
    }
}

/** Mensaje JSON ya decodificado. */
data class PcEnvelope(
    val version: Int,
    val type: String,
    val id: Long?,
    val channel: PcProtocol.Channel,
    val data: Map<String, Any?>,
) {
    fun string(key: String): String? = data[key]?.toString()
    fun stringOr(key: String, fallback: String): String = string(key) ?: fallback
    fun int(key: String, fallback: Int = 0): Int = (data[key] as? Number)?.toInt() ?: fallback
    fun long(key: String, fallback: Long = 0L): Long = (data[key] as? Number)?.toLong() ?: fallback
    fun double(key: String, fallback: Double = 0.0): Double = (data[key] as? Number)?.toDouble() ?: fallback
    fun boolean(key: String, fallback: Boolean = false): Boolean = when (val value = data[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true) || value == "1"
        else -> fallback
    }

    fun map(key: String): Map<String, Any?> =
        (data[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()

    fun list(key: String): List<Any?> = (data[key] as? List<*>) ?: emptyList()

    companion object {
        fun from(raw: Any?): PcEnvelope {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Mensaje no es un objeto JSON")
            val type = map["type"]?.toString() ?: throw IllegalArgumentException("Mensaje sin 'type'")
            val data = (map["data"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: emptyMap()
            return PcEnvelope(
                version = (map["v"] as? Number)?.toInt() ?: 1,
                type = type,
                id = (map["id"] as? Number)?.toLong(),
                channel = PcProtocol.Channel.from(map["ch"]?.toString()),
                data = data,
            )
        }
    }
}
