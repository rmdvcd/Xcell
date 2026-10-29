package com.devicescope.app.pcmovil.link

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.devicescope.app.pcmovil.data.PcDeviceStore
import com.devicescope.app.pcmovil.data.VpnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * VPN del teléfono hacia la red de la PC.
 *
 * El túnel es **punto a punto**: el teléfono se conecta al relay del agente y
 * enruta hacia él solo las redes privadas que el usuario marque. No es una VPN
 * de salida a Internet ni hay proveedor externo; si el usuario añade
 * `0.0.0.0/0`, todo el tráfico del teléfono saldrá por la PC (útil en una red
 * de confianza, y con la advertencia correspondiente en la UI).
 *
 * No se usa `startForeground()`: el sistema mantiene el servicio mientras el
 * `tun` esté establecido y muestra su propio indicador de VPN. Así se evita
 * depender del tipo de servicio en primer plano de Android 14+.
 */
class PcVpnService : VpnService() {

    private var engine: PcVpnEngine? = null
    private var tun: ParcelFileDescriptor? = null
    private var relay: Socket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel(null)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (host.isNullOrBlank() || port <= 0) {
                    publish(VpnState.Error("Falta el relay de la PC"))
                } else {
                    startTunnel(host, port)
                }
                return START_NOT_STICKY
            }
        }
        if (engine == null) publish(VpnState.Off)
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String, port: Int) {
        stopTunnel(null)
        publish(VpnState.Preparing("Negociando túnel con $host"))
        val store = PcDeviceStore(this)
        val routes = store.vpnAllowedNetworks.toList().ifEmpty { PcDeviceStore.DEFAULT_VPN_NETWORKS.toList() }

        var socket: Socket? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                protect(this)
                connect(InetSocketAddress(host, port), 10_000)
                soTimeout = 0
            }

            val builder = Builder()
                .setSession("DeviceScope · PC-Móvil")
                .setMtu(1400)
                .addAddress(PHONE_ADDRESS, 32)
                .addRoute(PHONE_ADDRESS, 32)
                .allowFamily(android.system.OsConstants.AF_INET)
            var added = 0
            routes.forEach { route ->
                val trimmed = route.trim()
                if (trimmed.isEmpty()) return@forEach
                val address = trimmed.substringBefore('/')
                val prefix = trimmed.substringAfter('/', "32").toIntOrNull() ?: 32
                if (address.isEmpty()) return@forEach
                runCatching {
                    builder.addRoute(address, prefix.coerceIn(0, 32))
                    added++
                }
            }
            if (added == 0) {
                builder.addRoute("192.168.0.0", 16)
            }
            descriptor = builder.establish()
            if (descriptor == null) {
                socket.close()
                publish(VpnState.Error("El sistema no autorizó la interfaz VPN"))
                return
            }

            val localIp = IpPacketCodec.parseIp(PHONE_ADDRESS) ?: 0
            val newEngine = PcVpnEngine(descriptor, socket, localIp, log = { line ->
                _log.value = (_log.value + line).takeLast(30)
            })
            engine = newEngine
            tun = descriptor
            relay = socket
            newEngine.start()
            startStatsLoop(newEngine)
            publish(
                VpnState.On(
                    endpoint = "$host:$port",
                    host = host,
                    sinceMillis = System.currentTimeMillis(),
                    routes = maxOf(added, 1),
                ),
            )
            _log.value = emptyList()
        } catch (error: Exception) {
            runCatching { socket?.close() }
            runCatching { descriptor?.close() }
            publish(VpnState.Error(error.localizedMessage ?: "No se pudo crear el túnel"))
        }
    }

    private fun startStatsLoop(engine: PcVpnEngine) {
        Thread({
            while (this.engine === engine) {
                publishStats(
                    received = engine.receivedPackets,
                    sent = engine.sentPackets,
                    dropped = engine.droppedPackets,
                    flows = engine.openFlows,
                )
                try {
                    Thread.sleep(2_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "pc-vpn-stats").apply { isDaemon = true }.start()
    }

    private fun stopTunnel(reason: String?, isError: Boolean = false) {
        val hadEngine = engine != null
        engine?.stop()
        engine = null
        runCatching { relay?.close() }
        relay = null
        runCatching { tun?.close() }
        tun = null
        if (reason != null && isError) {
            publish(VpnState.Error(reason))
        } else if (hadEngine || _state.value is VpnState.On) {
            publish(VpnState.Off)
        }
        stopSelf()
    }

    private fun publish(state: VpnState) {
        _state.value = state
    }

    override fun onRevoke() {
        stopTunnel("El sistema revocó el permiso de VPN", isError = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        runCatching { relay?.close() }
        runCatching { tun?.close() }
        runCatching { PcLinkManager.peek()?.closeVpn() }
        if (_state.value !is VpnState.Error) _state.value = VpnState.Off
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.devicescope.app.pcmovil.VPN_START"
        const val ACTION_STOP = "com.devicescope.app.pcmovil.VPN_STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        const val PHONE_ADDRESS = "10.111.0.2"

        private val _state = MutableStateFlow<VpnState>(VpnState.Off)
        val state: StateFlow<VpnState> = _state.asStateFlow()

        private val _log = MutableStateFlow<List<String>>(emptyList())
        val log: StateFlow<List<String>> = _log.asStateFlow()

        private val _stats = MutableStateFlow(VpnStats())
        val stats: StateFlow<VpnStats> = _stats.asStateFlow()

        fun requestStart(context: Context, host: String, port: Int) {
            val intent = Intent(context, PcVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            context.startService(intent)
        }

        fun requestStop(context: Context) {
            val intent = Intent(context, PcVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** El motor publica contadores cada pocos segundos desde la UI. */
        fun publishStats(received: Long, sent: Long, dropped: Long, flows: Int) {
            _stats.value = VpnStats(received, sent, dropped, flows)
        }

        fun resetLog() {
            _log.value = emptyList()
        }
    }
}

/** Contadores del túnel que la UI muestra mientras está activo. */
data class VpnStats(
    val packetsIn: Long = 0,
    val packetsOut: Long = 0,
    val dropped: Long = 0,
    val flows: Int = 0,
) {
    val throughputLabel: String
        get() = "${packetsIn + packetsOut} paquetes · ${flows} flujos"

    fun kbps(bits: Long): Int = (bits / 1024.0).roundToInt()
}
