package com.devicescope.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticAuditTest {

    @Test
    fun detectsModelMismatchBetweenBuildAndGetprop() {
        val snapshot = baseSnapshot().copy(
            build = baseSnapshot().build + ("MODEL" to "SM-A125F"),
            props = baseSnapshot().props + ("ro.product.model" to "Clon-X100"),
        )
        val findings = DiagnosticAudit.run(snapshot)
        assertTrue(findings.any { it.title.contains("Modelo inconsistente") })
        assertTrue(findings.any { it.severity == DiagnosticStatus.WARNING })
    }

    @Test
    fun detectsInflatedRam() {
        val snapshot = baseSnapshot().copy(
            memInfo = mapOf("MemTotal" to 2_000_000L),
            activityTotalMemBytes = 8L * 1024 * 1024 * 1024,
        )
        val findings = DiagnosticAudit.run(snapshot)
        assertTrue(findings.any { it.title.contains("RAM declarada inconsistente") && it.severity == DiagnosticStatus.ERROR })
    }

    @Test
    fun detectsWornBattery() {
        val snapshot = baseSnapshot().copy(
            chargeFullMah = 2_800.0,
            chargeFullDesignMah = 5_000.0,
        )
        val findings = DiagnosticAudit.run(snapshot)
        assertTrue(findings.any { it.title.contains("Batería desgastada") })
    }

    @Test
    fun detectsResolutionMismatch() {
        val snapshot = baseSnapshot().copy(wmSize = "Physical size: 720x1600")
        val findings = DiagnosticAudit.run(snapshot)
        assertTrue(findings.any { it.title.contains("Resolución declarada inconsistente") })
    }

    @Test
    fun coherentSourcesProduceOnlyInformationalFinding() {
        val findings = DiagnosticAudit.run(baseSnapshot())
        assertTrue(
            "No debería haber avisos: ${findings.filter { it.severity != DiagnosticStatus.OK }}",
            findings.none { it.severity == DiagnosticStatus.WARNING || it.severity == DiagnosticStatus.ERROR },
        )
        assertTrue(findings.any { it.severity == DiagnosticStatus.OK })
    }

    @Test
    fun overallPrioritizesErrors() {
        val findings = listOf(
            AuditFinding(DiagnosticStatus.WARNING, "Aviso", "detalle"),
            AuditFinding(DiagnosticStatus.ERROR, "Error", "detalle"),
        )
        val sections = listOf(
            DiagnosticSection("s1", "Sección", "resumen", "info", emptyList(), status = DiagnosticStatus.OK),
        )
        assertEquals(DiagnosticStatus.ERROR, DiagnosticAudit.overall(findings, sections))
    }

    @Test
    fun sectionStatusEscalatesWithMetrics() {
        val metrics = listOf(
            DiagnosticMetric("Normal", "valor"),
            DiagnosticMetric("Fallo", "valor", status = DiagnosticStatus.ERROR),
        )
        assertEquals(DiagnosticStatus.ERROR, DiagnosticAudit.sectionStatus(metrics, emptyList()))
    }

    private fun baseSnapshot(): IdentitySnapshot = IdentitySnapshot(
        build = mapOf(
            "MODEL" to "SM-A125F",
            "MANUFACTURER" to "samsung",
            "BRAND" to "samsung",
            "DEVICE" to "a12",
            "RELEASE" to "11",
            "SDK" to "30",
            "FINGERPRINT" to "samsung/a12xx/a12:11/RP1A.200720.012:user/release-keys",
            "TAGS" to "release-keys",
            "TYPE" to "user",
            "SUPPORTED_ABIS" to "arm64-v8a,armeabi-v7a",
        ),
        props = mapOf(
            "ro.product.model" to "SM-A125F",
            "ro.product.manufacturer" to "samsung",
            "ro.product.brand" to "samsung",
            "ro.product.device" to "a12",
            "ro.build.version.release" to "11",
            "ro.build.version.sdk" to "30",
            "ro.build.type" to "user",
            "ro.build.tags" to "release-keys",
            "ro.build.fingerprint" to "samsung/a12xx/a12:11/RP1A.200720.012:user/release-keys",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi",
            "ro.debuggable" to "0",
            "ro.secure" to "1",
        ),
        memInfo = mapOf("MemTotal" to 4_000_000L, "MemAvailable" to 1_500_000L),
        activityTotalMemBytes = 4_000_000L * 1024,
        cpuCountRuntime = 2,
        cpuInfo = "processor : 0\nHardware : MT6765\nprocessor : 1\n",
        partitions = emptyList(),
        dataTotalBytes = 64L * 1024 * 1024 * 1024,
        displayWidth = 1080,
        displayHeight = 2340,
        densityDpi = 420,
        wmSize = "Physical size: 1080x2340",
        wmDensity = 420,
        sensors = listOf("BMI160 accelerometer", "AK09918 magnetometer"),
        cameras = listOf("Cámara 0 · trasera"),
        features = mapOf("android.hardware.camera" to true),
        batteryCapacitySysFs = 82,
        batteryCapacityManager = 81,
        chargeFullMah = 4_200.0,
        chargeFullDesignMah = 5_000.0,
    )
}
