package com.devicescope.app.pcmovil.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio bidireccional entre el teléfono y la PC.
 *
 * - **Micrófono del teléfono → PC**: `AudioRecord` PCM16 mono 48 kHz en bloques
 *   de 20 ms, enviados por el canal MIC. La PC los reproduce con `sounddevice`.
 * - **Audio de la PC → altavoces del teléfono**: la PC captura su salida
 *   (loopback WASAPI/PulseAudio) y el teléfono la reproduce con `AudioTrack`.
 *
 * No se guarda nada en disco. La latencia depende de la red local y del buffer
 * del agente; en LAN suele quedar entre 150 y 400 ms.
 */
class PcAudioBridge(
    private val context: Context,
    private val link: PcLinkManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _micActive = MutableStateFlow(false)
    val micActive: StateFlow<Boolean> = _micActive.asStateFlow()

    private val _speakerActive = MutableStateFlow(false)
    val speakerActive: StateFlow<Boolean> = _speakerActive.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _speakerLevel = MutableStateFlow(0f)
    val speakerLevel: StateFlow<Float> = _speakerLevel.asStateFlow()

    private var micJob: Job? = null
    private var speakerJob: Job? = null
    private var record: AudioRecord? = null

    private var sampleRate = 48_000

    val hasRecordPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startMic(): Result<Unit> {
        if (_micActive.value) return Result.success(Unit)
        if (!hasRecordPermission) {
            return Result.failure(PcLinkException("Falta el permiso de micrófono"))
        }
        if (!link.startMic(sampleRate)) {
            return Result.failure(PcLinkException("Sin conexión con la PC"))
        }
        val chosen = chooseSampleRate()
        sampleRate = chosen
        val minimum = AudioRecord.getMinBufferSize(
            chosen,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minimum, chosen / 10 * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                chosen,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: Exception) {
            return Result.failure(PcLinkException("No se pudo abrir el micrófono: ${error.message}"))
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return Result.failure(PcLinkException("El micrófono no se inicializó"))
        }
        record = recorder
        _micActive.value = true
        micJob = scope.launch {
            val chunkBytes = chosen / 50 * 2 // 20 ms
            val buffer = ByteArray(chunkBytes)
            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    _micLevel.value = rmsLevel(chunk)
                    if (!link.sendMicChunk(chunk)) break
                }
            } catch (_: Exception) {
                // La sesión de audio se cierra abajo.
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                record = null
                _micActive.value = false
                _micLevel.value = 0f
            }
        }
        return Result.success(Unit)
    }

    fun stopMic() {
        micJob?.cancel()
        micJob = null
        runCatching { record?.stop() }
        link.stopMic()
        _micActive.value = false
        _micLevel.value = 0f
    }

    fun startSpeaker(): Result<Unit> {
        if (_speakerActive.value) return Result.success(Unit)
        val connection = link.connectionOrNull
            ?: return Result.failure(PcLinkException("Sin conexión con la PC"))
        if (!link.capabilities.speakerPlayback) {
            return Result.failure(PcLinkException("El agente no tiene captura de audio instalada"))
        }
        val stereoMin = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (stereoMin > 0) maxOf(stereoMin, 32 * 1024) else 32 * 1024
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: Exception) {
            return Result.failure(PcLinkException("No se pudo abrir el altavoz: ${error.message}"))
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return Result.failure(PcLinkException("El altavoz no se inicializó"))
        }
        if (!link.startSpeaker(sampleRate)) {
            runCatching { track.release() }
            return Result.failure(PcLinkException("Sin conexión con la PC"))
        }
        _speakerActive.value = true
        speakerJob = scope.launch {
            try {
                track.play()
                for (chunk in connection.incomingAudio) {
                    if (!isActive) break
                    track.write(chunk, 0, chunk.size)
                    _speakerLevel.value = rmsLevel(chunk)
                }
            } catch (_: Exception) {
                // Corte de enlace o de audio: se libera el recurso abajo.
            } finally {
                runCatching { track.stop() }
                runCatching { track.release() }
                _speakerActive.value = false
                _speakerLevel.value = 0f
            }
        }
        return Result.success(Unit)
    }

    fun stopSpeaker() {
        speakerJob?.cancel()
        speakerJob = null
        link.stopSpeaker()
        _speakerActive.value = false
        _speakerLevel.value = 0f
    }

    fun stopAll() {
        stopMic()
        stopSpeaker()
    }

    /** Nivel 0..1 a partir del RMS de una muestra PCM16. */
    private fun rmsLevel(samples: ByteArray): Float {
        if (samples.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < samples.size) {
            val sample = ((samples[index + 1].toInt() shl 8) or (samples[index].toInt() and 0xFF)).toShort()
            val value = sample.toDouble() / Short.MAX_VALUE
            sum += value * value
            count++
            index += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return (rms * 4.0).coerceIn(0.0, 1.0).toFloat().let { if (abs(it) < 0.005f) 0f else it }
    }

    private fun chooseSampleRate(): Int {
        val candidates = listOf(48_000, 44_100)
        for (rate in candidates) {
            val size = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (size > 0) return rate
        }
        return 44_100
    }
}
