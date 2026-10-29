package com.devicescope.app.pcmovil.link

import android.content.Context
import android.net.Uri
import com.devicescope.app.pcmovil.data.TransferDirection
import com.devicescope.app.pcmovil.data.TransferProgress
import com.devicescope.app.pcmovil.data.TransferState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

/**
 * Transferencia de archivos por el enlace, en ambos sentidos.
 *
 * El final de un archivo se marca **dentro del canal binario** con un frame de
 * control, no con un mensaje aparte: así el orden respecto a los chunks está
 * garantizado por el propio socket y no hay carrera entre el último bloque y el
 * aviso de fin.
 *
 * Recepción: `file_pull` → `file_start` → chunks (`KIND_FILE_CHUNK`) → control
 * `{"end":true}`. Se escribe en caché y solo al terminar se copia al destino del
 * selector de documentos, para no dejar archivos a medias.
 */
class PcFileTransfers(
    private val context: Context,
    private val link: PcLinkManager,
) {

    private val _progress = MutableStateFlow<TransferProgress?>(null)
    val progress: StateFlow<TransferProgress?> = _progress.asStateFlow()

    suspend fun pull(remotePath: String, destination: Uri): Result<String> = withContext(Dispatchers.IO) {
        val connection = link.connectionOrNull
            ?: return@withContext Result.failure(PcLinkException("Sin conexión con la PC"))
        val fallbackName = remotePath.substringAfterLast('/').substringAfterLast('\\').ifBlank { "archivo" }
        val temp = File(context.cacheDir, "pc-movil-${System.currentTimeMillis()}.part")
        val finished = CompletableDeferred<Long>()
        var received = 0L
        var declaredSize = 0L
        var fileName = fallbackName

        _progress.value = TransferProgress(
            direction = TransferDirection.TO_PHONE,
            fileName = fileName,
            bytesDone = 0,
            bytesTotal = 0,
            state = TransferState.RUNNING,
        )

        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(temp)
            val sink: (Int, ByteArray) -> Unit = { kind, payload ->
                if (kind == PcProtocol.BinHeader.KIND_CONTROL) {
                    val marker = runCatching { MiniJson.parse(String(payload, Charsets.UTF_8)) }.getOrNull()
                    if (marker is Map<*, *> && marker["end"] == true) {
                        finished.complete(received)
                    }
                } else {
                    runCatching { output?.write(payload) }
                    received += payload.size
                    _progress.value = TransferProgress(
                        direction = TransferDirection.TO_PHONE,
                        fileName = fileName,
                        bytesDone = received,
                        bytesTotal = if (declaredSize > 0) declaredSize else received,
                        state = TransferState.RUNNING,
                    )
                }
            }
            connection.fileSink = sink

            val start = connection.request(
                type = PcProtocol.Type.FILE_PULL,
                data = mapOf("path" to remotePath),
                timeoutMillis = 20_000,
                expected = setOf(PcProtocol.Type.FILE_START, PcProtocol.Type.FILE_ERROR),
            )
            if (start.type == PcProtocol.Type.FILE_ERROR) {
                throw PcLinkException(start.string("reason") ?: "La PC no pudo abrir el archivo")
            }
            fileName = start.stringOr("name", fallbackName)
            declaredSize = start.long("size")
            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PHONE,
                fileName = fileName,
                bytesDone = 0,
                bytesTotal = declaredSize,
                state = TransferState.RUNNING,
            )

            val result = withTimeoutOrNull(180_000L) { finished.await() }
                ?: throw PcLinkException("Tiempo de espera agotado durante la recepción")

            output.flush()
            output.close()
            output = null
            if (declaredSize > 0 && result != declaredSize) {
                throw PcLinkException("Recepción incompleta: $result de $declaredSize bytes")
            }

            val opened = context.contentResolver.openOutputStream(destination, "wt")
                ?: throw PcLinkException("El destino no permite escribir")
            opened.use { stream ->
                temp.inputStream().use { input -> input.copyTo(stream, 64 * 1024) }
            }

            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PHONE,
                fileName = fileName,
                bytesDone = received,
                bytesTotal = received,
                state = TransferState.DONE,
                message = "Guardado en el teléfono",
            )
            Result.success("$fileName · ${formatSize(received)}")
        } catch (error: Exception) {
            runCatching { output?.close() }
            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PHONE,
                fileName = fileName,
                bytesDone = received,
                bytesTotal = declaredSize,
                state = TransferState.ERROR,
                message = error.localizedMessage ?: "error",
            )
            Result.failure(error)
        } finally {
            connection.fileSink = null
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    suspend fun push(uri: Uri, displayName: String?): Result<String> = withContext(Dispatchers.IO) {
        val connection = link.connectionOrNull
            ?: return@withContext Result.failure(PcLinkException("Sin conexión con la PC"))
        val name = displayName ?: queryName(uri) ?: "archivo-${System.currentTimeMillis()}"
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                }
            } ?: throw PcLinkException("No se pudo leer el archivo elegido")

            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PC,
                fileName = name,
                bytesDone = 0,
                bytesTotal = total,
                state = TransferState.RUNNING,
            )

            val ack = connection.request(
                type = PcProtocol.Type.FILE_START,
                data = mapOf("name" to name, "size" to total),
                timeoutMillis = 10_000,
                expected = setOf(PcProtocol.Type.FILE_START, PcProtocol.Type.FILE_ERROR),
            )
            if (ack.type == PcProtocol.Type.FILE_ERROR) {
                throw PcLinkException(ack.string("reason") ?: "La PC rechazó el archivo")
            }

            var sent = 0L
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    connection.sendBinary(
                        PcProtocol.Channel.FILES,
                        PcProtocol.BinHeader.KIND_FILE_CHUNK,
                        chunk,
                    )
                    sent += read
                    _progress.value = TransferProgress(
                        direction = TransferDirection.TO_PC,
                        fileName = name,
                        bytesDone = sent,
                        bytesTotal = if (total > 0) total else sent,
                        state = TransferState.RUNNING,
                    )
                }
            } ?: throw PcLinkException("No se pudo abrir el origen")

            // El marcador va sin envoltorio: el agente busca "end" en la raíz.
            val marker = MiniJson.encode(
                mapOf("end" to true, "name" to name, "size" to sent),
            ).toByteArray(Charsets.UTF_8)
            val confirmation = coroutineScope {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(20_000) {
                        connection.messages.first {
                            it.type == PcProtocol.Type.FILE_END ||
                                it.type == PcProtocol.Type.FILE_ERROR
                        }
                    }
                }
                connection.sendBinary(
                    PcProtocol.Channel.FILES,
                    PcProtocol.BinHeader.KIND_CONTROL,
                    marker,
                )
                pending.await()
            }
            when {
                confirmation == null ->
                    throw PcLinkException("La PC no confirmó la recepción")
                confirmation.type == PcProtocol.Type.FILE_ERROR ->
                    throw PcLinkException(confirmation.string("reason") ?: "La PC rechazó el archivo")
            }

            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PC,
                fileName = name,
                bytesDone = sent,
                bytesTotal = sent,
                state = TransferState.DONE,
                message = "Enviado a la PC",
            )
            Result.success("$name · ${formatSize(sent)}")
        } catch (error: Exception) {
            _progress.value = TransferProgress(
                direction = TransferDirection.TO_PC,
                fileName = name,
                bytesDone = 0,
                bytesTotal = 0,
                state = TransferState.ERROR,
                message = error.localizedMessage ?: "error",
            )
            Result.failure(error)
        }
    }

    fun clear() {
        _progress.value = null
    }

    private fun queryName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
