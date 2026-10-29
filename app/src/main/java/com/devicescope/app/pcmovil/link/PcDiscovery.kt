package com.devicescope.app.pcmovil.link

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Descubrimiento de agentes en la LAN por UDP broadcast.
 *
 * Se envía [PcProtocol.DISCOVERY_MAGIC] al puerto 8788; cada agente contesta con
 * nombre, puerto de control y huella. No se usan servicios de terceros ni DNS.
 */
class PcDiscovery(context: Context) {

    private val appContext = context.applicationContext

    suspend fun scan(timeoutMillis: Long = 2_500L): List<PcProtocol.AgentAnnouncement> =
        withContext(Dispatchers.IO) {
            val results = LinkedHashMap<String, PcProtocol.AgentAnnouncement>()
            val lock = WifiManager.MulticastLock::class.java
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = runCatching {
                wifi?.createMulticastLock("pc-movil-discovery")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(0))
                    soTimeout = 250
                }
                val payload = PcProtocol.DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
                broadcastAddresses().forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, PcProtocol.DISCOVERY_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!received) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val address = packet.address?.hostAddress ?: continue
                    val announcement = PcProtocol.decodeDiscoveryReply(text, address) ?: continue
                    val key = "${announcement.address}:${announcement.port}"
                    results[key] = announcement
                }
            } finally {
                runCatching { socket?.close() }
                runCatching { multicastLock?.release() }
            }
            results.values.sortedBy { it.name.lowercase() }
        }

    /**
     * Broadcast global más el dirigido de cada interfaz IPv4. Algunos routers
     * filtran 255.255.255.255 y solo dejan pasar el dirigido.
     */
    private fun broadcastAddresses(): Set<InetAddress> {
        val addresses = linkedSetOf<InetAddress>()
        runCatching { addresses.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.interfaceAddresses.forEach { iface ->
                    iface.broadcast?.let { addresses.add(it) }
                }
            }
        }
        return addresses
    }
}
