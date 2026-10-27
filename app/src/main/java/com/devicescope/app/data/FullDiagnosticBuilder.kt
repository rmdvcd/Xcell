package com.devicescope.app.data

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.round

/**
 * Construye el informe de un ÚNICO módulo: lee el equipo desde fuentes
 * independientes (Build API, getprop, /proc, sysfs, servicios del sistema) y
 * contrasta unas con otras para detectar falsificaciones superficiales.
 *
 * IMPORTANTE: esto se ejecuta en Dispatchers.IO. Lee archivos y ejecuta
 * comandos del sistema; nunca usa red. No declara ni necesita INTERNET.
 */
object FullDiagnosticBuilder {

    private val FEATURE_KEYS = listOf(
        "android.hardware.telephony",
        "android.hardware.telephony.gsm",
        "android.hardware.telephony.cdma",
        "android.hardware.wifi",
        "android.hardware.wifi.direct",
        "android.hardware.wifi.passpoint",
        "android.hardware.bluetooth",
        "android.hardware.bluetooth_le",
        "android.hardware.nfc",
        "android.hardware.nfc.hce",
        "android.hardware.location.gps",
        "android.hardware.location.network",
        "android.hardware.camera",
        "android.hardware.camera.front",
        "android.hardware.camera.any",
        "android.hardware.camera.flash",
        "android.hardware.consumerir",
        "android.hardware.usb.accessory",
        "android.hardware.usb.host",
        "android.hardware.touchscreen",
        "android.hardware.touchscreen.multitouch",
        "android.hardware.touchscreen.multitouch.distinct",
        "android.hardware.touchscreen.multitouch.jazzhand",
        "android.hardware.sensor.accelerometer",
        "android.hardware.sensor.gyroscope",
        "android.hardware.sensor.compass",
        "android.hardware.sensor.barometer",
        "android.hardware.sensor.proximity",
        "android.hardware.sensor.light",
        "android.hardware.sensor.stepcounter",
        "android.hardware.fingerprint",
        "android.hardware.audio.low_latency",
        "android.hardware.audio.pro",
        "android.hardware.microphone",
        "android.software.sip",
    )

    private val POWER_SUPPLY_NODES = listOf(
        "type", "status", "health", "technology", "present", "online", "scope",
        "voltage_now", "voltage_avg", "voltage_max", "voltage_min", "voltage_max_design",
        "current_now", "current_avg", "current_max", "power_now",
        "temp", "capacity", "charge_full", "charge_full_design", "charge_counter",
        "cycle_count", "input_current_limited", "authentic", "real_type", "battery_type",
    )

    private data class AudioProbe(
        val metrics: Map<String, String>,
        val inputs: List<String>,
        val outputs: List<String>,
    )

    private data class InputProbe(
        val metrics: Map<String, String>,
        val devices: List<String>,
    )

    private data class StorageProbe(
        val metrics: Map<String, String>,
        val dataTotalBytes: Long,
        val volumes: List<RemovableVolume>,
    )

    private data class BatteryProbe(
        val percent: Int,
        val statusLabel: String,
        val healthLabel: String,
        val technology: String?,
        val voltageMv: Int,
        val temperatureC: Double?,
        val currentMa: Double?,
        val avgCurrentMa: Double?,
        val chargeCounterUah: Long?,
        val capacityPercent: Int?,
        val energyNwh: Long?,
        val chargeTimeRemainingMinutes: Double?,
        val plugged: Int,
        val pluggedLabel: String,
        val present: Boolean,
        val low: Boolean,
        val sysCapacity: Int?,
        val chargeFullMah: Double?,
        val chargeFullDesignMah: Double?,
        val cycleCount: Int?,
        val sysHealth: String?,
        val sysStatus: String?,
        val authentic: String?,
    )

    private data class ProbeData(
        val buildMap: Map<String, String>,
        val props: Map<String, String>,
        val memory: Map<String, String>,
        val storage: StorageProbe,
        val display: Map<String, String>,
        val audio: AudioProbe,
        val input: InputProbe,
        val radios: Map<String, String>,
        val network: Map<String, String>,
        val battery: BatteryProbe,
        val plugs: Map<String, String>,
        val usb: Map<String, String>,
        val sensors: List<String>,
        val cameras: List<String>,
        val features: Map<String, Boolean>,
        val voltageRails: List<VoltageRail>,
        val thermalZones: List<ThermalZone>,
        val cpuFrequencies: List<Pair<String, String>>,
        val propertyGroups: List<RawPropertyGroup>,
        val findings: List<AuditFinding>,
        val permissions: ProbePermissions,
    )

    fun build(context: Context, permissions: ProbePermissions): FullDiagnosticReport {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()

        val props = readGetprop()
        val buildMap = readBuildMap(permissions)
        val cpuInfo = readFile("/proc/cpuinfo", 600) ?: ""
        val memInfo = readMemInfo()
        val partitions = readPartitions()
        val wmSize = wmCommand("size")
        val wmDensity = wmCommand("density")?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)

        @Suppress("DEPRECATION")
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(displayMetrics)

        val sensors = readSensors(appContext)
        val cameras = readCameras(appContext)
        val features = readFeatures(appContext)
        val storage = readStorage(appContext)
        val audio = readAudio(appContext)
        val input = readInput(appContext)
        val radios = readRadios(appContext, permissions, features)
        val network = readNetwork(appContext, permissions)
        val battery = readBattery(appContext)
        val plugs = readPlugs(appContext, battery, props)
        val usb = readUsb(appContext)
        val voltageRails = readVoltageRails(battery)
        val thermalZones = readThermalZones()
        val cpuFrequencies = readCpuFrequencies()

        val memory = buildMapOf(
            "RAM total (ActivityManager)" to formatBytes(memoryInfo.totalMem),
            "RAM total (/proc/meminfo)" to (memInfo["MemTotal"]?.let { formatBytes(it * 1024L) } ?: "No disponible"),
            "RAM disponible" to formatBytes(memoryInfo.availMem),
            "Umbral de memoria baja" to formatBytes(memoryInfo.threshold),
            "Memoria baja ahora" to yesNo(memoryInfo.lowMemory),
            "MemFree (/proc)" to (memInfo["MemFree"]?.let { formatBytes(it * 1024L) } ?: "No disponible"),
            "MemAvailable (/proc)" to (memInfo["MemAvailable"]?.let { formatBytes(it * 1024L) } ?: "No disponible"),
            "Cached (/proc)" to (memInfo["Cached"]?.let { formatBytes(it * 1024L) } ?: "No disponible"),
            "SwapTotal (/proc)" to (memInfo["SwapTotal"]?.let { formatBytes(it * 1024L) } ?: "No disponible"),
            "Heap máximo Dalvik" to formatBytes(Runtime.getRuntime().maxMemory()),
            "dalvik.vm.heapsize" to (props["dalvik.vm.heapsize"] ?: "No declarado"),
            "ro.config.low_ram" to (props["ro.config.low_ram"] ?: "false"),
        )

        val displayMap = readDisplay(appContext, display, displayMetrics, wmSize, wmDensity)

        val identitySnapshot = IdentitySnapshot(
            build = buildMap,
            props = props,
            memInfo = memInfo,
            activityTotalMemBytes = memoryInfo.totalMem,
            cpuCountRuntime = Runtime.getRuntime().availableProcessors(),
            cpuInfo = cpuInfo,
            partitions = partitions,
            dataTotalBytes = storage.dataTotalBytes,
            displayWidth = displayMetrics.widthPixels,
            displayHeight = displayMetrics.heightPixels,
            densityDpi = displayMetrics.densityDpi,
            wmSize = wmSize,
            wmDensity = wmDensity,
            sensors = sensors,
            cameras = cameras,
            features = features,
            batteryCapacitySysFs = battery.sysCapacity,
            batteryCapacityManager = battery.capacityPercent,
            chargeFullMah = battery.chargeFullMah,
            chargeFullDesignMah = battery.chargeFullDesignMah,
        )
        val findings = DiagnosticAudit.run(identitySnapshot)

        val propertyGroups = buildPropertyGroups(
            buildMap = buildMap,
            props = props,
            cpuInfo = cpuInfo,
            memInfoText = readFile("/proc/meminfo", 100) ?: "",
            partitionsText = readFile("/proc/partitions", 100) ?: "",
            mountsText = readFile("/proc/mounts", 200) ?: "",
            versionText = readFile("/proc/version", 10) ?: "",
            sensors = sensors,
            cameras = cameras,
            features = features,
            input = input,
            audio = audio,
            usb = usb,
            voltageRails = voltageRails,
            thermalZones = thermalZones,
            cpuFrequencies = cpuFrequencies,
            wmSize = wmSize,
            wmDensity = wmDensity,
            dumpsysBattery = runCommand(arrayOf("dumpsys", "battery")),
        )

        val data = ProbeData(
            buildMap = buildMap,
            props = props,
            memory = memory,
            storage = storage,
            display = displayMap,
            audio = audio,
            input = input,
            radios = radios,
            network = network,
            battery = battery,
            plugs = plugs,
            usb = usb,
            sensors = sensors,
            cameras = cameras,
            features = features,
            voltageRails = voltageRails,
            thermalZones = thermalZones,
            cpuFrequencies = cpuFrequencies,
            propertyGroups = propertyGroups,
            findings = findings,
            permissions = permissions,
        )
        val sections = buildSections(data)
        val overall = DiagnosticAudit.overall(findings, sections)
        return FullDiagnosticReport(
            generatedAtMillis = now,
            sections = sections,
            voltageRails = voltageRails,
            thermalZones = thermalZones,
            auditFindings = findings,
            propertyGroups = propertyGroups,
            overall = overall,
        )
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun requestedPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun permissionsFrom(context: Context): ProbePermissions = ProbePermissions(
        readPhoneState = hasPermission(context, Manifest.permission.READ_PHONE_STATE),
        recordAudio = hasPermission(context, Manifest.permission.RECORD_AUDIO),
        nearbyWifi = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES),
        bluetoothConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT),
    )

    // ---------------------------------------------------------------------
    // Lecturas de identidad y propiedades
    // ---------------------------------------------------------------------

    private fun readGetprop(): Map<String, String> {
        val output = runCommand(arrayOf("getprop")) ?: return emptyMap()
        val regex = Regex("""^\[([^\]]+)\]:\s*\[(.*)\]$""")
        return output.lineSequence().mapNotNull { line ->
            val match = regex.find(line.trim()) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2]
        }.toMap()
    }

    @Suppress("DEPRECATION")
    private fun readBuildMap(permissions: ProbePermissions): Map<String, String> = buildMap {
        put("MANUFACTURER", Build.MANUFACTURER)
        put("BRAND", Build.BRAND)
        put("MODEL", Build.MODEL)
        put("DEVICE", Build.DEVICE)
        put("PRODUCT", Build.PRODUCT)
        put("BOARD", Build.BOARD)
        put("HARDWARE", Build.HARDWARE)
        put("BOOTLOADER", Build.BOOTLOADER)
        put("DISPLAY", Build.DISPLAY)
        put("FINGERPRINT", Build.FINGERPRINT)
        put("ID", Build.ID)
        put("TAGS", Build.TAGS)
        put("TYPE", Build.TYPE)
        put("USER", Build.USER)
        put("HOST", Build.HOST)
        put("RELEASE", Build.VERSION.RELEASE)
        put("SDK", Build.VERSION.SDK_INT.toString())
        put("SECURITY_PATCH", Build.VERSION.SECURITY_PATCH)
        put("SUPPORTED_ABIS", Build.SUPPORTED_ABIS.joinToString())
        put("SUPPORTED_32_BIT_ABIS", Build.SUPPORTED_32_BIT_ABIS.joinToString())
        put("SUPPORTED_64_BIT_ABIS", Build.SUPPORTED_64_BIT_ABIS.joinToString())
        put("RADIO_VERSION", Build.getRadioVersion() ?: "No disponible")
        put(
            "SERIAL",
            if (permissions.readPhoneState) {
                runCatching { Build.getSerial() }.getOrDefault("No disponible")
            } else {
                "Requiere READ_PHONE_STATE"
            },
        )
    }

    private fun readFile(path: String, maxLines: Int = 400): String? = runCatching {
        File(path).readLines(Charsets.UTF_8).take(maxLines).joinToString("\n")
    }.getOrNull()

    private fun runCommand(command: Array<String>): String? = runCatching {
        val process = Runtime.getRuntime().exec(command)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        runCatching { process.waitFor() }
        process.destroy()
        output
    }.getOrNull()

    private fun wmCommand(argument: String): String? =
        runCommand(arrayOf("/system/bin/wm", argument)) ?: runCommand(arrayOf("wm", argument))

    private fun readMemInfo(): Map<String, Long> {
        val text = readFile("/proc/meminfo", 200) ?: return emptyMap()
        val regex = Regex("""^([^:]+):\s*(\d+)\s*kB""")
        return text.lineSequence().mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val value = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            match.groupValues[1].trim() to value
        }.toMap()
    }

    private fun readPartitions(): List<PartitionInfo> {
        val text = readFile("/proc/partitions", 200) ?: return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""))
            if (parts.size < 4) return@mapNotNull null
            val blocks = parts[2].toLongOrNull() ?: return@mapNotNull null
            PartitionInfo(parts[3], blocks)
        }.toList()
    }

    private fun readPropFile(path: String): Map<String, String> {
        val text = readFile(path, 500) ?: return emptyMap()
        return text.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@mapNotNull null
            val index = trimmed.indexOf('=')
            trimmed.substring(0, index).trim() to trimmed.substring(index + 1).trim()
        }.toMap()
    }

    private fun readSensors(context: Context): List<String> {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return emptyList()
        return runCatching {
            manager.getSensorList(Sensor.TYPE_ALL).map {
                "${it.name} · ${it.vendor} · v${it.version} · tipo ${it.type} · ${it.stringType}"
            }
        }.getOrDefault(emptyList())
    }

    private fun readCameras(context: Context): List<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching {
            manager.cameraIdList.toList().map { id ->
                val facing = runCatching {
                    when (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
                        CameraCharacteristics.LENS_FACING_BACK -> "trasera"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "externa"
                        else -> "desconocida"
                    }
                }.getOrDefault("sin acceso")
                "Cámara $id · $facing"
            }
        }.getOrDefault(emptyList())
    }

    private fun readFeatures(context: Context): Map<String, Boolean> =
        FEATURE_KEYS.associateWith { context.packageManager.hasSystemFeature(it) }

    // ---------------------------------------------------------------------
    // Componentes
    // ---------------------------------------------------------------------

    private fun readStorage(context: Context): StorageProbe {
        val dataDir = Environment.getDataDirectory()
        val statFs = runCatching { StatFs(dataDir.absolutePath) }.getOrNull()
        val total = statFs?.totalBytes ?: 0L
        val available = statFs?.availableBytes ?: 0L
        val free = statFs?.freeBytes ?: 0L
        @Suppress("DEPRECATION")
        val externalDir = Environment.getExternalStorageDirectory()
        val externalStat = runCatching { StatFs(externalDir.absolutePath) }.getOrNull()
        val df = runCommand(arrayOf("df", "-k", "/data"))?.trim().orEmpty()
        val volumes = StorageDiagnosticReader.readRemovableVolumes(context).valueOrNull.orEmpty()
        val speed = measureInternalSpeed(context)
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val systemVolumes = runCatching {
            storageManager?.getStorageVolumes()?.map { volume ->
                "${volume.getDescription(context)} · extraíble=${volume.isRemovable} · estado=${volume.state}"
            }.orEmpty()
        }.getOrDefault(emptyList())

        val metrics = buildMapOf(
            "Almacenamiento interno total" to formatBytes(total),
            "Disponible (/data)" to formatBytes(available),
            "Libre (/data)" to formatBytes(free),
            "Almacenamiento externo total" to (externalStat?.totalBytes?.let(::formatBytes) ?: "No disponible"),
            "Particiones / volúmenes" to systemVolumes.joinToString(" | ").ifBlank { "No disponible" },
            "Volúmenes extraíbles" to volumes.size.toString(),
            "df -k /data" to df.lineSequence().lastOrNull().orEmpty().ifBlank { "No disponible" },
            "Escritura interna (5 MB)" to (speed?.let { "${it.first.fmt(1)} MB/s" } ?: "No disponible"),
            "Lectura interna (5 MB)" to (speed?.let { "${it.second.fmt(1)} MB/s" } ?: "No disponible"),
        )
        return StorageProbe(metrics, total, volumes)
    }

    private fun measureInternalSpeed(context: Context): Pair<Double, Double>? {
        val file = runCatching {
            File.createTempFile("devicescope-diag-", ".tmp", context.filesDir)
        }.getOrNull() ?: return null
        return try {
            val bytes = 5_000_000
            val buffer = ByteArray(64 * 1024) { 0x5A }
            val writeStart = SystemClock.elapsedRealtimeNanos()
            FileOutputStream(file).use { output ->
                var remaining = bytes
                while (remaining > 0) {
                    val count = minOf(buffer.size, remaining)
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                output.fd.sync()
            }
            val writeSeconds = (SystemClock.elapsedRealtimeNanos() - writeStart) / 1_000_000_000.0
            val readStart = SystemClock.elapsedRealtimeNanos()
            var readBytes = 0L
            BufferedInputStream(FileInputStream(file)).use { input ->
                val readBuffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(readBuffer)
                    if (count < 0) break
                    readBytes += count
                }
            }
            val readSeconds = (SystemClock.elapsedRealtimeNanos() - readStart) / 1_000_000_000.0
            if (readBytes != bytes.toLong()) return null
            val megabytes = bytes / 1_000_000.0
            (megabytes / writeSeconds.coerceAtLeast(0.001)) to
                (megabytes / readSeconds.coerceAtLeast(0.001))
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun readDisplay(
        context: Context,
        display: android.view.Display,
        metrics: DisplayMetrics,
        wmSize: String?,
        wmDensity: Int?,
    ): Map<String, String> {
        val modes = runCatching { display.supportedModes.map { "${it.physicalWidth}x${it.physicalHeight} @ ${it.refreshRate.toDouble().fmt(1)} Hz" } }
            .getOrDefault(emptyList())
        val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                display.hdrCapabilities?.supportedHdrTypes?.joinToString { hdrName(it) }
                    ?: "No disponible"
            }.getOrDefault("No disponible")
        } else {
            "No disponible (API < 24)"
        }
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1).toString()
        }.getOrDefault("No disponible")
        val brightnessMode = runCatching {
            when (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "Automático"
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "Manual"
                else -> "Desconocido"
            }
        }.getOrDefault("No disponible")
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(realMetrics)
        return buildMapOf(
            "Resolución (DisplayMetrics)" to "${metrics.widthPixels} x ${metrics.heightPixels} px",
            "Resolución real" to "${realMetrics.widthPixels} x ${realMetrics.heightPixels} px",
            "Densidad" to "${metrics.densityDpi} dpi",
            "xdpi / ydpi" to "${metrics.xdpi.toDouble().fmt(1)} / ${metrics.ydpi.toDouble().fmt(1)}",
            "Factor de escala" to metrics.density.toDouble().fmt(2),
            "Refresco actual" to "${display.refreshRate.toDouble().fmt(1)} Hz",
            "Modos soportados" to modes.joinToString(" | ").ifBlank { "No disponible" },
            "HDR soportado" to hdr,
            "Brillo (0-255)" to brightness,
            "Modo de brillo" to brightnessMode,
            "wm size" to (wmSize?.trim() ?: "No disponible"),
            "wm density" to (wmDensity?.toString() ?: "No disponible"),
            "Rotación" to rotationName(display.rotation),
        )
    }

    private fun hdrName(type: Int): String = when (type) {
        android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        else -> "Tipo $type"
    }

    private fun rotationName(rotation: Int): String = when (rotation) {
        android.view.Surface.ROTATION_0 -> "0°"
        android.view.Surface.ROTATION_90 -> "90°"
        android.view.Surface.ROTATION_180 -> "180°"
        android.view.Surface.ROTATION_270 -> "270°"
        else -> "Desconocida"
    }

    private fun readAudio(context: Context): AudioProbe {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val all = runCatching { manager.getDevices(AudioManager.GET_DEVICES_ALL).toList() }
            .getOrDefault(emptyList())
        val inputs = all.filter { it.isSource }.map { audioDeviceLabel(it) }
        val outputs = all.filter { it.isSink }.map { audioDeviceLabel(it) }
        val metrics = buildMapOf(
            "Dispositivos de entrada" to inputs.joinToString(" | ").ifBlank { "Ninguno detectado" },
            "Dispositivos de salida" to outputs.joinToString(" | ").ifBlank { "Ninguno detectado" },
            "Micrófono silenciado (API)" to yesNo(runCatching { manager.isMicrophoneMute }.getOrDefault(false)),
            "Auriculares cableados (API)" to yesNo(runCatching { manager.isWiredHeadsetOn }.getOrDefault(false)),
            "Altavoz externo activo" to yesNo(manager.isSpeakerphoneOn),
            "Volumen música" to "${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
            "Volumen timbre" to "${manager.getStreamVolume(AudioManager.STREAM_RING)} / ${manager.getStreamMaxVolume(AudioManager.STREAM_RING)}",
            "Volumen alarma" to "${manager.getStreamVolume(AudioManager.STREAM_ALARM)} / ${manager.getStreamMaxVolume(AudioManager.STREAM_ALARM)}",
            "Volumen llamada" to "${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} / ${manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}",
            "Modo de timbre" to ringerModeName(manager.ringerMode),
            "Frecuencia de salida" to (manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "No disponible"),
            "Frames por buffer" to (manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "No disponible"),
        )
        return AudioProbe(metrics, inputs, outputs)
    }

    private fun audioDeviceLabel(device: AudioDeviceInfo): String {
        val type = audioDeviceTypeLabel(device.type)
        val name = runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")
        return listOf(type, name).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun audioDeviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono integrado"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Bocina integrada"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular de llamada"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con micrófono"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "Audio USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "Auriculares USB"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telefonía"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Línea analógica"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Línea digital"
        AudioDeviceInfo.TYPE_FM_TUNER -> "Sintonizador FM"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Auxiliar"
        else -> "Tipo $type"
    }

    private fun ringerModeName(mode: Int): String = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "Normal"
        AudioManager.RINGER_MODE_SILENT -> "Silencio"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibración"
        else -> "Desconocido"
    }

    private fun readInput(context: Context): InputProbe {
        val devices = runCatching {
            InputDevice.getDeviceIds().map { id -> InputDevice.getDevice(id) }.filterNotNull()
        }.getOrDefault(emptyList())
        val touchDevices = devices.filter { device ->
            (device.sources and InputDevice.SOURCE_TOUCHSCREEN) != 0 ||
                (device.sources and InputDevice.SOURCE_TOUCH_NAVIGATION) != 0
        }
        val list = devices.map { device ->
            val rangeX = device.getMotionRange(MotionEvent.AXIS_X)
            val rangeY = device.getMotionRange(MotionEvent.AXIS_Y)
            "${device.name} · fuentes=${device.sources} · rango X=${rangeX?.max ?: "?"} Y=${rangeY?.max ?: "?"} · controlador=${device.controllerNumber}"
        }
        val metrics = buildMapOf(
            "Dispositivos de entrada" to devices.size.toString(),
            "Pantallas táctiles detectadas" to touchDevices.size.toString(),
            "Multitouch básico" to yesNo(context.packageManager.hasSystemFeature("android.hardware.touchscreen.multitouch")),
            "Multitouch distinct" to yesNo(context.packageManager.hasSystemFeature("android.hardware.touchscreen.multitouch.distinct")),
            "Multitouch jazzhand (5+)" to yesNo(context.packageManager.hasSystemFeature("android.hardware.touchscreen.multitouch.jazzhand")),
            "Lista de dispositivos" to list.joinToString(" | ").ifBlank { "No disponible" },
        )
        return InputProbe(metrics, list)
    }

    // ---------------------------------------------------------------------
    // Radios y red
    // ---------------------------------------------------------------------

    private fun readRadios(
        context: Context,
        permissions: ProbePermissions,
        features: Map<String, Boolean>,
    ): Map<String, String> {
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasPhone = permissions.readPhoneState && manager != null
        val signal: SignalStrength? = if (hasPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager?.getSignalStrength() }.getOrNull()
        } else {
            null
        }
        val cellDetails = signal?.cellSignalStrengths?.joinToString(" | ") { it.toString() }.orEmpty()
        val signalDbm = signal?.cellSignalStrengths?.map { it.dbm }?.firstOrNull { it in -150..-1 }
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = runCatching { wifiManager?.connectionInfo }.getOrNull()
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = runCatching { bluetoothManager?.adapter }.getOrNull()
        val btState = runCatching { adapter?.state }.getOrNull()
        val btName = if (permissions.bluetoothConnect || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { adapter?.name }.getOrNull()
        } else {
            "Requiere BLUETOOTH_CONNECT"
        }
        val bonded = if (permissions.bluetoothConnect || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { adapter?.bondedDevices?.size }.getOrNull()
        } else {
            null
        }
        val nfc = NfcAdapter.getDefaultAdapter(context)
        val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val gps = runCatching { location?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()

        return buildMapOf(
            "Características declaradas" to features.filterValues { it }.keys.joinToString(", ").ifBlank { "Ninguna" },
            "Características ausentes" to features.filterValues { !it }.keys.joinToString(", ").ifBlank { "Ninguna" },
            "Tipo de teléfono" to (manager?.let { phoneTypeName(it.getPhoneType()) } ?: "Sin telefonía"),
            "Estado SIM" to (manager?.let { simStateName(it.getSimState()) } ?: "No disponible"),
            "Operador de red" to if (hasPhone) runCatching { manager?.getNetworkOperatorName() }.getOrNull().orEmpty().ifBlank { "No disponible" } else "Requiere READ_PHONE_STATE",
            "País de la red" to if (hasPhone) runCatching { manager?.getNetworkCountryIso() }.getOrNull().orEmpty().ifBlank { "No disponible" } else "Requiere READ_PHONE_STATE",
            "Roaming" to if (hasPhone) yesNo(runCatching { manager?.isNetworkRoaming() }.getOrNull() ?: false) else "Requiere READ_PHONE_STATE",
            "Tipo de red móvil" to if (hasPhone) networkTypeName(runCatching { manager?.getNetworkType() }.getOrNull() ?: 0) else "Requiere READ_PHONE_STATE",
            "Estado de datos móviles" to if (hasPhone) dataStateName(runCatching { manager?.getDataState() }.getOrNull() ?: -1) else "Requiere READ_PHONE_STATE",
            "Actividad de datos" to if (hasPhone) dataActivityName(runCatching { manager?.getDataActivity() }.getOrNull() ?: -1) else "Requiere READ_PHONE_STATE",
            "Nivel de señal (0-4)" to (signal?.level?.toString() ?: "No disponible"),
            "Señal dBm" to (signalDbm?.let { "$it dBm" } ?: "No disponible"),
            "Detalle de celdas" to cellDetails.ifBlank { "No disponible (sin permiso o sin señal)" },
            "WiFi habilitado" to (wifiManager?.isWifiEnabled?.let(::yesNo) ?: "No disponible"),
            "Estado WiFi" to wifiStateName(wifiManager?.wifiState ?: -1),
            "WiFi RSSI" to (wifiInfo?.rssi?.let { "$it dBm" } ?: "No disponible"),
            "WiFi velocidad de enlace" to (wifiInfo?.linkSpeed?.let { "$it Mbps" } ?: "No disponible"),
            "WiFi frecuencia" to (wifiInfo?.frequency?.let { "$it MHz" } ?: "No disponible"),
            "WiFi SSID" to readWifiSsid(wifiInfo, permissions),
            "Bluetooth estado" to (btState?.let(::bluetoothStateName) ?: "No disponible / sin permiso"),
            "Bluetooth nombre" to (btName ?: "No disponible"),
            "Bluetooth emparejados" to (bonded?.toString() ?: "No disponible / sin permiso"),
            "Bluetooth LE múltiple" to (adapter?.isMultipleAdvertisementSupported?.let(::yesNo) ?: "No disponible"),
            "NFC" to (nfc?.let { yesNo(it.isEnabled) } ?: "No disponible"),
            "Proveedor GPS activo" to (gps?.let(::yesNo) ?: "No disponible"),
        )
    }

    @Suppress("DEPRECATION")
    private fun readWifiSsid(wifiInfo: android.net.wifi.WifiInfo?, permissions: ProbePermissions): String {
        if (wifiInfo == null) return "No disponible"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permissions.nearbyWifi) {
            runCatching { wifiInfo.ssid?.trim('"') }.getOrNull().orEmpty().ifBlank { "Oculto" }
        } else {
            "<oculto: requiere Android 13+ y permiso Dispositivos cercanos>"
        }
    }

    private fun readNetwork(context: Context, permissions: ProbePermissions): Map<String, String> {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = runCatching { manager.activeNetwork }.getOrNull()
        val caps = active?.let { runCatching { manager.getNetworkCapabilities(it) }.getOrNull() }
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to "Datos móviles",
            NetworkCapabilities.TRANSPORT_WIFI to "WiFi",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "Bluetooth",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_WIFI_AWARE to "WiFi Aware",
            NetworkCapabilities.TRANSPORT_LOWPAN to "LoWPAN",
        ).filter { caps?.hasTransport(it.first) == true }.joinToString(", ") { it.second }
        @Suppress("DEPRECATION")
        val activeInfo = runCatching { manager.activeNetworkInfo }.getOrNull()
        val allNetworks = runCatching { manager.allNetworks }.getOrDefault(emptyArray<android.net.Network>())
        val networkList = allNetworks.mapIndexedNotNull { index, network ->
            val info = runCatching { manager.getNetworkInfo(network) }.getOrNull()
            val networkCaps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            val type = info?.typeName ?: "Tipo ${info?.type ?: "?"}"
            val validated = networkCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            "Red ${index + 1}: $type · ${info?.state ?: "?"} · validada=$validated"
        }.joinToString(" | ").ifBlank { "No disponible" }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val mobileType = if (permissions.readPhoneState && telephony != null) {
            networkTypeName(runCatching { telephony.getNetworkType() }.getOrNull() ?: 0)
        } else {
            "Requiere READ_PHONE_STATE"
        }
        return buildMapOf(
            "Red activa" to (activeInfo?.typeName ?: "Sin red activa"),
            "Subtipo" to (activeInfo?.subtypeName?.ifBlank { "No disponible" } ?: "No disponible"),
            "Estado" to (activeInfo?.state?.toString() ?: "No disponible"),
            "Transportes" to transports.ifBlank { "Ninguno" },
            "Capacidad Internet" to yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true),
            "Conectividad validada" to yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
            "No medida (ilimitada)" to yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true),
            "Ancho de banda bajada" to (caps?.linkDownstreamBandwidthKbps?.let { "$it kbps" } ?: "No disponible"),
            "Ancho de banda subida" to (caps?.linkUpstreamBandwidthKbps?.let { "$it kbps" } ?: "No disponible"),
            "Redes del sistema" to networkList,
            "Tipo de red móvil" to mobileType,
            "Permiso READ_PHONE_STATE" to yesNo(permissions.readPhoneState),
            "Permiso NEARBY_WIFI_DEVICES" to yesNo(permissions.nearbyWifi),
            "Permiso BLUETOOTH_CONNECT" to yesNo(permissions.bluetoothConnect),
        )
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        TelephonyManager.PHONE_TYPE_NONE -> "Sin radio telefónica"
        else -> "Tipo $type"
    }

    private fun simStateName(state: Int): String = when (state) {
        TelephonyManager.SIM_STATE_ABSENT -> "Ausente"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN requerido"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK requerido"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Bloqueada por red"
        TelephonyManager.SIM_STATE_READY -> "Lista"
        TelephonyManager.SIM_STATE_NOT_READY -> "No lista"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "Deshabilitada"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Error de E/S"
        else -> "Estado $state"
    }

    @Suppress("DEPRECATION")
    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO 0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO A"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO B"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Tipo $type"
    }

    private fun dataStateName(state: Int): String = when (state) {
        TelephonyManager.DATA_DISCONNECTED -> "Desconectado"
        TelephonyManager.DATA_CONNECTING -> "Conectando"
        TelephonyManager.DATA_CONNECTED -> "Conectado"
        TelephonyManager.DATA_SUSPENDED -> "Suspendido"
        else -> "Estado $state"
    }

    private fun dataActivityName(activity: Int): String = when (activity) {
        TelephonyManager.DATA_ACTIVITY_NONE -> "Sin actividad"
        TelephonyManager.DATA_ACTIVITY_IN -> "Recibiendo"
        TelephonyManager.DATA_ACTIVITY_OUT -> "Enviando"
        TelephonyManager.DATA_ACTIVITY_INOUT -> "Enviando y recibiendo"
        TelephonyManager.DATA_ACTIVITY_DORMANT -> "Inactivo"
        else -> "Actividad $activity"
    }

    private fun wifiStateName(state: Int): String = when (state) {
        WifiManager.WIFI_STATE_DISABLED -> "Desactivado"
        WifiManager.WIFI_STATE_DISABLING -> "Desactivando"
        WifiManager.WIFI_STATE_ENABLED -> "Activado"
        WifiManager.WIFI_STATE_ENABLING -> "Activando"
        WifiManager.WIFI_STATE_UNKNOWN -> "Desconocido"
        else -> "Estado $state"
    }

    private fun bluetoothStateName(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "Apagado"
        BluetoothAdapter.STATE_ON -> "Encendido"
        BluetoothAdapter.STATE_TURNING_ON -> "Encendiendo"
        BluetoothAdapter.STATE_TURNING_OFF -> "Apagando"
        else -> "Estado $state"
    }

    // ---------------------------------------------------------------------
    // Batería, cargador y sysfs
    // ---------------------------------------------------------------------

    private fun readBattery(context: Context): BatteryProbe {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0 && level >= 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
        val present = intent?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ?: true

        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentUa = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeUnless { it == Int.MIN_VALUE }
        val avgUa = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?.takeUnless { it == Int.MIN_VALUE }
        val chargeCounter = manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }
        val capacityPercent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val energyNwh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)?.takeIf { it > 0 }
        } else {
            null
        }
        val chargeTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager?.computeChargeTimeRemaining() }.getOrNull()
                ?.takeIf { it > 0 }?.let { it / 60_000.0 }
        } else {
            null
        }

        val batteryDir = File("/sys/class/power_supply/battery")
        val sysCapacity = readNode(batteryDir, "capacity")?.toIntOrNull()
        val chargeFull = readNode(batteryDir, "charge_full")?.toDoubleOrNull()?.let { it / 1000.0 }
        val chargeFullDesign = readNode(batteryDir, "charge_full_design")?.toDoubleOrNull()?.let { it / 1000.0 }
        val cycleCount = readNode(batteryDir, "cycle_count")?.toIntOrNull()
        val sysHealth = readNode(batteryDir, "health")
        val sysStatus = readNode(batteryDir, "status")
        val authentic = readNode(batteryDir, "authentic") ?: readNode(batteryDir, "battery_authentic")

        return BatteryProbe(
            percent = percent,
            statusLabel = batteryStatusName(status),
            healthLabel = batteryHealthName(health),
            technology = technology,
            voltageMv = voltageMv,
            temperatureC = tempTenths.takeIf { it >= 0 }?.let { it / 10.0 },
            currentMa = currentUa?.let { it / 1000.0 },
            avgCurrentMa = avgUa?.let { it / 1000.0 },
            chargeCounterUah = chargeCounter,
            capacityPercent = capacityPercent,
            energyNwh = energyNwh,
            chargeTimeRemainingMinutes = chargeTime,
            plugged = plugged,
            pluggedLabel = pluggedName(plugged),
            present = present,
            low = percent in 0..15,
            sysCapacity = sysCapacity,
            chargeFullMah = chargeFull,
            chargeFullDesignMah = chargeFullDesign,
            cycleCount = cycleCount,
            sysHealth = sysHealth,
            sysStatus = sysStatus,
            authentic = authentic,
        )
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Cargando"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descargando"
        BatteryManager.BATTERY_STATUS_FULL -> "Llena"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Sin cargar"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "Desconocido"
        else -> "Estado $status"
    }

    private fun batteryHealthName(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Buena"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Sobrecalentamiento"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Agotada"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensión"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Fallo no especificado"
        BatteryManager.BATTERY_HEALTH_COLD -> "Fría"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Desconocida"
        else -> "Salud $health"
    }

    private fun pluggedName(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "Corriente alterna (AC)"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrica"
        0 -> "No conectado"
        else -> "Fuente $plugged"
    }

    private fun readPlugs(context: Context, battery: BatteryProbe, props: Map<String, String>): Map<String, String> {
        val headsetIntent = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_HEADSET_PLUG))
        val headsetState = headsetIntent?.getIntExtra("state", -1) ?: -1
        val headsetName = headsetIntent?.getStringExtra("name") ?: "No disponible"
        val headsetMic = headsetIntent?.getIntExtra("microphone", -1) ?: -1
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_ALL).toList() }
            .getOrDefault(emptyList())
        val wired = devices.filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }.map { audioDeviceLabel(it) }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val usbDevices = runCatching {
            usbManager?.getDeviceList()?.values?.map {
                "vid=${it.vendorId} pid=${it.productId} · ${it.deviceName} · clase=${it.deviceClass}"
            }.orEmpty()
        }.getOrDefault(emptyList())
        val accessories = runCatching {
            usbManager?.accessoryList?.map { "${it.manufacturer} / ${it.model}" }.orEmpty()
        }.getOrDefault(emptyList())
        return buildMapOf(
            "Conector de auriculares" to when (headsetState) {
                1 -> "Conectado ($headsetName)"
                0 -> "Desconectado"
                else -> "Estado desconocido"
            },
            "Micrófono en el conector" to when (headsetMic) {
                1 -> "Sí"
                0 -> "No"
                else -> "No disponible"
            },
            "Dispositivos de audio cableados/USB" to wired.joinToString(" | ").ifBlank { "Ninguno" },
            "Fuente de carga" to battery.pluggedLabel,
            "Batería presente" to yesNo(battery.present),
            "Configuración USB (getprop)" to (props["sys.usb.config"] ?: "No disponible"),
            "Dispositivos USB (host)" to usbDevices.joinToString(" | ").ifBlank { "Ninguno (lista vacía no prueba que el cable no tenga datos)" },
            "Accesorios USB" to accessories.joinToString(" | ").ifBlank { "Ninguno" },
        )
    }

    private fun readUsb(context: Context): Map<String, String> {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = runCatching {
            manager?.getDeviceList()?.values?.map {
                "vendor=${it.vendorId} product=${it.productId} clase=${it.deviceClass} subclase=${it.deviceSubclass} protocolo=${it.deviceProtocol} nombre=${it.deviceName}"
            }.orEmpty()
        }.getOrDefault(emptyList())
        val accessories = runCatching {
            manager?.accessoryList?.map {
                "fabricante=${it.manufacturer} modelo=${it.model} versión=${it.version}"
            }.orEmpty()
        }.getOrDefault(emptyList())
        return mapOf(
            "Dispositivos USB" to devices.joinToString(" | ").ifBlank { "Lista vacía" },
            "Accesorios USB" to accessories.joinToString(" | ").ifBlank { "Lista vacía" },
        )
    }

    private fun readVoltageRails(battery: BatteryProbe): List<VoltageRail> {
        val rails = mutableListOf<VoltageRail>()
        val supplyRoot = File("/sys/class/power_supply")
        runCatching {
            supplyRoot.listFiles()?.sortedBy { it.name }?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val raw = readNodes(dir, POWER_SUPPLY_NODES)
                rails += VoltageRail(
                    name = "power_supply/${dir.name}",
                    type = raw["type"],
                    voltageVolts = firstScaled(raw, "voltage_now", "voltage_avg", "voltage_max", divisor = 1_000_000.0),
                    currentAmps = firstScaled(raw, "current_now", "current_avg", "current_max", divisor = 1_000_000.0),
                    temperatureC = raw["temp"]?.toDoubleOrNull()?.let { it / 10.0 },
                    capacityPercent = raw["capacity"]?.toIntOrNull(),
                    chargeFullMah = raw["charge_full"]?.toDoubleOrNull()?.let { it / 1000.0 },
                    chargeFullDesignMah = raw["charge_full_design"]?.toDoubleOrNull()?.let { it / 1000.0 },
                    cycleCount = raw["cycle_count"]?.toIntOrNull(),
                    health = raw["health"],
                    chargingState = raw["status"],
                    technology = raw["technology"],
                    raw = raw,
                )
            }
        }
        val regulatorRoot = File("/sys/class/regulator")
        runCatching {
            regulatorRoot.listFiles()?.sortedBy { it.name }?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val raw = readNodes(dir, listOf("name", "microvolts", "state", "num_users", "min_microvolts", "max_microvolts"))
                rails += VoltageRail(
                    name = "regulator/${dir.name}",
                    type = raw["name"] ?: "regulator",
                    voltageVolts = raw["microvolts"]?.toDoubleOrNull()?.let { it / 1_000_000.0 },
                    currentAmps = null,
                    temperatureC = null,
                    capacityPercent = null,
                    chargeFullMah = null,
                    chargeFullDesignMah = null,
                    cycleCount = null,
                    health = raw["state"],
                    chargingState = null,
                    technology = null,
                    raw = raw,
                )
            }
        }
        rails += VoltageRail(
            name = "battery_manager",
            type = "Android BatteryManager API",
            voltageVolts = battery.voltageMv.takeIf { it > 0 }?.let { it / 1000.0 },
            currentAmps = battery.currentMa?.let { it / 1000.0 },
            temperatureC = battery.temperatureC,
            capacityPercent = battery.capacityPercent ?: battery.percent.takeIf { it >= 0 },
            chargeFullMah = battery.chargeFullMah,
            chargeFullDesignMah = battery.chargeFullDesignMah,
            cycleCount = battery.cycleCount,
            health = battery.healthLabel,
            chargingState = battery.statusLabel,
            technology = battery.technology,
            raw = mapOf(
                "source" to "BatteryManager",
                "plugged" to battery.pluggedLabel,
                "charge_counter_uAh" to (battery.chargeCounterUah?.toString() ?: "No disponible"),
                "energy_nWh" to (battery.energyNwh?.toString() ?: "No disponible"),
            ),
        )
        return rails
    }

    private fun readNodes(dir: File, names: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        names.forEach { name -> readNode(dir, name)?.let { result[name] = it } }
        return result
    }

    private fun readNode(dir: File, path: String): String? = runCatching {
        val file = File(dir, path)
        if (!file.exists() || !file.canRead()) return@runCatching null
        file.readText(Charsets.UTF_8).lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun firstScaled(raw: Map<String, String>, vararg keys: String, divisor: Double): Double? {
        keys.forEach { key ->
            raw[key]?.toDoubleOrNull()?.let { return it / divisor }
        }
        return null
    }

    private fun readThermalZones(): List<ThermalZone> {
        val zones = mutableListOf<ThermalZone>()
        val thermalRoot = File("/sys/class/thermal")
        runCatching {
            thermalRoot.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.sortedBy { it.name }
                ?.forEach { dir ->
                    val type = readNode(dir, "type")
                    val tempRaw = readNode(dir, "temp")?.toDoubleOrNull()
                    val celsius = tempRaw?.let { if (it > 1000.0) it / 1000.0 else it / 10.0 }
                    zones += ThermalZone(dir.name, type, celsius)
                }
        }
        val hwmonRoot = File("/sys/class/hwmon")
        runCatching {
            hwmonRoot.listFiles()?.sortedBy { it.name }?.forEach { hwmon ->
                hwmon.listFiles()?.filter { it.name.startsWith("temp") && it.name.endsWith("_input") }
                    ?.forEach { sensor ->
                        val millidegrees = readNode(hwmon, sensor.name)?.toDoubleOrNull()
                        val label = readNode(hwmon, sensor.name.removeSuffix("_input") + "_label")
                        zones += ThermalZone(
                            name = "${hwmon.name}/${sensor.name}",
                            type = label ?: "hwmon",
                            temperatureC = millidegrees?.let { it / 1000.0 },
                        )
                    }
            }
        }
        return zones
    }

    private fun readCpuFrequencies(): List<Pair<String, String>> {
        val root = File("/sys/devices/system/cpu")
        return runCatching {
            root.listFiles()?.filter { it.name.matches(Regex("""cpu\d+""")) }?.sortedBy { it.name }
                ?.map { cpu ->
                    val khz = readNode(cpu, "cpufreq/scaling_cur_freq")?.toLongOrNull()
                    val min = readNode(cpu, "cpufreq/scaling_min_freq")?.toLongOrNull()
                    val max = readNode(cpu, "cpufreq/scaling_max_freq")?.toLongOrNull()
                    val governor = readNode(cpu, "cpufreq/scaling_governor")
                    cpu.name to listOfNotNull(
                        khz?.let { "actual=${it / 1000} MHz" },
                        min?.let { "mín=${it / 1000} MHz" },
                        max?.let { "máx=${it / 1000} MHz" },
                        governor?.let { "gobernador=$it" },
                    ).joinToString(" · ").ifBlank { "sin acceso (SELinux)" }
                }.orEmpty()
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------------
    // Grupos de propiedades crudas
    // ---------------------------------------------------------------------

    private fun buildPropertyGroups(
        buildMap: Map<String, String>,
        props: Map<String, String>,
        cpuInfo: String,
        memInfoText: String,
        partitionsText: String,
        mountsText: String,
        versionText: String,
        sensors: List<String>,
        cameras: List<String>,
        features: Map<String, Boolean>,
        input: InputProbe,
        audio: AudioProbe,
        usb: Map<String, String>,
        voltageRails: List<VoltageRail>,
        thermalZones: List<ThermalZone>,
        cpuFrequencies: List<Pair<String, String>>,
        wmSize: String?,
        wmDensity: Int?,
        dumpsysBattery: String?,
    ): List<RawPropertyGroup> = buildList {
        add(RawPropertyGroup("Build API (android.os.Build)", buildMap.entries.sortedBy { it.key }.map { it.key to it.value }))
        add(RawPropertyGroup("getprop (todas las propiedades del sistema)", props.entries.sortedBy { it.key }.map { it.key to it.value }))
        listOf(
            "/system/build.prop",
            "/vendor/build.prop",
            "/product/build.prop",
            "/odm/build.prop",
            "/system/etc/build.prop",
        ).forEach { path ->
            val entries = readPropFile(path)
            if (entries.isNotEmpty()) {
                add(RawPropertyGroup(path, entries.entries.sortedBy { it.key }.map { it.key to it.value }))
            }
        }
        add(textGroup("/proc/version", versionText))
        add(textGroup("/proc/cpuinfo", cpuInfo))
        add(textGroup("/proc/meminfo", memInfoText))
        add(textGroup("/proc/partitions", partitionsText))
        add(textGroup("/proc/mounts", mountsText))
        add(RawPropertyGroup("Sensores", sensors.mapIndexed { index, value -> "sensor_$index" to value }))
        add(RawPropertyGroup("Cámaras", cameras.mapIndexed { index, value -> "camara_$index" to value }))
        add(RawPropertyGroup("Características declaradas", features.entries.sortedBy { it.key }.map { it.key to it.value.toString() }))
        add(RawPropertyGroup("Dispositivos de entrada", input.devices.mapIndexed { index, value -> "input_$index" to value }))
        add(RawPropertyGroup("Dispositivos de audio (entrada)", audio.inputs.mapIndexed { index, value -> "in_$index" to value }))
        add(RawPropertyGroup("Dispositivos de audio (salida)", audio.outputs.mapIndexed { index, value -> "out_$index" to value }))
        add(RawPropertyGroup("USB", usb.entries.sortedBy { it.key }.map { it.key to it.value }))
        add(
            RawPropertyGroup(
                "sysfs: power_supply / regulator",
                voltageRails.flatMap { rail ->
                    rail.raw.entries.map { "${rail.name}/${it.key}" to it.value }
                },
            ),
        )
        add(
            RawPropertyGroup(
                "Zonas térmicas",
                thermalZones.map { it.name to "${it.type ?: "?"} · ${it.temperatureC ?: "?"} °C" },
            ),
        )
        add(RawPropertyGroup("Frecuencias de CPU", cpuFrequencies))
        add(
            RawPropertyGroup(
                "WindowManager / dumpsys",
                listOfNotNull(
                    "wm size" to (wmSize?.trim() ?: "No disponible"),
                    "wm density" to (wmDensity?.toString() ?: "No disponible"),
                    "dumpsys battery" to (dumpsysBattery?.trim()?.lineSequence()?.take(20)?.joinToString(" | ") ?: "No disponible"),
                ),
            ),
        )
    }

    private fun textGroup(name: String, text: String): RawPropertyGroup =
        RawPropertyGroup(name, text.lineSequence().filter(String::isNotBlank).mapIndexed { index, line -> "línea_$index" to line }.toList())

    // ---------------------------------------------------------------------
    // Construcción de secciones
    // ---------------------------------------------------------------------

    private fun buildSections(data: ProbeData): List<DiagnosticSection> {
        val findings = data.findings
        val battery = data.battery
        val batteryTempWarning = (battery.temperatureC ?: 0.0) > 45.0
        val batteryHealthWarning = (battery.chargeFullMah ?: 0.0) > 0.0 &&
            (battery.chargeFullDesignMah ?: 0.0) > 0.0 &&
            battery.chargeFullMah!! / battery.chargeFullDesignMah!! < 0.7
        val authenticWarning = battery.authentic == "0"

        val sections = mutableListOf<DiagnosticSection>()
        sections += section(
            id = DiagnosticSectionIds.IDENTITY,
            title = "Identidad y antifalsificación",
            summary = "Contraste de Build API, getprop, build.prop, /proc, WindowManager y sysfs.",
            icon = "identity",
            metrics = identityMetrics(data),
            findings = findings,
            note = "Detectar contradicciones entre fuentes independientes es la forma más fiable de encontrar un clon sin root. Un dispositivo con root y hooks en el kernel puede falsear todas las fuentes: ningún informe de usuario puede garantizar autenticidad absoluta.",
        )
        sections += section(
            id = DiagnosticSectionIds.MIC,
            title = "Micrófono",
            summary = "Dispositivos de entrada, estado de silencio y medición de nivel real.",
            icon = "mic",
            metrics = mapMetrics(data.audio.metrics) + listOf(
                DiagnosticMetric(
                    "Prueba de grabación",
                    if (data.permissions.recordAudio) "Pendiente: pulsa «Probar micrófono»" else "Requiere permiso RECORD_AUDIO",
                    status = if (data.permissions.recordAudio) DiagnosticStatus.UNKNOWN else DiagnosticStatus.WARNING,
                ),
            ),
            note = "La prueba graba 2 segundos en memoria y calcula el nivel pico/RMS. El audio no se guarda ni se envía a ningún sitio.",
            interactiveLabel = "Probar micrófono (2 s)",
        )
        sections += section(
            id = DiagnosticSectionIds.RAM,
            title = "Memoria RAM",
            summary = "ActivityManager, /proc/meminfo, heap de Dalvik y coherencia entre fuentes.",
            icon = "ram",
            metrics = mapMetrics(data.memory) + ramStatusMetrics(data),
            findings = findings.filter { it.title.contains("RAM", ignoreCase = true) || it.title.contains("Heap", ignoreCase = true) || it.title.contains("low_ram", ignoreCase = true) },
            note = "Una diferencia grande entre /proc/meminfo y ActivityManager.totalMem suele indicar valores inflados por firmware.",
        )
        sections += section(
            id = DiagnosticSectionIds.STORAGE,
            title = "Almacenamiento",
            summary = "StatFs, /proc/partitions, df, volúmenes extraíbles y prueba real de 5 MB.",
            icon = "storage",
            metrics = mapMetrics(data.storage.metrics) + listOf(
                DiagnosticMetric("Volúmenes extraíbles", data.storage.volumes.size.toString()),
            ),
            findings = findings.filter { it.title.contains("Almacenamiento", ignoreCase = true) },
            note = "La prueba de 5 MB escribe y lee un archivo temporal en el directorio privado de la app; se borra al terminar. La caché del sistema puede influir en la lectura.",
        )
        sections += section(
            id = DiagnosticSectionIds.ANTENNAS,
            title = "Antenas y transmisores",
            summary = "Radios declaradas, telefonía, señal celular, WiFi, Bluetooth, NFC y GPS.",
            icon = "antennas",
            metrics = mapMetrics(data.radios) + antennaStatusMetrics(data),
            note = "No se escanean redes, no se leen coordenadas, no se consulta ICCID/IMSI ni número telefónico. El SSID solo se muestra en Android 13+ con Dispositivos cercanos.",
        )
        sections += section(
            id = DiagnosticSectionIds.SCREEN,
            title = "Pantalla",
            summary = "Resolución, densidad, refresco, HDR, brillo y prueba de píxeles.",
            icon = "screen",
            metrics = mapMetrics(data.display) + listOf(
                DiagnosticMetric("Prueba de píxeles", "Pendiente: pulsa «Probar pantalla»", status = DiagnosticStatus.UNKNOWN),
            ),
            findings = findings.filter { it.title.contains("Resolución", ignoreCase = true) || it.title.contains("Densidad", ignoreCase = true) },
            note = "La prueba muestra seis colores a pantalla completa para detectar píxeles muertos, manchas y líneas.",
            interactiveLabel = "Probar pantalla",
        )
        sections += section(
            id = DiagnosticSectionIds.TOUCH,
            title = "Táctil",
            summary = "Capacidades multitouch, dispositivos de entrada y cobertura real del panel.",
            icon = "touch",
            metrics = mapMetrics(data.input.metrics) + listOf(
                DiagnosticMetric("Cobertura táctil", "Pendiente: pulsa «Probar táctil»", status = DiagnosticStatus.UNKNOWN),
            ),
            note = "Desliza uno o varios dedos por toda la cuadrícula: se cuentan las zonas que responden y el máximo de punteros simultáneos.",
            interactiveLabel = "Probar táctil",
        )
        sections += section(
            id = DiagnosticSectionIds.SPEAKERS,
            title = "Bocinas",
            summary = "Dispositivos de salida, volúmenes por stream y tono estéreo izquierdo/derecho.",
            icon = "speakers",
            metrics = mapMetrics(data.audio.metrics) + listOf(
                DiagnosticMetric("Prueba de bocinas", "Pendiente: pulsa «Probar bocinas»", status = DiagnosticStatus.UNKNOWN),
            ),
            note = "Se genera un tono de 440 Hz en el canal izquierdo y 660 Hz en el derecho con AudioTrack. Sube el volumen antes de probar.",
            interactiveLabel = "Probar bocinas",
        )
        sections += section(
            id = DiagnosticSectionIds.PLUGS,
            title = "Plugs y conectores",
            summary = "Conector de auriculares, USB, accesorios y fuente de carga.",
            icon = "plugs",
            metrics = mapMetrics(data.plugs) + mapMetrics(data.usb),
            note = "UsbManager.getDeviceList() puede devolver una lista vacía aunque el cable tenga datos: el teléfono suele actuar como dispositivo, no como host.",
        )
        sections += section(
            id = DiagnosticSectionIds.BATTERY,
            title = "Batería",
            summary = "Nivel, salud, temperatura, corriente, contador de carga, ciclos y desgaste.",
            icon = "battery",
            metrics = batteryMetrics(battery, data),
            findings = buildList {
                if (batteryTempWarning) add(AuditFinding(DiagnosticStatus.WARNING, "Temperatura elevada", "Batería a ${battery.temperatureC?.fmt(1) ?: "?"} °C."))
                if (batteryHealthWarning) add(AuditFinding(DiagnosticStatus.WARNING, "Capacidad reducida", "La carga máxima actual está por debajo del 70 % del diseño."))
                if (authenticWarning) add(AuditFinding(DiagnosticStatus.ERROR, "Batería no auténtica", "El nodo sysfs «authentic» informa 0: posible batería de reemplazo no original."))
            },
            note = "El porcentaje de desgaste solo es válido si el fabricante expone charge_full/charge_full_design. Sin root no se puede leer la capacidad química real de la celda.",
        )
        sections += section(
            id = DiagnosticSectionIds.CABLE,
            title = "Cable y cargador",
            summary = "Medición guiada de 10 s: voltaje, corriente, potencia y temperatura.",
            icon = "cable",
            metrics = cableMetrics(battery, data),
            note = "Android expone valores de la batería, no la salida real del adaptador USB. Los vatios son una estimación orientativa, no una certificación.",
            interactiveLabel = "Medir carga 10 s",
        )
        sections += section(
            id = DiagnosticSectionIds.NETWORK,
            title = "Diagnóstico de red (Datos / WiFi / Bluetooth)",
            summary = "Conectividad activa, capacidades, ancho de banda, estado de radios y permisos.",
            icon = "network",
            metrics = mapMetrics(data.network) + networkStatusMetrics(data),
            note = "Solo se consultan estados del sistema. La app no tiene permiso INTERNET, no hace ping, no escanea redes y no mide velocidad consumiendo datos.",
        )
        sections += section(
            id = DiagnosticSectionIds.VOLTAGE,
            title = "Mapa de voltajes internos",
            summary = "power_supply, reguladores, zonas térmicas y frecuencias de CPU disponibles en sysfs.",
            icon = "voltage",
            metrics = voltageMetrics(data),
            note = "Sin root, SELinux suele bloquear los rieles del PMIC y del SoC; se publican los nodos legibles (batería, cargador, BMS, reguladores y sensores térmicos) más los valores de BatteryManager. No es un mapa eléctrico completo del chipset.",
            interactiveLabel = "Actualizar mapa",
        )
        sections += section(
            id = DiagnosticSectionIds.PROPERTIES,
            title = "Todas las propiedades del equipo",
            summary = "Volcado crudo de getprop, build.prop, /proc, sysfs, sensores, cámaras y servicios.",
            icon = "properties",
            metrics = listOf(
                DiagnosticMetric("Grupos de propiedades", data.propertyGroups.size.toString()),
                DiagnosticMetric("Entradas totales", data.propertyGroups.sumOf { it.entries.size }.toString()),
            ),
            note = "Usa el buscador para filtrar por clave. Puedes exportar el volcado completo a texto o JSON con el botón «Compartir informe».",
        )
        return sections
    }

    private fun section(
        id: String,
        title: String,
        summary: String,
        icon: String,
        metrics: List<DiagnosticMetric>,
        findings: List<AuditFinding> = emptyList(),
        note: String,
        interactiveLabel: String? = null,
    ): DiagnosticSection = DiagnosticSection(
        id = id,
        title = title,
        summary = summary,
        icon = icon,
        metrics = metrics,
        findings = findings,
        status = sectionStatus(metrics, findings),
        note = note,
        interactiveLabel = interactiveLabel,
    )

    private fun sectionStatus(metrics: List<DiagnosticMetric>, findings: List<AuditFinding>): DiagnosticStatus = when {
        findings.any { it.severity == DiagnosticStatus.ERROR } || metrics.any { it.status == DiagnosticStatus.ERROR } -> DiagnosticStatus.ERROR
        findings.any { it.severity == DiagnosticStatus.WARNING } || metrics.any { it.status == DiagnosticStatus.WARNING } -> DiagnosticStatus.WARNING
        else -> DiagnosticStatus.OK
    }

    private fun mapMetrics(map: Map<String, String>): List<DiagnosticMetric> =
        map.entries.map { DiagnosticMetric(it.key, it.value) }

    private fun identityMetrics(data: ProbeData): List<DiagnosticMetric> {
        val preferred = listOf(
            "MANUFACTURER" to "Fabricante",
            "BRAND" to "Marca",
            "MODEL" to "Modelo",
            "DEVICE" to "Dispositivo",
            "PRODUCT" to "Producto",
            "BOARD" to "Placa",
            "HARDWARE" to "Hardware",
            "BOOTLOADER" to "Bootloader",
            "FINGERPRINT" to "Huella de compilación",
            "RELEASE" to "Android",
            "SDK" to "Nivel API",
            "SECURITY_PATCH" to "Parche de seguridad",
            "SUPPORTED_ABIS" to "ABIs soportadas",
            "RADIO_VERSION" to "Versión de radio/baseband",
            "TYPE" to "Tipo de build",
            "TAGS" to "Tags de build",
            "SERIAL" to "Serie (Build.getSerial)",
        )
        val metrics = preferred.mapNotNull { (key, label) ->
            data.buildMap[key]?.let { DiagnosticMetric(label, it, "Fuente: Build API · getprop=${data.props[keyToProp(key)] ?: "no declarado"}") }
        }.toMutableList()
        metrics += DiagnosticMetric("Propiedades getprop leídas", data.props.size.toString())
        metrics += DiagnosticMetric("Fuentes independientes", "Build API, getprop, build.prop, /proc/cpuinfo, /proc/meminfo, /proc/partitions, wm, sysfs")
        return metrics
    }

    private fun keyToProp(key: String): String = when (key) {
        "MANUFACTURER" -> "ro.product.manufacturer"
        "BRAND" -> "ro.product.brand"
        "MODEL" -> "ro.product.model"
        "DEVICE" -> "ro.product.device"
        "PRODUCT" -> "ro.product.name"
        "BOARD" -> "ro.board.platform"
        "HARDWARE" -> "ro.hardware"
        "BOOTLOADER" -> "ro.boot.bootloader"
        "FINGERPRINT" -> "ro.build.fingerprint"
        "RELEASE" -> "ro.build.version.release"
        "SDK" -> "ro.build.version.sdk"
        "SECURITY_PATCH" -> "ro.build.version.security_patch"
        "SUPPORTED_ABIS" -> "ro.product.cpu.abilist"
        "TYPE" -> "ro.build.type"
        "TAGS" -> "ro.build.tags"
        else -> key
    }

    private fun ramStatusMetrics(data: ProbeData): List<DiagnosticMetric> {
        val total = data.memory["RAM total (ActivityManager)"] ?: return emptyList()
        val proc = data.memory["RAM total (/proc/meminfo)"] ?: return emptyList()
        return listOf(DiagnosticMetric("Contraste de fuentes", "$total vs $proc"))
    }

    private fun antennaStatusMetrics(data: ProbeData): List<DiagnosticMetric> {
        val signal = data.radios["Señal dBm"] ?: return emptyList()
        val dbm = Regex("""-?\d+""").find(signal)?.value?.toIntOrNull()
        return if (dbm != null && dbm < -105) {
            listOf(DiagnosticMetric("Calidad de señal celular", "Débil ($dbm dBm)", status = DiagnosticStatus.WARNING))
        } else {
            emptyList()
        }
    }

    private fun networkStatusMetrics(data: ProbeData): List<DiagnosticMetric> {
        val validated = data.network["Conectividad validada"]
        val rssi = data.network["WiFi RSSI"] ?: return emptyList()
        val value = Regex("""-?\d+""").find(rssi)?.value?.toIntOrNull()
        val result = mutableListOf<DiagnosticMetric>()
        if (validated == "No") {
            result += DiagnosticMetric("Internet validado", "No", "El sistema no marca la red activa como validada.", DiagnosticStatus.WARNING)
        }
        if (value != null && value < -80) {
            result += DiagnosticMetric("Intensidad WiFi", "Débil ($value dBm)", status = DiagnosticStatus.WARNING)
        }
        return result
    }

    private fun batteryMetrics(battery: BatteryProbe, data: ProbeData): List<DiagnosticMetric> {
        val health = if (battery.chargeFullMah != null && battery.chargeFullDesignMah != null && battery.chargeFullDesignMah!! > 0) {
            ((battery.chargeFullMah!! / battery.chargeFullDesignMah!!) * 100).fmt(0) + " %"
        } else {
            "No disponible"
        }
        return listOf(
            DiagnosticMetric("Nivel", if (battery.percent >= 0) "${battery.percent} %" else "No disponible"),
            DiagnosticMetric("Estado", battery.statusLabel),
            DiagnosticMetric("Salud (BatteryManager)", battery.healthLabel),
            DiagnosticMetric("Salud sysfs", battery.sysHealth ?: "No disponible"),
            DiagnosticMetric("Tecnología", battery.technology ?: "No disponible"),
            DiagnosticMetric("Voltaje", if (battery.voltageMv > 0) "${battery.voltageMv} mV (${(battery.voltageMv / 1000.0).fmt(2)} V)" else "No disponible"),
            DiagnosticMetric("Temperatura", battery.temperatureC?.let { "${it.fmt(1)} °C" } ?: "No disponible"),
            DiagnosticMetric("Corriente instantánea", battery.currentMa?.let { "${it.fmt(1)} mA" } ?: "No disponible"),
            DiagnosticMetric("Corriente media", battery.avgCurrentMa?.let { "${it.fmt(1)} mA" } ?: "No disponible"),
            DiagnosticMetric("Contador de carga", battery.chargeCounterUah?.let { "$it µAh" } ?: "No disponible"),
            DiagnosticMetric("Capacidad (BATTERY_PROPERTY_CAPACITY)", battery.capacityPercent?.let { "$it %" } ?: "No disponible"),
            DiagnosticMetric("Capacidad sysfs", battery.sysCapacity?.let { "$it %" } ?: "No disponible"),
            DiagnosticMetric("Energía", battery.energyNwh?.let { "$it nWh" } ?: "No disponible"),
            DiagnosticMetric("Tiempo restante de carga", battery.chargeTimeRemainingMinutes?.let { "${it.fmt(0)} min" } ?: "No disponible"),
            DiagnosticMetric("Carga máxima actual", battery.chargeFullMah?.let { "${it.fmt(0)} mAh" } ?: "No disponible"),
            DiagnosticMetric("Capacidad de diseño", battery.chargeFullDesignMah?.let { "${it.fmt(0)} mAh" } ?: "No disponible"),
            DiagnosticMetric("Salud estimada", health),
            DiagnosticMetric("Ciclos", battery.cycleCount?.toString() ?: "No disponible"),
            DiagnosticMetric("Autenticidad sysfs", battery.authentic ?: "No disponible"),
            DiagnosticMetric("Batería presente", yesNo(battery.present)),
            DiagnosticMetric("Nivel bajo", yesNo(battery.low)),
        )
    }

    private fun cableMetrics(battery: BatteryProbe, data: ProbeData): List<DiagnosticMetric> = listOf(
        DiagnosticMetric("Fuente conectada", battery.pluggedLabel),
        DiagnosticMetric("Estado de batería", battery.statusLabel),
        DiagnosticMetric("Corriente instantánea", battery.currentMa?.let { "${it.fmt(1)} mA" } ?: "No disponible"),
        DiagnosticMetric("Voltaje instantáneo", if (battery.voltageMv > 0) "${battery.voltageMv} mV" else "No disponible"),
        DiagnosticMetric("Potencia instantánea estimada", if (battery.voltageMv > 0 && battery.currentMa != null) ((battery.voltageMv / 1000.0) * (battery.currentMa!! / 1000.0)).fmt(2) + " W" else "No disponible"),
        DiagnosticMetric("Dispositivos USB", data.usb["Dispositivos USB"] ?: "No disponible"),
        DiagnosticMetric("Medición de 10 s", "Pendiente: pulsa «Medir carga 10 s»", status = DiagnosticStatus.UNKNOWN),
    )

    private fun voltageMetrics(data: ProbeData): List<DiagnosticMetric> {
        val rails = data.voltageRails
        val withVoltage = rails.count { it.voltageVolts != null }
        val withCurrent = rails.count { it.currentAmps != null }
        val sysfsRails = rails.count { it.name.startsWith("power_supply/") }
        val inaccessible = data.cpuFrequencies.count { it.second.contains("sin acceso") }
        return listOf(
            DiagnosticMetric("Rieles enumerados", rails.size.toString()),
            DiagnosticMetric("Con voltaje legible", withVoltage.toString()),
            DiagnosticMetric("Con corriente legible", withCurrent.toString()),
            DiagnosticMetric("power_supply accesibles", sysfsRails.toString()),
            DiagnosticMetric("Reguladores", rails.count { it.name.startsWith("regulator/") }.toString()),
            DiagnosticMetric("Zonas térmicas", data.thermalZones.size.toString()),
            DiagnosticMetric("Núcleos CPU leídos", data.cpuFrequencies.size.toString()),
            DiagnosticMetric("Núcleos con SELinux bloqueado", inaccessible.toString()),
            DiagnosticMetric("Rango de voltajes", rails.mapNotNull { it.voltageVolts }.let { values ->
                if (values.isEmpty()) "No disponible" else "${values.minOrNull()?.fmt(3) ?: "?"} V – ${values.maxOrNull()?.fmt(3) ?: "?"} V"
            }),
        )
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    private fun buildMapOf(vararg pairs: Pair<String, String>): Map<String, String> =
        pairs.toMap()

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "${value.fmt(2)} ${units[index]}"
    }

    private fun yesNo(value: Boolean): String = if (value) "Sí" else "No"

    private fun Double.fmt(decimals: Int): String {
        val factor = when (decimals) {
            0 -> 1.0
            1 -> 10.0
            2 -> 100.0
            else -> 1000.0
        }
        return (round(this * factor) / factor).toString()
    }
}
