package com.devicescope.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PowerEstimatorTest {

    @Test
    fun `calculates watts from voltage and current`() {
        val summary = PowerEstimator.summarize(
            listOf(
                snapshot(
                    voltageMv = DeviceReading.Available(5_000),
                    currentUa = DeviceReading.Available(2_000_000L),
                ),
            ),
        )

        assertEquals(10.0, summary.estimatedPowerW!!, 0.001)
        assertEquals(5_000.0, summary.averageVoltageMv!!, 0.001)
        assertEquals(2_000_000.0, summary.averageCurrentMagnitudeUa!!, 0.001)
        assertEquals(1, summary.sampleCount)
        assertEquals(1, summary.wiredChargeSamples)
        assertFalse(summary.wirelessChargeDetected)
    }

    @Test
    fun `leaves power empty when current is not available`() {
        val summary = PowerEstimator.summarize(
            listOf(
                snapshot(
                    voltageMv = DeviceReading.Available(5_000),
                    currentUa = DeviceReading.NotAvailable,
                ),
            ),
        )

        assertNull(summary.estimatedPowerW)
        assertNull(summary.averageCurrentMagnitudeUa)
        assertEquals(5_000.0, summary.averageVoltageMv!!, 0.001)
        assertEquals(1, summary.sampleCount)
    }

    @Test
    fun `leaves power empty when current read fails`() {
        val summary = PowerEstimator.summarize(
            listOf(
                snapshot(
                    voltageMv = DeviceReading.Available(5_000),
                    currentUa = DeviceReading.Failed(UnsupportedOperationException("sin sensor")),
                ),
            ),
        )

        assertNull(summary.estimatedPowerW)
        assertNull(summary.averageCurrentMagnitudeUa)
    }

    private fun snapshot(
        voltageMv: DeviceReading<Int>,
        currentUa: DeviceReading<Long>,
    ) = BatterySnapshot(
        voltageMv = voltageMv,
        temperatureTenthsC = DeviceReading.Available(300),
        currentUa = currentUa,
        pluggedSource = DeviceReading.Available(2),
        batteryStatus = DeviceReading.Available(2),
    )
}
