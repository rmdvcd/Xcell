package com.devicescope.app.pcmovil.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.devicescope.app.pcmovil.data.ScanHit
import com.devicescope.app.pcmovil.data.ScanKind
import com.devicescope.app.pcmovil.data.ScanRadio
import com.devicescope.app.pcmovil.data.ScanReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * A-D Scanner: barrido de radiofrecuencia con los sensores del teléfono.
 *
 * Qué mide de verdad y qué no:
 * - **WiFi**: puntos de acceso visibles por la radio WiFi del teléfono (≈ 40 m
 *   en interiores, más en campo abierto), con canal, seguridad y RSSI. En
 *   Android 13+ el escaneo no necesita ubicación (`NEARBY_WIFI_DEVICES` con
 *   `neverForLocation`); en versiones anteriores sí, y por eso el permiso se
 *   pide solo cuando el usuario enciende el barrido.
 * - **Bluetooth LE**: anuncios BLE con dirección, nombre y RSSI. Alcance real
 *   ≈ 10–30 m según potencia del emisor.
 * - **Bluetooth clásico**: solo dispositivos ya emparejados; el descubrimiento
 *   clásico tarda 12 s por vuelta y bloquea la radio, así que no se usa.
 * - **Telefonía**: celdas registradas/vecinas (`getAllCellInfo`), que exigen
 *   permiso de ubicación. Nunca se leen coordenadas.
 * - **LAN extendida**: el agente de la PC barre su propia subred y devuelve los
 *   hosts; esto amplía el alcance más allá de lo que ve el teléfono.
 * - **NFC/GPS/sensores**: solo inventario de presencia; NFC no "escucha" sin
 *   una tarjeta delante, y las coordenadas GPS deliberadamente no se leen.
 *
 * No se identifica a personas: solo se listan emisores y su intensidad. El
 * resultado se queda en el teléfono salvo que el usuario lo guarde.
 */
class DataScanner(private val context: Context) {

    /** Inventario de radios, para pintar la pantalla antes de escanear. */
    data class RadioInventory(
        val wifi: Boolean,
        val wifiEnabled: Boolean,
        val wifiDirect: Boolean,
        val wifiAware: Boolean,
        val ble: Boolean,
        val bluetoothEnabled: Boolean,
        val classic: Boolean,
        val nfc: Boolean,
        val cellular: Boolean,
        val gps: Boolean,
        val uwb: Boolean,
        val infrared: Boolean,
        val fmRadio: Boolean,
        val sensorCount: Int,
        val wifiPermission: Boolean,
        val locationPermission: Boolean,
        val bluetoothScanPermission: Boolean,
    ) {
        val missingPermissions: List<String>
            get() = buildList {
                if (!wifiPermission) add("Dispositivos cercanos (WiFi)")
                if (!bluetoothScanPermission) add("Dispositivos cercanos (Bluetooth)")
                if (!locationPermission) add("Ubicación aproximada (solo WiFi/celdas en Android ≤12)")
            }
    }

    fun inventory(): RadioInventory {
        val pm = context.packageManager
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getSensorList(Sensor.TYPE_ALL)
            ?.size ?: 0
        return RadioInventory(
            wifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            wifiDirect = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            wifiAware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            bluetoothEnabled = adapter?.isEnabled == true,
            classic = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC),
            cellular = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            gps = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
            uwb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                pm.hasSystemFeature(PackageManager.FEATURE_UWB),
            infrared = pm.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR),
            fmRadio = pm.hasSystemFeature("android.hardware.fm"),
            sensorCount = sensors,
            wifiPermission = hasPermission(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.NEARBY_WIFI_DEVICES
                } else {
                    Manifest.permission.ACCESS_FINE_LOCATION
                },
            ),
            locationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            bluetoothScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            },
        )
    }

    /** Permisos que hay que pedir para un barrido completo en este equipo. */
    fun requiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.filterNot { hasPermission(it) }
    }

    /**
     * Barrido completo. [durationMillis] es la ventana de escucha BLE; WiFi se
     * lee de la caché del sistema tras pedir un escaneo.
     */
    suspend fun scan(durationMillis: Long = 8_000L): ScanReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val inventory = inventory()
        val hits = LinkedHashMap<String, ScanHit>()
        val notes = mutableListOf<String>()
        val perRadio = linkedMapOf<ScanRadio, Int>()

        fun add(hit: ScanHit) {
            val key = "${hit.radio}:${hit.address}"
            val previous = hits[key]
            hits[key] = if (previous == null) {
                hit
            } else {
                previous.copy(
                    rssi = hit.rssi ?: previous.rssi,
                    lastSeenMillis = hit.lastSeenMillis,
                    occurrences = previous.occurrences + 1,
                    name = if (hit.name.isNotBlank() && hit.name != "oculto") hit.name else previous.name,
                )
            }
        }

        // ---------------------------------------------------------------- WiFi
        if (inventory.wifi) {
            val wifiHits = scanWifi(inventory)
            wifiHits.forEach(::add)
            perRadio[ScanRadio.WIFI] = wifiHits.size
            if (wifiHits.isEmpty()) {
                notes += "WiFi: sin resultados. En Android 13+ hace falta «Dispositivos cercanos»; " +
                    "en versiones anteriores también la ubicación del sistema y el WiFi encendido."
            }
            if (!inventory.wifiEnabled) notes += "El WiFi está apagado."
        }

        // ------------------------------------------------------------ Bluetooth
        if (inventory.ble && inventory.bluetoothEnabled) {
            val bleHits = scanBle(minOf(durationMillis, 10_000L))
            bleHits.forEach(::add)
            perRadio[ScanRadio.BLUETOOTH] = bleHits.size
            if (bleHits.isEmpty()) {
                notes += "Bluetooth: sin anuncios en la ventana de escucha. " +
                    "El alcance útil de BLE ronda 10–30 m."
            }
        } else if (inventory.ble) {
            notes += "Bluetooth está apagado: no se pueden ver anuncios BLE."
        }

        if (inventory.classic) {
            scanClassic().forEach(::add)
        }

        // ------------------------------------------------------------- Celular
        if (inventory.cellular) {
            val cellHits = scanCell()
            cellHits.forEach(::add)
            perRadio[ScanRadio.CELLULAR] = cellHits.size
            if (cellHits.isEmpty()) {
                notes += "Telefonía: no se pudo leer celdas. Requiere permiso de ubicación y " +
                    "que el radio esté registrado en una red."
            }
        }

        // ---------------------------------------------- Sensores y otros radios
        sensorInventoryHits(inventory).forEach(::add)
        perRadio[ScanRadio.SENSOR] = minOf(inventory.sensorCount, 12)

        if (inventory.nfc) {
            add(
                ScanHit(
                    radio = ScanRadio.NFC,
                    kind = ScanKind.SENSOR,
                    name = "Lector NFC",
                    address = "nfc0",
                    detail = "NFC pasivo: solo detecta etiquetas a menos de 4 cm. No barre el entorno.",
                ),
            )
        }
        if (inventory.uwb) {
            notes += "Este teléfono tiene UWB, pero Android no expone un escaneo UWB a terceros."
        }
        if (inventory.infrared) {
            notes += "Hay emisor infrarrojo: es de salida, no un sensor de escucha."
        }

        ScanReport(
            startedAtMillis = startedAt,
            durationMillis = System.currentTimeMillis() - startedAt,
            hits = hits.values.sortedWith(
                compareByDescending<ScanHit> { it.rssi ?: -127 }.thenBy { it.radio.ordinal },
            ),
            perRadio = perRadio,
            notes = notes,
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun scanWifi(inventory: RadioInventory): List<ScanHit> {
        if (!inventory.wifiPermission) return emptyList()
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        if (!manager.isWifiEnabled) return emptyList()
        // Android 12 y anteriores exigen ubicación activa para devolver resultados.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            if (!enabled) return emptyList()
        }
        val started = runCatching { manager.startScan() }.getOrDefault(false)
        if (started) delay(1_500)
        val results = runCatching { manager.scanResults }.getOrElse { emptyList() }
        val seen = LinkedHashMap<String, ScanHit>()
        results.forEach { result ->
            val ssid = result.SSID?.takeIf { it.isNotBlank() } ?: "oculto"
            val bssid = result.BSSID ?: return@forEach
            val capabilities = result.capabilities.orEmpty()
            val security = when {
                capabilities.contains("EAP") -> "WPA-Enterprise"
                capabilities.contains("WPA3") -> "WPA3"
                capabilities.contains("WPA2") -> "WPA2"
                capabilities.contains("WPA") -> "WPA"
                capabilities.contains("WEP") -> "WEP"
                capabilities.contains("ESS") || capabilities.isBlank() -> "abierta"
                else -> capabilities.take(24)
            }
            val key = bssid.lowercase()
            val hit = ScanHit(
                radio = ScanRadio.WIFI,
                kind = ScanKind.ACCESS_POINT,
                name = ssid,
                address = bssid,
                rssi = result.level,
                distanceMeters = estimateDistance(result.level, 2.0),
                detail = "$security · canal ${channelOf(result.frequency)} · ${result.frequency} MHz",
                channels = listOf(channelOf(result.frequency)),
                secure = security != "abierta",
            )
            val previous = seen[key]
            seen[key] = if (previous == null || (totalRssi(result.level) > totalRssi(previous.rssi ?: -127))) {
                hit
            } else {
                previous.copy(occurrences = previous.occurrences + 1)
            }
        }
        return seen.values.toList()
    }

    /**
     * Escaneo BLE real durante [durationMillis]. Se pide un solo callback y se
     * corta al terminar: la radio no queda encendida más tiempo del necesario.
     */
    @Suppress("MissingPermission")
    private suspend fun scanBle(durationMillis: Long): List<ScanHit> = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return@withContext emptyList()
        if (!adapter.isEnabled) return@withContext emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList()
        val found = LinkedHashMap<String, ScanHit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device?.address ?: return
                val name = runCatching { result.scanRecord?.deviceName }.getOrNull()
                    ?: runCatching { result.device?.name }.getOrNull()
                    ?: "BLE sin nombre"
                val hit = ScanHit(
                    radio = ScanRadio.BLUETOOTH,
                    kind = ScanKind.BLE_DEVICE,
                    name = name,
                    address = address,
                    rssi = result.rssi,
                    distanceMeters = estimateDistance(result.rssi, 2.0),
                    detail = buildString {
                        append("BLE")
                        result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let {
                            append(" · TX $it dBm")
                        }
                        val services = result.scanRecord?.serviceUuids?.size ?: 0
                        if (services > 0) append(" · $services servicio(s)")
                    },
                )
                val previous = found[address]
                found[address] = if (previous == null) {
                    hit
                } else {
                    hit.copy(
                        occurrences = previous.occurrences + 1,
                        lastSeenMillis = System.currentTimeMillis(),
                    )
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return@withContext try {
            scanner.startScan(null, settings, callback)
            delay(durationMillis)
            scanner.stopScan(callback)
            found.values.toList()
        } catch (error: SecurityException) {
            runCatching { scanner.stopScan(callback) }
            emptyList()
        }
    }

    @Suppress("MissingPermission")
    private fun scanClassic(): List<ScanHit> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return emptyList()
        }
        return runCatching {
            adapter.bondedDevices.orEmpty().map { device ->
                val name = runCatching { device.name }.getOrNull() ?: "emparejado"
                ScanHit(
                    radio = ScanRadio.BLUETOOTH,
                    kind = ScanKind.CLASSIC_DEVICE,
                    name = name,
                    address = device.address,
                    detail = "Bluetooth clásico emparejado · tipo ${device.bluetoothClass?.deviceClass ?: 0}",
                )
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun scanCell(): List<ScanHit> {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyList()
        val info = runCatching { telephony.allCellInfo }.getOrNull() ?: return emptyList()
        return info.mapNotNull { cell ->
            // Se evita referenciar CellInfoNr directamente: la clase no existe por
            // debajo de Android 10 y un `is` fallaría en esos equipos.
            val className = cell.javaClass.simpleName
            when {
                cell is android.telephony.CellInfoGsm -> {
                    val identity = cell.cellIdentity
                    ScanHit(
                        radio = ScanRadio.CELLULAR,
                        kind = ScanKind.CELL_TOWER,
                        name = "GSM · ${identity.operatorAlphaLong ?: "red"}",
                        address = "gsm-${identity.cid}",
                        rssi = cell.cellSignalStrength.dbm,
                        detail = "LAC ${identity.lac} · CID ${identity.cid} · ${if (cell.isRegistered) "registrada" else "vecina"}",
                    )
                }
                cell is android.telephony.CellInfoWcdma -> {
                    val identity = cell.cellIdentity
                    ScanHit(
                        radio = ScanRadio.CELLULAR,
                        kind = ScanKind.CELL_TOWER,
                        name = "UMTS · ${identity.operatorAlphaLong ?: "red"}",
                        address = "wcdma-${identity.cid}",
                        rssi = cell.cellSignalStrength.dbm,
                        detail = "LAC ${identity.lac} · CID ${identity.cid}",
                    )
                }
                cell is android.telephony.CellInfoLte -> {
                    val identity = cell.cellIdentity
                    ScanHit(
                        radio = ScanRadio.CELLULAR,
                        kind = ScanKind.CELL_TOWER,
                        name = "LTE · ${identity.operatorAlphaLong ?: "red"}",
                        address = "lte-${identity.ci}",
                        rssi = cell.cellSignalStrength.dbm,
                        detail = "TAC ${identity.tac} · CI ${identity.ci} · ${if (cell.isRegistered) "registrada" else "vecina"}",
                    )
                }
                className == "CellInfoNr" -> ScanHit(
                    radio = ScanRadio.CELLULAR,
                    kind = ScanKind.CELL_TOWER,
                    name = "5G NR · ${if (cell.isRegistered) "registrada" else "vecina"}",
                    address = "nr-${cell.hashCode()}",
                    detail = "Celda 5G detectada; el detalle de banda depende del fabricante.",
                )
                else -> null
            }
        }
    }

    private fun sensorInventoryHits(inventory: RadioInventory): List<ScanHit> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensors = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        val interesting = listOf(
            Sensor.TYPE_MAGNETIC_FIELD to "Magnetómetro (detecta metales y campos)",
            Sensor.TYPE_PROXIMITY to "Proximidad (infrarrojo de corto alcance)",
            Sensor.TYPE_LIGHT to "Luz ambiental",
            Sensor.TYPE_PRESSURE to "Barómetro (altitud)",
            Sensor.TYPE_STEP_COUNTER to "Podómetro",
            Sensor.TYPE_ACCELEROMETER to "Acelerómetro",
            Sensor.TYPE_GYROSCOPE to "Giroscopio",
        )
        val hits = mutableListOf<ScanHit>()
        interesting.forEach { (type, label) ->
            val sensor = sensors.firstOrNull { it.type == type } ?: return@forEach
            hits += ScanHit(
                radio = ScanRadio.SENSOR,
                kind = ScanKind.SENSOR,
                name = label,
                address = sensor.name,
                detail = "${sensor.vendor} · alcance ${sensor.maximumRange} · potencia ${sensor.power} mA",
            )
        }
        if (inventory.gps) {
            hits += ScanHit(
                radio = ScanRadio.GPS,
                kind = ScanKind.SENSOR,
                name = "Receptor GNSS",
                address = "gnss0",
                detail = "Solo se comprueba que existe: este módulo no lee coordenadas.",
            )
        }
        if (inventory.wifiDirect) {
            hits += ScanHit(
                radio = ScanRadio.WIFI,
                kind = ScanKind.SENSOR,
                name = "WiFi Direct",
                address = "p2p0",
                detail = "La radio admite conexión directa entre dispositivos.",
            )
        }
        if (inventory.wifiAware) {
            hits += ScanHit(
                radio = ScanRadio.WIFI,
                kind = ScanKind.SENSOR,
                name = "WiFi Aware (NAN)",
                address = "nan0",
                detail = "Anuncios NAN disponibles en este equipo.",
            )
        }
        return hits
    }

    private fun channelOf(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5160..5885 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> 0
    }

    private fun totalRssi(rssi: Int): Int = rssi + 127

    /** Modelo logarítmico de distancia: orientativo, sin calibración en sitio. */
    private fun estimateDistance(rssi: Int, pathLossExponent: Double, txPowerAtOneMeter: Int = -59): Double =
        runCatching { 10.0.pow((txPowerAtOneMeter - rssi) / (10.0 * pathLossExponent)) }.getOrDefault(0.0)

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Hosts de la LAN que el agente haya aportado, en el mismo formato. */
    fun lanHits(hosts: List<Map<String, Any?>>): List<ScanHit> = hosts.mapNotNull { host ->
        val ip = host["ip"]?.toString() ?: return@mapNotNull null
        ScanHit(
            radio = ScanRadio.LAN,
            kind = ScanKind.LAN_HOST,
            name = host["name"]?.toString()?.takeIf { it.isNotBlank() } ?: ip,
            address = ip,
            detail = buildString {
                host["mac"]?.toString()?.takeIf { it.isNotBlank() }?.let { append("MAC $it · ") }
                host["vendor"]?.toString()?.takeIf { it.isNotBlank() }?.let { append(it) }
                val ports = (host["ports"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                if (!ports.isNullOrEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("puertos ${ports.joinToString(",")}")
                }
            }.ifBlank { "Host visible desde la PC" },
            firstSeenMillis = System.currentTimeMillis(),
            lastSeenMillis = System.currentTimeMillis(),
        )
    }
}
