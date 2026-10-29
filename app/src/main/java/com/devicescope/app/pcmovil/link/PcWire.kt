package com.devicescope.app.pcmovil.link

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Un frame leído del enlace. */
sealed interface PcFrame {
    data class Json(val envelope: PcEnvelope) : PcFrame
    data class Binary(val channel: PcProtocol.Channel, val kind: Int, val payload: ByteArray) : PcFrame
    data object Closed : PcFrame
}

class PcLinkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Lectura/escritura del enlace multiplexado.
 *
 * Los mensajes JSON empiezan por `{` (0x7B) y terminan en `\n`. Los frames
 * binarios empiezan por el ordinal del canal (0..8), así que nunca colisionan.
 * Antes de cada frame binario viaja la cabecera de 6 bytes de
 * [PcProtocol.BinHeader].
 */
class PcWire(input: InputStream, private val output: OutputStream) : Closeable {

    private val buffered = BufferedInputStream(input, 32 * 1024)
    private val writeLock = Any()
    private val lineBytes = ArrayList<Byte>(512)

    /** Envía un mensaje de control. Seguro entre hilos. */
    fun sendJson(line: String) {
        synchronized(writeLock) {
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }
    }

    fun sendMessage(
        type: String,
        id: Long? = null,
        channel: PcProtocol.Channel? = null,
        data: Map<String, Any?> = emptyMap(),
    ) = sendJson(PcProtocol.encode(type = type, id = id, channel = channel, data = data))

    fun sendBinary(channel: PcProtocol.Channel, kind: Int, payload: ByteArray) {
        synchronized(writeLock) {
            output.write(PcProtocol.BinHeader.encode(channel, kind, payload.size))
            output.write(payload)
            output.flush()
        }
    }

    /** Bloquea hasta tener un frame completo. Devuelve [PcFrame.Closed] al EOF. */
    fun readFrame(): PcFrame {
        val first = buffered.read()
        if (first < 0) return PcFrame.Closed
        if (first == '{'.code) {
            val line = readLineRest(first)
            val envelope = PcProtocol.decode(line)
                ?: throw PcLinkException("Mensaje JSON inválido: ${line.take(120)}")
            return PcFrame.Json(envelope)
        }
        val channel = PcProtocol.Channel.entries.getOrNull(first)
            ?: throw PcLinkException("Canal binario desconocido: $first")
        val header = ByteArray(PcProtocol.BinHeader.SIZE)
        header[0] = first.toByte()
        readFully(header, 1, header.size - 1)
        val kind = PcProtocol.BinHeader.kindOf(header)
        val length = PcProtocol.BinHeader.lengthOf(header)
        if (length < 0 || length > MAX_BINARY_FRAME) {
            throw PcLinkException("Frame binario de tamaño inválido: $length")
        }
        val payload = ByteArray(length)
        readFully(payload, 0, length)
        return PcFrame.Binary(channel, kind, payload)
    }

    private fun readLineRest(firstByte: Int): String {
        lineBytes.clear()
        lineBytes.add(firstByte.toByte())
        while (true) {
            val next = buffered.read()
            if (next < 0) throw EOFException("El enlace se cerró a mitad de un mensaje")
            if (next == '\n'.code) break
            if (next != '\r'.code) lineBytes.add(next.toByte())
            if (lineBytes.size > PcProtocol.MAX_LINE_BYTES) {
                throw PcLinkException("Mensaje mayor que ${PcProtocol.MAX_LINE_BYTES} bytes")
            }
        }
        val array = ByteArray(lineBytes.size)
        for (index in lineBytes.indices) array[index] = lineBytes[index]
        return String(array, Charsets.UTF_8)
    }

    private fun readFully(target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = buffered.read(target, offset + read, length - read)
            if (count < 0) throw EOFException("El enlace se cerró a mitad de un frame")
            read += count
        }
    }

    override fun close() {
        runCatching { buffered.close() }
        runCatching { output.close() }
    }

    private companion object {
        const val MAX_BINARY_FRAME = 8 * 1024 * 1024
    }
}
