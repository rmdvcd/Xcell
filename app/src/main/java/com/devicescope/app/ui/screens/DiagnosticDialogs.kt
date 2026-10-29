package com.devicescope.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.devicescope.app.data.BatteryDiagnosticReader
import com.devicescope.app.data.BatterySnapshot
import com.devicescope.app.data.DiagnosticMetric
import com.devicescope.app.data.DiagnosticStatus
import com.devicescope.app.data.PowerEstimator
import com.devicescope.app.data.UsbDiagnosticReader
import com.devicescope.app.data.valueOrNull
import com.devicescope.app.ui.components.MetricRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

private fun round1(value: Double): String = (round(value * 10.0) / 10.0).toString()

private fun Double.dbfs(): Double = if (this <= 0.0) -120.0 else 20.0 * log10(this / 32768.0)

// -------------------------------------------------------------------------
// Micrófono
// -------------------------------------------------------------------------

@Composable
internal fun MicTestDialog(
    onDismiss: () -> Unit,
    onResult: (List<DiagnosticMetric>) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(true) }
    var metrics by remember { mutableStateOf(emptyList<DiagnosticMetric>()) }

    suspend fun measure() {
        running = true
        metrics = withContext(Dispatchers.IO) { measureMicrophone(context) }
        running = false
    }
    LaunchedEffect(Unit) { measure() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Prueba de micrófono", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Habla en voz alta durante 2 segundos. El audio se procesa en memoria, no se guarda ni se envía.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                if (running) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("Grabando… habla ahora", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    metrics.forEach { MetricRow(it) }
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = { scope.launch { measure() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Repetir prueba") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onResult(metrics) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Guardar resultado") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

private fun measureMicrophone(context: Context): List<DiagnosticMetric> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return listOf(
            DiagnosticMetric("Micrófono", "Sin permiso RECORD_AUDIO", "Concede el permiso y vuelve a analizar.", DiagnosticStatus.WARNING),
        )
    }
    val sampleRate = 44_100
    val minBuffer = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) {
        return listOf(DiagnosticMetric("Micrófono", "AudioRecord no disponible", status = DiagnosticStatus.ERROR))
    }
    val recorder = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
    }.getOrNull()
    if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
        recorder?.release()
        return listOf(DiagnosticMetric("Micrófono", "No se pudo inicializar la grabación", status = DiagnosticStatus.ERROR))
    }
    return try {
        recorder.startRecording()
        val buffer = ShortArray(minBuffer)
        var peak = 0
        var sumSquares = 0.0
        var samples = 0
        val endAt = SystemClock.elapsedRealtime() + 2_000L
        while (SystemClock.elapsedRealtime() < endAt) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    peak = maxOf(peak, abs(value))
                    sumSquares += value.toDouble() * value
                    samples++
                }
            }
        }
        recorder.stop()
        val rms = sqrt(sumSquares / samples.coerceAtLeast(1))
        val peakDb = peak.toDouble().dbfs()
        val rmsDb = rms.dbfs()
        val status = when {
            peakDb < -50.0 -> DiagnosticStatus.WARNING
            peakDb > -1.0 -> DiagnosticStatus.WARNING
            else -> DiagnosticStatus.OK
        }
        val verdict = when {
            peakDb < -50.0 -> "Señal muy baja: micrófono tapado, dañado o silenciado"
            peakDb > -1.0 -> "Señal saturada: posible distorsión"
            else -> "Se detectó señal de audio"
        }
        listOf(
            DiagnosticMetric("Nivel pico", "${round1(peakDb)} dBFS", "0 dBFS es el máximo digital.", status),
            DiagnosticMetric("Nivel RMS", "${round1(rmsDb)} dBFS"),
            DiagnosticMetric("Muestras procesadas", samples.toString()),
            DiagnosticMetric("Veredicto", verdict, status = status),
        )
    } catch (error: Exception) {
        listOf(
            DiagnosticMetric(
                "Micrófono",
                "Error durante la grabación",
                error.localizedMessage ?: "excepción no identificada",
                DiagnosticStatus.ERROR,
            ),
        )
    } finally {
        recorder.release()
    }
}

// -------------------------------------------------------------------------
// Bocinas
// -------------------------------------------------------------------------

@Composable
internal fun SpeakerTestDialog(
    onDismiss: () -> Unit,
    onResult: (List<DiagnosticMetric>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Reproduciendo tono estéreo…") }

    fun play() {
        playing = true
        message = "Reproduciendo tono estéreo…"
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { playStereoTone() }.exceptionOrNull() }
            playing = false
            message = error?.let { "No se pudo reproducir: ${it.localizedMessage}" } ?: "Tono reproducido."
        }
    }
    LaunchedEffect(Unit) { play() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Prueba de bocinas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sube el volumen. Se emite un tono de 440 Hz en el canal izquierdo y 660 Hz en el derecho.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                if (playing) CircularProgressIndicator()
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { play() }, modifier = Modifier.fillMaxWidth()) { Text("Repetir tono") }
                Spacer(Modifier.height(14.dp))
                Text("¿Escuchaste ambos tonos con claridad?", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onResult(
                            listOf(
                                DiagnosticMetric("Prueba de bocinas", "Ambos canales audibles", status = DiagnosticStatus.OK),
                                DiagnosticMetric("Nota", "Se usó AudioTrack estéreo con tonos de 440/660 Hz."),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sí, se escuchan bien") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onResult(
                            listOf(
                                DiagnosticMetric("Prueba de bocinas", "Fallo reportado por el usuario", status = DiagnosticStatus.ERROR),
                                DiagnosticMetric("Nota", "Revisa volumen, auriculares conectados y posibles daños en el altavoz."),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("No / se escucha mal") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

private fun playStereoTone() {
    val sampleRate = 44_100
    val durationMs = 1_200
    val frames = sampleRate * durationMs / 1_000
    val buffer = ShortArray(frames * 2)
    for (index in 0 until frames) {
        val time = index.toDouble() / sampleRate
        val left = (sin(2.0 * PI * 440.0 * time) * 0.25 * Short.MAX_VALUE).toInt().toShort()
        val right = (sin(2.0 * PI * 660.0 * time) * 0.25 * Short.MAX_VALUE).toInt().toShort()
        buffer[index * 2] = left
        buffer[index * 2 + 1] = right
    }
    val track = AudioTrack.Builder()
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
        .setBufferSizeInBytes(buffer.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    try {
        track.write(buffer, 0, buffer.size)
        track.play()
        Thread.sleep(durationMs.toLong())
        track.stop()
    } finally {
        track.release()
    }
}

// -------------------------------------------------------------------------
// Táctil
// -------------------------------------------------------------------------

@Composable
internal fun TouchGridDialog(
    onDismiss: () -> Unit,
    onResult: (List<DiagnosticMetric>) -> Unit,
) {
    var touched by remember { mutableStateOf(setOf<Int>()) }
    var maxPointers by remember { mutableStateOf(0) }
    val columns = 6
    val rows = 10
    val total = columns * rows
    val untouchedColor = MaterialTheme.colorScheme.surfaceVariant
    val touchedColor = MaterialTheme.colorScheme.primary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Text("Prueba de táctil", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Desliza uno o varios dedos por TODA la cuadrícula. Las zonas sin marcar pueden tener el digitalizador dañado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed > maxPointers) maxPointers = pressed
                                    event.changes.forEach { change ->
                                        if (change.pressed) {
                                            val column = (change.position.x / (size.width.toFloat() / columns))
                                                .toInt().coerceIn(0, columns - 1)
                                            val row = (change.position.y / (size.height.toFloat() / rows))
                                                .toInt().coerceIn(0, rows - 1)
                                            touched = touched + (row * columns + column)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    val cellWidth = size.width / columns
                    val cellHeight = size.height / rows
                    for (row in 0 until rows) {
                        for (column in 0 until columns) {
                            val index = row * columns + column
                            drawRect(
                                color = if (index in touched) touchedColor else untouchedColor,
                                topLeft = Offset(column * cellWidth + 2f, row * cellHeight + 2f),
                                size = Size(cellWidth - 4f, cellHeight - 4f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Zonas respondidas: ${touched.size} de $total · Máximo de punteros: $maxPointers",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val coverageStatus = if (touched.size >= total * 0.8) DiagnosticStatus.OK else DiagnosticStatus.ERROR
                        onResult(
                            listOf(
                                DiagnosticMetric("Cobertura táctil", "${touched.size}/$total zonas", status = coverageStatus),
                                DiagnosticMetric("Punteros simultáneos", maxPointers.toString(), status = if (maxPointers >= 2) DiagnosticStatus.OK else DiagnosticStatus.WARNING),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Guardar resultado") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Pantalla
// -------------------------------------------------------------------------

private val pixelTestColors = listOf(
    Color.White,
    Color.Black,
    Color.Red,
    Color.Green,
    Color.Blue,
    Color.Gray,
)

@Composable
internal fun ColorTestDialog(
    onDismiss: () -> Unit,
    onResult: (List<DiagnosticMetric>) -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (asking) MaterialTheme.colorScheme.surface else pixelTestColors[index],
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (asking) "¿Qué observaste?" else "Color ${index + 1} de ${pixelTestColors.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (asking) {
                                "¿Viste manchas, líneas, zonas oscuras o píxeles que no cambian de color?"
                            } else {
                                "Mira toda la pantalla con brillo alto y busca píxeles muertos o manchas."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (asking) {
                            Button(
                                onClick = {
                                    onResult(
                                        listOf(
                                            DiagnosticMetric("Prueba de píxeles", "Sin defectos visibles", status = DiagnosticStatus.OK),
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("No vi fallos") }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onResult(
                                        listOf(
                                            DiagnosticMetric("Prueba de píxeles", "Defectos visuales detectados", status = DiagnosticStatus.ERROR),
                                        ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Sí, vi fallos") }
                        } else {
                            Button(
                                onClick = {
                                    if (index == pixelTestColors.lastIndex) asking = true else index += 1
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Siguiente color") }
                            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------
// Cable y cargador (10 s)
// -------------------------------------------------------------------------

@Composable
internal fun ChargeMeasureDialog(
    onDismiss: () -> Unit,
    onResult: (List<DiagnosticMetric>) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var seconds by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(true) }
    var metrics by remember { mutableStateOf(emptyList<DiagnosticMetric>()) }

    LaunchedEffect(Unit) {
        running = true
        metrics = withContext(Dispatchers.IO) {
            measureCharging(context) { seconds = it }
        }
        running = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Medición de carga (10 s)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Conecta el cargador y mantén la pantalla encendida. Se muestrea voltaje, corriente y temperatura una vez por segundo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                if (running) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("$seconds / 10 s", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    metrics.forEach { MetricRow(it) }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { onResult(metrics) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Guardar resultado")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

private suspend fun measureCharging(
    context: Context,
    onProgress: (Int) -> Unit,
): List<DiagnosticMetric> = withContext(Dispatchers.IO) {
    val readings = mutableListOf<BatterySnapshot>()
    for (second in 1..10) {
        val reading = BatteryDiagnosticReader.read(context).valueOrNull
        if (reading == null) break
        readings += reading
        onProgress(second)
        if (second < 10) delay(1_000L)
    }
    if (readings.isEmpty()) {
        return@withContext listOf(
            DiagnosticMetric("Medición", "No se pudo leer la batería", status = DiagnosticStatus.ERROR),
        )
    }
    val summary = PowerEstimator.summarize(readings)
    val usbCount = UsbDiagnosticReader.readDeviceCount(context).valueOrNull
    val lastPlugged = readings.lastOrNull()?.pluggedSource?.valueOrNull ?: 0
    val power = summary.estimatedPowerW
    val currentMa = summary.averageCurrentMagnitudeUa?.let { it / 1000.0 }
    val voltageV = summary.averageVoltageMv?.let { it / 1000.0 }
    val temperatureC = summary.averageTemperatureTenthsC?.let { it / 10.0 }
    val status = when {
        lastPlugged == 0 -> DiagnosticStatus.WARNING
        power != null && power < 2.5 -> DiagnosticStatus.WARNING
        else -> DiagnosticStatus.OK
    }
    buildList {
        add(DiagnosticMetric("Muestras", summary.sampleCount.toString()))
        add(DiagnosticMetric("Muestras con carga cableada", summary.wiredChargeSamples.toString()))
        add(DiagnosticMetric("Carga inalámbrica detectada", if (summary.wirelessChargeDetected) "Sí" else "No"))
        add(DiagnosticMetric("Voltaje medio", voltageV?.let { "${round1(it)} V" } ?: "No disponible"))
        add(DiagnosticMetric("Corriente media", currentMa?.let { "${round1(it)} mA" } ?: "No disponible"))
        add(DiagnosticMetric("Potencia estimada", power?.let { "${round1(it)} W" } ?: "No disponible"))
        add(DiagnosticMetric("Temperatura media", temperatureC?.let { "${round1(it)} °C" } ?: "No disponible"))
        add(DiagnosticMetric("Dispositivos USB (host)", usbCount?.toString() ?: "No disponible"))
        add(DiagnosticMetric("Referencia 5 V / 1 A", "5 W"))
        add(DiagnosticMetric("Referencia 5 V / 2 A", "10 W"))
        add(DiagnosticMetric("Referencia 9 V / 2 A", "18 W"))
        add(
            DiagnosticMetric(
                "Veredicto",
                when {
                    lastPlugged == 0 -> "No se detectó cargador conectado"
                    power == null -> "Sin corriente: Android no expone el sensor"
                    power < 2.5 -> "Carga lenta: revisa cable, adaptador y puerto"
                    else -> "Carga detectada con potencia razonable"
                },
                status = status,
            ),
        )
    }
}
