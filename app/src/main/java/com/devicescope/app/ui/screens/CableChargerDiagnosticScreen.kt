package com.devicescope.app.ui.screens

import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicescope.app.data.BatteryDiagnosticReader
import com.devicescope.app.data.BatterySnapshot
import com.devicescope.app.data.DeviceModule
import com.devicescope.app.data.DeviceReading
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.data.PowerEstimator
import com.devicescope.app.data.PowerSummary
import com.devicescope.app.data.UsbDiagnosticReader
import com.devicescope.app.data.valueOrNull
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private const val MEASUREMENT_DURATION_MS = 10_000L
private const val SAMPLE_INTERVAL_MS = 1_000L
private const val HIGH_TEMPERATURE_TENTHS_C = 450

private enum class CableDiagnosticStage {
    CONNECT,
    READY,
    MEASURING,
    COMPLETE,
    INTERRUPTED,
}

private enum class MeasurementInterruption {
    HIGH_TEMPERATURE,
    READ_FAILURE,
}

private data class ChargerReference(
    val label: String,
    val voltage: Double,
    val current: Double,
) {
    val watts: Double get() = voltage * current
}

private data class UsbDataCheck(
    val deviceCount: DeviceReading<Int>,
    val pluggedSource: Int,
) {
    val hasDataDevice: Boolean get() = (deviceCount.valueOrNull ?: 0) > 0
}

private val commonChargerReferences = listOf(
    ChargerReference(label = "5 V / 1 A", voltage = 5.0, current = 1.0),
    ChargerReference(label = "5 V / 2 A", voltage = 5.0, current = 2.0),
    ChargerReference(label = "9 V / 2 A", voltage = 9.0, current = 2.0),
)

@Composable
internal fun ColumnScope.CableChargerDiagnosticContent(module: DeviceModule) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    var stage by remember { mutableStateOf(CableDiagnosticStage.CONNECT) }
    var latestReading by remember { mutableStateOf<BatterySnapshot?>(null) }
    var powerSummary by remember { mutableStateOf<PowerSummary?>(null) }
    var usbDataCheck by remember { mutableStateOf<UsbDataCheck?>(null) }
    var selectedReference by remember { mutableStateOf<ChargerReference?>(null) }
    var remainingSeconds by remember { mutableStateOf(10) }
    var interruption by remember { mutableStateOf<MeasurementInterruption?>(null) }

    DisposableEffect(view, stage) {
        view.setKeepScreenOn(stage == CableDiagnosticStage.MEASURING)
        onDispose { view.setKeepScreenOn(false) }
    }

    LaunchedEffect(stage) {
        if (stage != CableDiagnosticStage.MEASURING) return@LaunchedEffect

        latestReading = null
        powerSummary = null
        usbDataCheck = null
        interruption = null
        remainingSeconds = 10
        val readings = mutableListOf<BatterySnapshot>()
        val startedAt = SystemClock.elapsedRealtime()
        var stoppedForHeat = false
        var readingFailed = false

        while (true) {
            val result = try {
                withContext(Dispatchers.IO) { BatteryDiagnosticReader.read(context) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readingFailed = true
                break
            }
            val reading = when (result) {
                is DeviceReading.Available -> result.value
                DeviceReading.NotAvailable, is DeviceReading.Failed -> {
                    readingFailed = true
                    break
                }
            }

            latestReading = reading
            readings += reading

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val remainingMillis = (MEASUREMENT_DURATION_MS - elapsed).coerceAtLeast(0L)
            remainingSeconds = ((remainingMillis + 999L) / 1_000L).toInt()

            if ((reading.temperatureTenthsC.valueOrNull ?: 0) >= HIGH_TEMPERATURE_TENTHS_C) {
                stoppedForHeat = true
                break
            }
            if (elapsed >= MEASUREMENT_DURATION_MS) break

            val nextSampleAt = startedAt + readings.size * SAMPLE_INTERVAL_MS
            val waitMillis = (nextSampleAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            delay(waitMillis.coerceAtMost(MEASUREMENT_DURATION_MS - elapsed))
        }

        when {
            stoppedForHeat -> {
                interruption = MeasurementInterruption.HIGH_TEMPERATURE
                stage = CableDiagnosticStage.INTERRUPTED
            }
            readingFailed || readings.isEmpty() -> {
                interruption = MeasurementInterruption.READ_FAILURE
                stage = CableDiagnosticStage.INTERRUPTED
            }
            else -> {
                powerSummary = PowerEstimator.summarize(readings)
                val source = readings.lastOrNull()?.pluggedSource?.valueOrNull ?: 0
                usbDataCheck = withContext(Dispatchers.IO) {
                    UsbDataCheck(
                        deviceCount = UsbDiagnosticReader.readDeviceCount(context),
                        pluggedSource = source,
                    )
                }
                remainingSeconds = 0
                stage = CableDiagnosticStage.COMPLETE
            }
        }
    }

    val isMeasuring = stage == CableDiagnosticStage.MEASURING
    val isFirstStepDone = stage != CableDiagnosticStage.CONNECT
    val lastTemperature = latestReading?.temperatureTenthsC?.valueOrNull
    val measurementStatus = when {
        lastTemperature != null && lastTemperature >= HIGH_TEMPERATURE_TENTHS_C -> ModuleStatus.ERROR
        lastTemperature != null && lastTemperature >= 400 -> ModuleStatus.WARNING
        else -> ModuleStatus.OK
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Revisión guiada de carga",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = module.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        MeasurementLimitNote()
        if (isMeasuring) {
            Spacer(Modifier.height(12.dp))
            MeasurementTimer(remainingSeconds = remainingSeconds)
            Spacer(Modifier.height(10.dp))
            LiveMeasurementsCard(
                reading = latestReading,
                isMeasuring = true,
                status = measurementStatus,
            )
            TextButton(
                onClick = {
                    stage = CableDiagnosticStage.READY
                    latestReading = null
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Cancelar medición")
            }
        }
        Spacer(Modifier.height(19.dp))
        Text(
            text = "Sigue estos pasos",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(11.dp))

        InstructionStepCard(
            number = 1,
            title = "Conecta tu cargador y cable actual",
            supportingText = if (isFirstStepDone) {
                "Conexión confirmada por ti."
            } else {
                "Usa el adaptador y el cable que quieres comprobar."
            },
            completed = isFirstStepDone,
            enabled = true,
        )
        if (stage == CableDiagnosticStage.CONNECT) {
            Spacer(Modifier.height(9.dp))
            Button(
                onClick = { stage = CableDiagnosticStage.READY },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Ya conecté el cargador", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))
        InstructionStepCard(
            number = 2,
            title = "Mantén la pantalla encendida durante 10 segundos mientras medimos",
            supportingText = when (stage) {
                CableDiagnosticStage.CONNECT -> "Conecta el cargador para habilitar la medición."
                CableDiagnosticStage.READY -> "La pantalla permanecerá encendida durante la prueba."
                CableDiagnosticStage.MEASURING -> "Lectura en curso; quedan $remainingSeconds s."
                CableDiagnosticStage.COMPLETE -> "Medición completada en este dispositivo."
                CableDiagnosticStage.INTERRUPTED -> "La prueba se detuvo antes de completar los 10 segundos."
            },
            completed = stage == CableDiagnosticStage.COMPLETE,
            enabled = stage != CableDiagnosticStage.CONNECT,
        )

        if (stage == CableDiagnosticStage.READY || stage == CableDiagnosticStage.COMPLETE) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    latestReading = null
                    powerSummary = null
                    usbDataCheck = null
                    selectedReference = null
                    interruption = null
                    remainingSeconds = 10
                    stage = CableDiagnosticStage.MEASURING
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (stage == CableDiagnosticStage.COMPLETE) "Medir de nuevo" else "Iniciar medición de 10 s",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (stage == CableDiagnosticStage.INTERRUPTED) {
            Spacer(Modifier.height(12.dp))
            InterruptionCard(reason = interruption)
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = {
                    interruption = null
                    latestReading = null
                    stage = CableDiagnosticStage.READY
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (interruption == MeasurementInterruption.HIGH_TEMPERATURE) "Reintentar cuando esté frío" else "Reintentar medición")
            }
        }

        if (stage == CableDiagnosticStage.COMPLETE || stage == CableDiagnosticStage.INTERRUPTED) {
            latestReading?.let { reading ->
                Spacer(Modifier.height(14.dp))
                LiveMeasurementsCard(
                    reading = reading,
                    isMeasuring = false,
                    status = measurementStatus,
                )
            }
        }

        if (stage == CableDiagnosticStage.COMPLETE && powerSummary != null) {
            Spacer(Modifier.height(18.dp))
            PowerEstimateCard(
                summary = powerSummary!!,
                selectedReference = selectedReference,
            )
            Spacer(Modifier.height(13.dp))
            UsbDataResultCard(check = usbDataCheck)
            Spacer(Modifier.height(13.dp))
            ChargerReferenceTable(
                selectedReference = selectedReference,
                estimatedPowerW = powerSummary?.estimatedPowerW,
                onSelectReference = { selectedReference = it },
            )
        }

        Spacer(Modifier.height(13.dp))
        PrivacyAndSafetyNote(module = module)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MeasurementLimitNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "El voltaje y la temperatura son los de la batería; la corriente también depende del sensor del teléfono. No se mide directamente la salida USB del cargador. La potencia calculada es orientativa y cambia con la carga, el calor y el consumo del equipo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun InstructionStepCard(
    number: Int,
    title: String,
    supportingText: String,
    completed: Boolean,
    enabled: Boolean,
) {
    val backgroundColor = if (completed) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = if (enabled || completed) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        color = if (completed) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (completed) "✓" else number.toString(),
                    color = if (completed) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeasurementTimer(remainingSeconds: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = remainingSeconds.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column {
                Text(
                    text = "Midiendo en tiempo real",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Mantén la pantalla encendida · $remainingSeconds s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
private fun LiveMeasurementsCard(
    reading: BatterySnapshot?,
    isMeasuring: Boolean,
    status: ModuleStatus,
) {
    val voltageMv = reading?.voltageMv?.valueOrNull
    val temperatureTenthsC = reading?.temperatureTenthsC?.valueOrNull
    val currentUa = reading?.currentUa?.valueOrNull
    val pluggedSource = reading?.pluggedSource?.valueOrNull
    val batteryStatus = reading?.batteryStatus?.valueOrNull

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Lecturas de batería",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    status = status,
                    label = if (isMeasuring) "EN VIVO" else "ÚLTIMA LECTURA",
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LiveMetricRow(
                label = "Voltaje",
                value = when {
                    reading == null -> "Esperando lectura…"
                    voltageMv == null -> "No disponible"
                    else -> "$voltageMv mV"
                },
                supportingText = when {
                    voltageMv != null -> "≈ ${formatVoltage(voltageMv.toDouble())}"
                    reading == null -> "BatteryManager.EXTRA_VOLTAGE"
                    else -> "BatteryManager.EXTRA_VOLTAGE no está disponible"
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LiveMetricRow(
                label = "Temperatura",
                value = when {
                    reading == null -> "Esperando lectura…"
                    temperatureTenthsC == null -> "No disponible"
                    else -> "$temperatureTenthsC décimas de °C"
                },
                supportingText = when {
                    temperatureTenthsC != null -> formatTemperature(temperatureTenthsC.toDouble())
                    reading == null -> "BatteryManager.EXTRA_TEMPERATURE"
                    else -> "BatteryManager.EXTRA_TEMPERATURE no está disponible"
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LiveMetricRow(
                label = "Corriente instantánea",
                value = when {
                    reading == null -> "Esperando lectura…"
                    currentUa == null -> "No disponible"
                    else -> formatSignedMicroamps(currentUa)
                },
                supportingText = when {
                    reading == null -> "BATTERY_PROPERTY_CURRENT_NOW"
                    currentUa == null -> "Este dispositivo no expone BATTERY_PROPERTY_CURRENT_NOW"
                    else -> "µA · el signo puede variar según el fabricante"
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LiveMetricRow(
                label = "Fuente de carga",
                value = when {
                    reading == null -> "Esperando lectura"
                    pluggedSource == null -> "No disponible"
                    else -> describePowerSource(pluggedSource)
                },
                supportingText = when {
                    reading == null -> "Consulta cada ~1 s; Android puede renovar algunos datos con menos frecuencia"
                    batteryStatus == null -> "Estado de batería no disponible"
                    else -> describeBatteryState(batteryStatus)
                },
                showValueBold = false,
            )
        }
    }
}

@Composable
private fun LiveMetricRow(
    label: String,
    value: String,
    supportingText: String,
    showValueBold: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (showValueBold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
        )
    }
}

@Composable
private fun InterruptionCard(reason: MeasurementInterruption?) {
    val isHot = reason == MeasurementInterruption.HIGH_TEMPERATURE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isHot) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isHot) "Medición detenida por temperatura" else "No se pudo completar la lectura",
                color = if (isHot) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (isHot) {
                    "La batería alcanzó 45 °C. Desconecta el cargador si notas calor y deja enfriar el teléfono antes de volver a probar."
                } else {
                    "No se recibieron lecturas fiables del sistema. Comprueba la conexión y vuelve a intentarlo."
                },
                color = if (isHot) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PowerEstimateCard(
    summary: PowerSummary,
    selectedReference: ChargerReference?,
) {
    val power = summary.estimatedPowerW
    val comparison = selectedReference?.let { reference ->
        power?.let { it / reference.watts }
    }
    val isSlow = comparison != null && comparison < 0.65
    val isBelowReference = comparison != null && comparison < 0.90
    val status = when {
        power == null -> ModuleStatus.WARNING
        isBelowReference -> ModuleStatus.WARNING
        else -> ModuleStatus.OK
    }
    val title = when {
        power == null -> "Potencia no disponible"
        selectedReference == null -> "Potencia estimada: ≈ ${formatWatts(power)}"
        isSlow -> "Carga lenta estimada frente a ${formatWatts(selectedReference.watts)}"
        isBelowReference -> "Lectura por debajo de la referencia"
        else -> "Lectura cercana a la referencia"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Resultado de carga",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(7.dp))
                    StatusPill(
                        status = status,
                        label = if (power == null) "Sin estimación" else if (isBelowReference) "Por debajo" else "Estimación",
                    )
                }
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            if (power != null) {
                val voltage = summary.averageVoltageMv?.let { formatVoltage(it) } ?: "voltaje no disponible"
                val current = summary.averageCurrentMagnitudeUa?.let { formatAmps(it / 1_000_000.0) }
                    ?: "corriente no disponible"
                Text(
                    text = "$voltage / $current (≈${formatWatts(power)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val referenceText = when {
                    selectedReference == null -> {
                        val closest = commonChargerReferences.minByOrNull { abs(it.watts - power) }
                        closest?.let {
                            "Referencia nominal más cercana: ${it.label} (≈${formatWatts(it.watts)}). Sin conocer la potencia declarada de tu adaptador no se puede concluir si la carga es lenta."
                        } ?: ""
                    }
                    isSlow -> "Si tu cargador declara ${formatWatts(selectedReference.watts)}, el cable o el cargador podrían estar limitando la carga."
                    isBelowReference -> "La lectura queda por debajo de ${formatWatts(selectedReference.watts)} declarados; el teléfono también puede limitar la carga."
                    else -> "La estimación es cercana a la referencia declarada, pero no mide la salida USB del adaptador."
                }
                if (referenceText.isNotBlank()) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = referenceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val message = when {
                    summary.wirelessChargeDetected -> "Se detectó carga inalámbrica; esta comparación es solo para cargadores por cable."
                    summary.wiredChargeSamples == 0 -> "Android no informa una fuente de carga por cable conectada. Conecta el cargador y vuelve a medir."
                    summary.averageVoltageMv == null -> "El teléfono no expuso un voltaje válido durante la prueba."
                    summary.averageCurrentMagnitudeUa == null -> "Este dispositivo no expone corriente instantánea; no es posible calcular V × I."
                    else -> "No se detectó corriente de carga suficiente para una estimación fiable."
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Promedio de ${summary.sampleCount} muestras · temperatura media: ${summary.averageTemperatureTenthsC?.let { formatTemperature(it) } ?: "No disponible"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(11.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(
                    text = "La lectura usa voltaje y corriente de batería. Un valor menor que los vatios nominales no demuestra por sí solo una falla: el nivel de carga, la temperatura, el uso de pantalla y los límites del teléfono influyen.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsbDataResultCard(check: UsbDataCheck?) {
    val pluggedSource = check?.pluggedSource ?: 0
    val deviceCount = check?.deviceCount
    val count = deviceCount?.valueOrNull
    val title = when {
        check == null -> "Comprobación USB no disponible"
        count == null -> "No se pudo consultar USB"
        check.hasDataDevice -> "Dispositivo USB de datos detectado"
        pluggedSource == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Carga inalámbrica detectada"
        pluggedSource != 0 -> "No se detectó transferencia de datos"
        else -> "No hay un dispositivo USB enumerado"
    }
    val detail = when {
        check == null -> "No se completó la consulta con UsbManager."
        deviceCount is DeviceReading.Failed -> "UsbManager no pudo enumerar los dispositivos USB."
        count == null -> "UsbManager.getDeviceList() no devolvió una lista disponible."
        check.hasDataDevice -> "UsbManager.getDeviceList() enumeró $count periférico(s). Hay comunicación USB en modo host."
        pluggedSource == BatteryManager.BATTERY_PLUGGED_WIRELESS -> "No aplica una prueba de cable mientras la carga es inalámbrica."
        pluggedSource != 0 -> "Parece una conexión de solo carga, pero no es concluyente: getDeviceList() enumera periféricos en modo host, no siempre el teléfono conectado a un PC. Prueba Transferencia de archivos (MTP)."
        else -> "Conecta el teléfono a una computadora y selecciona Transferencia de archivos (MTP) para probar el cable."
    }
    val status = if (check?.hasDataDevice == true) ModuleStatus.OK else ModuleStatus.WARNING

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(21.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "USB",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(7.dp))
                    StatusPill(status = status)
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChargerReferenceTable(
    selectedReference: ChargerReference?,
    estimatedPowerW: Double?,
    onSelectReference: (ChargerReference) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = "Referencias de cargadores",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Toca una opción si conoces la potencia declarada en la etiqueta del adaptador.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(9.dp))
            commonChargerReferences.forEachIndexed { index, reference ->
                val isClosest = estimatedPowerW != null &&
                    commonChargerReferences.minByOrNull { abs(it.watts - estimatedPowerW) } == reference
                ReferenceRow(
                    reference = reference,
                    selected = selectedReference == reference,
                    isClosest = isClosest,
                    onClick = { onSelectReference(reference) },
                )
                if (index != commonChargerReferences.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Potencia nominal = voltios × amperios; la potencia efectiva depende del protocolo de carga y del teléfono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReferenceRow(
    reference: ChargerReference,
    selected: Boolean,
    isClosest: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reference.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Potencia nominal ≈ ${formatWatts(reference.watts)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isClosest) {
                Text(
                    text = "Más próxima",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PrivacyAndSafetyNote(module: DeviceModule) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = "${module.privacyNote} La consulta se ejecuta localmente y no necesita internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun describePowerSource(source: Int): String = when (source) {
    BatteryManager.BATTERY_PLUGGED_AC -> "Adaptador de corriente"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrica"
    0 -> "Sin conexión reportada"
    else -> "Fuente conectada"
}

private fun describeBatteryState(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Estado: cargando"
    BatteryManager.BATTERY_STATUS_FULL -> "Estado: carga completa"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Estado: descargando"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Estado: sin carga activa"
    else -> "Estado reportado por Android"
}

private fun formatSignedMicroamps(currentUa: Long): String {
    val sign = when {
        currentUa > 0L -> "+"
        currentUa < 0L -> "−"
        else -> ""
    }
    return "$sign${NumberFormat.getIntegerInstance().format(abs(currentUa))} µA"
}

private fun formatVoltage(voltageMv: Double): String =
    "${formatDecimal(voltageMv / 1_000.0, 1)} V"

private fun formatTemperature(temperatureTenthsC: Double): String =
    "${formatDecimal(temperatureTenthsC / 10.0, 1)} °C"

private fun formatAmps(currentA: Double): String =
    "${formatDecimal(currentA, 2)} A"

private fun formatWatts(watts: Double): String =
    "${formatDecimal(watts, 1)} W"

private fun formatDecimal(value: Double, maximumFractionDigits: Int): String =
    NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 1
        this.maximumFractionDigits = maximumFractionDigits
    }.format(value)
