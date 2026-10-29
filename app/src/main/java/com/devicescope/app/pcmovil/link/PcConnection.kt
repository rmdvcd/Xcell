package com.devicescope.app.pcmovil.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Una conexión viva con el agente: bucle de lectura, peticiones correladas y
 * flujos de datos (frames de pantalla, audio y archivos).
 *
 * Regla de diseño: el teléfono **nunca** deja de leer el socket. Los mensajes
 * que nadie consume se descartan a propósito (frames de espejo o de audio) para
 * que el enlace de control no se bloquee.
 */
class PcConnection(
    private val socket: Socket,
    scope: CoroutineScope,
) : Closeable {

    private val wire = PcWire(socket.getInputStream(), socket.getOutputStream())
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<PcEnvelope>>()
    private var readerJob: Job? = null

    private val _messages = MutableSharedFlow<PcEnvelope>(extraBufferCapacity = 128)
    val messages: SharedFlow<PcEnvelope> = _messages.asSharedFlow()

    /** Último frame de la pantalla de la PC. Conflado: se descarta lo viejo. */
    private val _mirrorFrame = MutableStateFlow<ByteArray?>(null)
    val mirrorFrame: StateFlow<ByteArray?> = _mirrorFrame.asStateFlow()

    /** Audio recibido de la PC (PCM16). Buffer acotado; si se llena, se pierde audio. */
    val incomingAudio = Channel<ByteArray>(capacity = 64)

    /** Receptor activo de datos de archivo; lo instala [PcFileTransfers]. */
    @Volatile
    var fileSink: ((Int, ByteArray) -> Unit)? = null

    private val _closed = MutableSharedFlow<String>(replay = 1)
    val closed: SharedFlow<String> = _closed.asSharedFlow()

    @Volatile
    private var closeReason: String? = null

    val alive: Boolean get() = closeReason == null && !socket.isClosed

    fun start(scope: CoroutineScope) {
        if (readerJob != null) return
        readerJob = scope.launch {
            try {
                while (isActive) {
                    when (val frame = wire.readFrame()) {
                        is PcFrame.Json -> dispatch(frame.envelope)
                        is PcFrame.Binary -> dispatchBinary(frame)
                        PcFrame.Closed -> break
                    }
                }
            } catch (error: Exception) {
                closeReason = error.localizedMessage ?: "conexión perdida"
            } finally {
                finish(closeReason ?: "conexión cerrada")
            }
        }
    }

    private fun dispatch(envelope: PcEnvelope) {
        val id = envelope.id
        if (id != null) {
            pending.remove(id)?.complete(envelope)
        }
        if (envelope.type == PcProtocol.Type.PONG) return
        _messages.tryEmit(envelope)
    }

    private fun dispatchBinary(frame: PcFrame.Binary) {
        when (frame.channel) {
            PcProtocol.Channel.MIRROR -> _mirrorFrame.value = frame.payload
            PcProtocol.Channel.CAMERA -> Unit // la cámara del teléfono solo se envía
            PcProtocol.Channel.SPEAKER,
            PcProtocol.Channel.MIC,
            -> {
                if (!incomingAudio.trySend(frame.payload).isSuccess) {
                    // Se descarta para no frenar el resto del enlace.
                }
            }
            PcProtocol.Channel.FILES -> fileSink?.invoke(frame.kind, frame.payload)
            else -> Unit
        }
    }

    fun nextId(): Long = ids.getAndIncrement()

    fun sendRaw(
        type: String,
        data: Map<String, Any?> = emptyMap(),
        id: Long? = null,
        channel: PcProtocol.Channel? = null,
    ) {
        wire.sendMessage(type = type, id = id, channel = channel, data = data)
    }

    fun sendBinary(channel: PcProtocol.Channel, kind: Int, payload: ByteArray) {
        wire.sendBinary(channel, kind, payload)
    }

    /** Envía y espera la respuesta correlada por `id`. */
    suspend fun request(
        type: String,
        data: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 8_000,
        expected: Set<String> = emptySet(),
    ): PcEnvelope {
        val id = nextId()
        val deferred = CompletableDeferred<PcEnvelope>()
        pending[id] = deferred
        return try {
            sendRaw(type = type, data = data, id = id)
            val response = withTimeoutOrNull(timeoutMillis) { deferred.await() }
                ?: throw PcLinkException("La PC no respondió a '$type'")
            if (expected.isNotEmpty() && response.type !in expected) {
                throw PcLinkException(
                    "Respuesta inesperada a '$type': ${response.type} ${response.string("reason").orEmpty()}",
                )
            }
            response
        } finally {
            pending.remove(id)
        }
    }

    fun clearMirror() {
        _mirrorFrame.value = null
    }

    private fun finish(reason: String) {
        if (closeReason == null) closeReason = reason
        _closed.tryEmit(closeReason ?: "conexión cerrada")
        pending.values.forEach { it.completeExceptionally(PcLinkException(closeReason ?: "cerrada")) }
        pending.clear()
        incomingAudio.close()
        runCatching { wire.close() }
        runCatching { socket.close() }
    }

    override fun close() {
        finish("cerrada por el usuario")
    }
}
