package com.devicescope.app.data

import android.os.BatteryManager
import kotlin.math.abs

/** Aggregate of the battery readings captured during the guided cable/charger test. */
data class PowerSummary(
    val averageVoltageMv: Double?,
    val averageTemperatureTenthsC: Double?,
    val averageCurrentMagnitudeUa: Double?,
    val estimatedPowerW: Double?,
    val sampleCount: Int,
    val wiredChargeSamples: Int,
    val wirelessChargeDetected: Boolean,
)

object PowerEstimator {
    fun summarize(readings: List<BatterySnapshot>): PowerSummary {
        val voltages = readings.mapNotNull { it.voltageMv.valueOrNull?.toDouble() }
        val temperatures = readings.mapNotNull { it.temperatureTenthsC.valueOrNull?.toDouble() }
        val currents = readings.mapNotNull {
            it.currentUa.valueOrNull?.let { value -> abs(value.toDouble()) }
        }
        val wiredReadings = readings.filter {
            val source = it.pluggedSource.valueOrNull
            source != null && source != 0 && source != BatteryManager.BATTERY_PLUGGED_WIRELESS
        }
        val powers = wiredReadings.mapNotNull { reading ->
            val voltageMv = reading.voltageMv.valueOrNull?.toDouble()
            val currentUa = reading.currentUa.valueOrNull?.let { abs(it.toDouble()) }
            if (voltageMv == null || currentUa == null || currentUa == 0.0) {
                null
            } else {
                (voltageMv / 1_000.0) * (currentUa / 1_000_000.0)
            }
        }

        return PowerSummary(
            averageVoltageMv = if (voltages.isNotEmpty()) voltages.average() else null,
            averageTemperatureTenthsC = if (temperatures.isNotEmpty()) temperatures.average() else null,
            averageCurrentMagnitudeUa = if (currents.isNotEmpty()) currents.average() else null,
            estimatedPowerW = if (powers.isNotEmpty()) powers.average() else null,
            sampleCount = readings.size,
            wiredChargeSamples = wiredReadings.size,
            wirelessChargeDetected = readings.any {
                it.pluggedSource.valueOrNull == BatteryManager.BATTERY_PLUGGED_WIRELESS
            },
        )
    }
}
