package com.devicescope.app.data

import kotlin.math.abs

data class PartitionInfo(val name: String, val blocks: Long)

/**
 * Instantánea de fuentes independientes. Cuanto más se contradigan entre sí,
 * más probable es que el equipo tenga propiedades falsificadas.
 */
data class IdentitySnapshot(
    val build: Map<String, String>,
    val props: Map<String, String>,
    val memInfo: Map<String, Long>,
    val activityTotalMemBytes: Long,
    val cpuCountRuntime: Int,
    val cpuInfo: String,
    val partitions: List<PartitionInfo>,
    val dataTotalBytes: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val densityDpi: Int,
    val wmSize: String?,
    val wmDensity: Int?,
    val sensors: List<String>,
    val cameras: List<String>,
    val features: Map<String, Boolean>,
    val batteryCapacitySysFs: Int?,
    val batteryCapacityManager: Int?,
    val chargeFullMah: Double?,
    val chargeFullDesignMah: Double?,
)

/**
 * Motor de coherencia. No "rompe" falsificaciones: las detecta comparando
 * fuentes que un spoofing superficial suele parchear por separado.
 * Es lógica pura para poder probarla en JVM sin Android.
 */
object DiagnosticAudit {

    fun run(snapshot: IdentitySnapshot): List<AuditFinding> {
        val findings = mutableListOf<AuditFinding>()
        val props = snapshot.props
        val build = snapshot.build
        var comparisons = 0

        fun compare(label: String, buildValue: String?, vararg propKeys: String) {
            val api = buildValue?.takeIf { it.isNotBlank() } ?: return
            val prop = propKeys.firstNotNullOfOrNull { key -> props[key]?.takeIf { it.isNotBlank() } }
                ?: return
            comparisons++
            if (!same(api, prop)) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "$label inconsistente entre fuentes",
                    "Build API dice «$api», pero getprop dice «$prop» (${propKeys.joinToString()}).",
                )
            }
        }

        compare("Modelo", build["MODEL"], "ro.product.model", "ro.product.system.model", "ro.product.vendor.model")
        compare("Fabricante", build["MANUFACTURER"], "ro.product.manufacturer", "ro.product.system.manufacturer")
        compare("Marca", build["BRAND"], "ro.product.brand", "ro.product.system.brand")
        compare("Dispositivo", build["DEVICE"], "ro.product.device", "ro.product.system.device")
        compare("Producto", build["PRODUCT"], "ro.product.name", "ro.product.product", "ro.build.product")
        compare("Placa", build["BOARD"], "ro.product.board", "ro.board.platform")
        compare("Hardware", build["HARDWARE"], "ro.hardware", "ro.board.platform")
        compare("Bootloader", build["BOOTLOADER"], "ro.boot.bootloader")
        compare("Huella", build["FINGERPRINT"], "ro.build.fingerprint", "ro.bootimage.build.fingerprint")
        compare("Versión Android", build["RELEASE"], "ro.build.version.release")
        compare("Nivel de API", build["SDK"], "ro.build.version.sdk")
        compare("Parche de seguridad", build["SECURITY_PATCH"], "ro.build.version.security_patch")
        compare("Tipo de build", build["TYPE"], "ro.build.type")
        compare("Tags de build", build["TAGS"], "ro.build.tags")
        compare("Usuario de build", build["USER"], "ro.build.user")
        compare("Host de build", build["HOST"], "ro.build.host")
        compare("Id de build", build["ID"], "ro.build.id")
        compare("Display id", build["DISPLAY"], "ro.build.display.id")

        // ABIs declaradas vs lista de getprop.
        val abiList = props["ro.product.cpu.abilist"]?.split(",")?.map { it.trim() }.orEmpty()
        val buildAbis = build["SUPPORTED_ABIS"].orEmpty().split(",").map { it.trim() }.filter(String::isNotBlank)
        if (abiList.isNotEmpty() && buildAbis.isNotEmpty()) {
            comparisons++
            if (abiList.firstOrNull()?.let { !same(it, buildAbis.first()) } == true) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "ABI principal inconsistente",
                    "ro.product.cpu.abilist=${abiList.joinToString()} · Build.SUPPORTED_ABIS=${buildAbis.joinToString()}.",
                )
            }
        }

        // Versiones de producto partidas (Android 10+): system/vendor/product/odm.
        val splitModels = listOf(
            "ro.product.system.model",
            "ro.product.vendor.model",
            "ro.product.product.model",
            "ro.product.odm.model",
        ).mapNotNull { key -> props[key]?.takeIf { it.isNotBlank() } }.distinct()
        val mainModel = props["ro.product.model"]
        if (!mainModel.isNullOrBlank() && splitModels.size > 1) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Múltiples modelos declarados",
                "Modelo principal: «$mainModel» · variantes: ${splitModels.joinToString()}.",
            )
        }

        // Firmware de prueba / depuración.
        val tags = build["TAGS"].orEmpty()
        val buildType = build["TYPE"].orEmpty().lowercase()
        if (tags.contains("test-keys", ignoreCase = true) || buildType == "eng" || buildType == "userdebug") {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Firmware de desarrollo",
                "TAGS=$tags · TYPE=$buildType. Un equipo de producción debería ser «user/ release-keys».",
            )
        }
        if (props["ro.debuggable"] == "1" || props["ro.secure"] == "0") {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Depuración activa",
                "ro.debuggable=${props["ro.debuggable"]} · ro.secure=${props["ro.secure"]}.",
            )
        }
        if (build["FINGERPRINT"].orEmpty().contains("generic", ignoreCase = true) ||
            mainModel.orEmpty().contains("generic", ignoreCase = true)
        ) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Identidad genérica",
                "La huella o el modelo contienen «generic», típico de emuladores y clones.",
            )
        }
        if (!mainModel.isNullOrBlank() && same(mainModel, props["ro.product.device"].orEmpty())) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Modelo y dispositivo idénticos",
                "«$mainModel» se usa como modelo y como dispositivo; es común en equipos clonados.",
            )
        }

        // RAM: /proc/meminfo vs ActivityManager.
        val memTotalKb = snapshot.memInfo["MemTotal"]
        val activityMb = snapshot.activityTotalMemBytes / 1024.0 / 1024.0
        if (memTotalKb != null && memTotalKb > 0 && activityMb > 0) {
            comparisons++
            val procMb = memTotalKb / 1024.0
            val diff = abs(procMb - activityMb) / maxOf(procMb, activityMb)
            if (diff > 0.15) {
                findings += AuditFinding(
                    DiagnosticStatus.ERROR,
                    "RAM declarada inconsistente",
                    "/proc/meminfo=${procMb.format(1)} MB · ActivityManager=${activityMb.format(1)} MB (diferencia ${(diff * 100).format(0)} %).",
                )
            }
        }

        // Heapsize absurdo para la RAM real.
        val heap = props["dalvik.vm.heapsize"].orEmpty()
        val heapMb = heap.removeSuffix("m").removeSuffix("M").toDoubleOrNull()
        if (heapMb != null && memTotalKb != null && memTotalKb > 0) {
            val ramMb = memTotalKb / 1024.0
            if (heapMb > ramMb * 0.75) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Heap de Dalvik desproporcionado",
                    "dalvik.vm.heapsize=$heap con solo ${ramMb.format(0)} MB de RAM.",
                )
            }
        }
        if (props["ro.config.low_ram"] == "true" && memTotalKb != null && memTotalKb > 2_000_000) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Bandera low_ram incoherente",
                "ro.config.low_ram=true con ${memTotalKb / 1024} MB de RAM.",
            )
        }

        // CPU: número de núcleos y plataforma.
        val cpuCountProc = snapshot.cpuInfo.lineSequence()
            .count { it.trim().startsWith("processor") && it.contains(":") }
        if (cpuCountProc > 0 && snapshot.cpuCountRuntime > 0) {
            comparisons++
            if (abs(cpuCountProc - snapshot.cpuCountRuntime) > 2) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Número de núcleos inconsistente",
                    "/proc/cpuinfo=$cpuCountProc · Runtime.availableProcessors()=${snapshot.cpuCountRuntime}.",
                )
            }
        }
        val cpuHardware = snapshot.cpuInfo.lineSequence()
            .firstOrNull { it.startsWith("Hardware", ignoreCase = true) }
            ?.substringAfter(":")?.trim().orEmpty()
        val platform = listOfNotNull(
            props["ro.board.platform"],
            props["ro.hardware"],
            build["HARDWARE"],
            build["BOARD"],
        ).firstOrNull().orEmpty()
        if (cpuHardware.isNotBlank() && platform.isNotBlank()) {
            comparisons++
            val normalizedHardware = cpuHardware.lowercase().replace("_", "")
            val normalizedPlatform = platform.lowercase().replace("_", "")
            val compatible = normalizedHardware.contains(normalizedPlatform.take(4)) ||
                normalizedPlatform.contains(normalizedHardware.take(4)) ||
                listOf("mt", "qcom", "exynos", "unisoc", "kirin", "sdm", "sm").any {
                    normalizedHardware.contains(it) && normalizedPlatform.contains(it)
                }
            if (!compatible) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Plataforma de CPU dudosa",
                    "/proc/cpuinfo Hardware=«$cpuHardware» · plataforma declarada=«$platform».",
                )
            }
        }

        // Almacenamiento: partición userdata vs StatFs.
        val userdata = snapshot.partitions
            .filter { it.name.contains("userdata", ignoreCase = true) || it.name.contains("data", ignoreCase = true) }
            .maxByOrNull { it.blocks }
        if (userdata != null && userdata.blocks > 0 && snapshot.dataTotalBytes > 0) {
            comparisons++
            val partitionMb = userdata.blocks * 1024.0 / 1024.0 / 1024.0
            val statMb = snapshot.dataTotalBytes / 1024.0 / 1024.0
            if (statMb > partitionMb * 1.8) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Almacenamiento inflado",
                    "StatFs=/data=${statMb.format(0)} MB · partición ${userdata.name}=${partitionMb.format(0)} MB.",
                )
            }
        }

        // Pantalla: DisplayMetrics vs WindowManager (wm).
        val wmResolution = snapshot.wmSize?.let { parseResolution(it) }
        if (wmResolution != null && snapshot.displayWidth > 0) {
            comparisons++
            val sameOrientation = wmResolution.first == snapshot.displayWidth && wmResolution.second == snapshot.displayHeight
            val rotated = wmResolution.first == snapshot.displayHeight && wmResolution.second == snapshot.displayWidth
            if (!sameOrientation && !rotated) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Resolución declarada inconsistente",
                    "DisplayMetrics=${snapshot.displayWidth}x${snapshot.displayHeight} · wm size=${wmResolution.first}x${wmResolution.second}.",
                )
            }
        }
        if (snapshot.wmDensity != null && snapshot.densityDpi > 0) {
            comparisons++
            if (abs(snapshot.wmDensity - snapshot.densityDpi) > snapshot.densityDpi * 0.2) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Densidad declarada inconsistente",
                    "DisplayMetrics.densityDpi=${snapshot.densityDpi} · wm density=${snapshot.wmDensity}.",
                )
            }
        }

        // Sensores / cámaras ausentes.
        if (snapshot.sensors.isEmpty()) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Sin sensores detectados",
                "SensorManager devolvió una lista vacía; un clon o un entorno restringido puede ocultarlos.",
            )
        }
        if (snapshot.cameras.isEmpty() && snapshot.features["android.hardware.camera"] == true) {
            findings += AuditFinding(
                DiagnosticStatus.WARNING,
                "Cámara declarada pero no enumerada",
                "El sistema declara android.hardware.camera, pero CameraManager no lista ninguna cámara.",
            )
        }

        // Batería: capacidad sysfs vs BatteryManager y desgaste.
        val sysCapacity = snapshot.batteryCapacitySysFs
        val managerCapacity = snapshot.batteryCapacityManager
        if (sysCapacity != null && managerCapacity != null) {
            comparisons++
            if (abs(sysCapacity - managerCapacity) > 15) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Capacidad de batería inconsistente",
                    "sysfs capacity=$sysCapacity % · BATTERY_PROPERTY_CAPACITY=$managerCapacity %.",
                )
            }
        }
        val full = snapshot.chargeFullMah
        val design = snapshot.chargeFullDesignMah
        if (full != null && design != null && design > 0) {
            comparisons++
            val health = full / design * 100.0
            if (health < 70.0) {
                findings += AuditFinding(
                    DiagnosticStatus.WARNING,
                    "Batería desgastada",
                    "Carga máxima actual=${full.format(0)} mAh frente a diseño=${design.format(0)} mAh (${health.format(0)} %).",
                )
            }
        }

        findings += AuditFinding(
            DiagnosticStatus.OK,
            "Comparación de fuentes completada",
            "Se contrastaron $comparisons pares de valores entre Build API, getprop, /proc, WindowManager y sysfs. " +
                "La ausencia de hallazgos no prueba autenticidad: un rootkit con acceso al kernel puede falsear todas las fuentes.",
        )
        return findings.sortedBy { severityOrder(it.severity) }
    }

    fun sectionStatus(
        metrics: List<DiagnosticMetric>,
        findings: List<AuditFinding>,
    ): DiagnosticStatus = when {
        findings.any { it.severity == DiagnosticStatus.ERROR } ||
            metrics.any { it.status == DiagnosticStatus.ERROR } -> DiagnosticStatus.ERROR
        findings.any { it.severity == DiagnosticStatus.WARNING } ||
            metrics.any { it.status == DiagnosticStatus.WARNING } -> DiagnosticStatus.WARNING
        else -> DiagnosticStatus.OK
    }

    fun overall(
        findings: List<AuditFinding>,
        sections: List<DiagnosticSection>,
    ): DiagnosticStatus = when {
        findings.any { it.severity == DiagnosticStatus.ERROR } ||
            sections.any { it.status == DiagnosticStatus.ERROR } -> DiagnosticStatus.ERROR
        findings.any { it.severity == DiagnosticStatus.WARNING } ||
            sections.any { it.status == DiagnosticStatus.WARNING } -> DiagnosticStatus.WARNING
        sections.isEmpty() -> DiagnosticStatus.UNKNOWN
        else -> DiagnosticStatus.OK
    }

    fun severityOrder(status: DiagnosticStatus): Int = when (status) {
        DiagnosticStatus.ERROR -> 0
        DiagnosticStatus.WARNING -> 1
        DiagnosticStatus.OK -> 2
        DiagnosticStatus.UNKNOWN -> 3
    }

    private fun same(a: String, b: String): Boolean {
        fun normalize(value: String) = value.lowercase()
            .replace(Regex("[_\\s-]+"), "")
            .replace("google", "")
            .trim('"')
        return normalize(a) == normalize(b)
    }

    private fun parseResolution(wmOutput: String): Pair<Int, Int>? {
        val match = Regex("(\\d{3,5})x(\\d{3,5})").find(wmOutput) ?: return null
        return match.groupValues[1].toIntOrNull()?.let { w ->
            match.groupValues[2].toIntOrNull()?.let { h -> w to h }
        }
    }

    private fun Double.format(decimals: Int): String {
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            else -> 100.0
        }
        return ((kotlin.math.round(this * factor)) / factor).toString()
    }
}
