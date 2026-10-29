package com.devicescope.app.pcmovil.data

import android.content.Context
import com.devicescope.app.pcmovil.link.MiniJson

/**
 * Persistencia local de equipos emparejados y ajustes del módulo.
 *
 * Se usa `SharedPreferences` a propósito: el módulo no tiene base de datos ni
 * cuentas. El token de emparejamiento se guarda en el almacenamiento privado de
 * la app y se puede revocar desde el agente o borrando el equipo aquí.
 */
class PcDeviceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pc_movil", Context.MODE_PRIVATE)

    fun devices(): List<PcDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        val parsed = runCatching { MiniJson.parse(raw) as? List<*> }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { item ->
            val map = (item as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            map?.let { PcDevice.fromJsonMap(it) }
        }
    }

    fun device(id: String): PcDevice? = devices().firstOrNull { it.id == id }

    fun upsert(device: PcDevice) {
        val current = devices().filterNot { it.id == device.id || it.endpoint == device.endpoint }
        save(current + device)
    }

    fun updateToken(id: String, token: String, info: PcAgentInfo) {
        val existing = device(id)
        val base = existing ?: PcDevice(
            id = id,
            name = info.name,
            address = info.endpoint.substringBeforeLast(':'),
            port = info.endpoint.substringAfterLast(':', "8787").toIntOrNull() ?: 8787,
        )
        upsert(
            base.copy(
                token = token,
                name = info.name.ifBlank { base.name },
                platform = info.platform,
                agentVersion = info.version,
                lastSeenMillis = System.currentTimeMillis(),
            ),
        )
    }

    fun touch(id: String) {
        val existing = device(id) ?: return
        upsert(existing.copy(lastSeenMillis = System.currentTimeMillis()))
    }

    fun remove(id: String) {
        save(devices().filterNot { it.id == id })
    }

    fun clear() = prefs.edit().remove(KEY_DEVICES).apply()

    private fun save(devices: List<PcDevice>) {
        prefs.edit().putString(KEY_DEVICES, MiniJson.encode(devices.map { it.toJsonMap() })).apply()
    }

    /** Preferencias de las herramientas (persisten entre sesiones). */
    var mirrorQuality: Int
        get() = prefs.getInt(KEY_MIRROR_QUALITY, 55)
        set(value) = prefs.edit().putInt(KEY_MIRROR_QUALITY, value.coerceIn(15, 90)).apply()

    var mirrorFps: Int
        get() = prefs.getInt(KEY_MIRROR_FPS, 8)
        set(value) = prefs.edit().putInt(KEY_MIRROR_FPS, value.coerceIn(1, 20)).apply()

    var metricsIntervalMillis: Int
        get() = prefs.getInt(KEY_METRICS_INTERVAL, 1000)
        set(value) = prefs.edit().putInt(KEY_METRICS_INTERVAL, value.coerceIn(500, 5000)).apply()

    var invertScroll: Boolean
        get() = prefs.getBoolean(KEY_INVERT_SCROLL, false)
        set(value) = prefs.edit().putBoolean(KEY_INVERT_SCROLL, value).apply()

    var pointerSpeed: Float
        get() = prefs.getFloat(KEY_POINTER_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_POINTER_SPEED, value.coerceIn(0.25f, 3f)).apply()

    var vpnAllowedNetworks: Set<String>
        get() = prefs.getStringSet(KEY_VPN_NETWORKS, DEFAULT_VPN_NETWORKS) ?: DEFAULT_VPN_NETWORKS
        set(value) = prefs.edit().putStringSet(KEY_VPN_NETWORKS, value).apply()

    var autoConnectId: String?
        get() = prefs.getString(KEY_AUTO_CONNECT, null)
        set(value) = prefs.edit().putString(KEY_AUTO_CONNECT, value).apply()

    var transferDirectory: String?
        get() = prefs.getString(KEY_TRANSFER_DIR, null)
        set(value) = prefs.edit().putString(KEY_TRANSFER_DIR, value).apply()

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_MIRROR_QUALITY = "mirror_quality"
        private const val KEY_MIRROR_FPS = "mirror_fps"
        private const val KEY_METRICS_INTERVAL = "metrics_interval"
        private const val KEY_INVERT_SCROLL = "invert_scroll"
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_VPN_NETWORKS = "vpn_networks"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_TRANSFER_DIR = "transfer_dir"

        val DEFAULT_VPN_NETWORKS = setOf("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")
    }
}
