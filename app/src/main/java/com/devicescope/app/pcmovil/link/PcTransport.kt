package com.devicescope.app.pcmovil.link

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Preferencia local; no cambia redes ni activa tethering sin intervención del usuario. */
object PcTransport {
    enum class Kind(val label: String) { USB("USB"), HOTSPOT("Punto de acceso"), WIFI("Wi-Fi"), OTHER("Red local") }

    fun kind(name: String): Kind = when {
        name.lowercase().let { it.startsWith("rndis") || it.startsWith("usb") || it.startsWith("ncm") } -> Kind.USB
        name.lowercase().let { it.startsWith("ap") || it.startsWith("swlan") || it.startsWith("wlan1") } -> Kind.HOTSPOT
        name.lowercase().startsWith("wlan") -> Kind.WIFI
        else -> Kind.OTHER
    }

    fun sameSubnet(local: ByteArray, remote: ByteArray, prefix: Int): Boolean {
        if (local.size != 4 || remote.size != 4 || prefix !in 1..32) return false
        return (0 until prefix).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (local[bit / 8].toInt() and mask) == (remote[bit / 8].toInt() and mask)
        }
    }

    data class Route(val local: InetAddress, val kind: Kind)

    /** Solo interfaces IPv4 con ruta directa al agente; excluye VPN y loopback. */
    fun route(address: InetAddress): Route? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && kind(it.name) != Kind.OTHER }
            .sortedBy { kind(it.name).ordinal }
            .firstNotNullOfOrNull { nif ->
                nif.interfaceAddresses.firstOrNull {
                    it.address is Inet4Address && sameSubnet(it.address.address, address.address, it.networkPrefixLength.toInt())
                }?.let { Route(it.address, kind(nif.name)) }
            }
    }.getOrNull()

    fun priority(address: String): Int = runCatching {
        route(InetAddress.getByName(address))?.kind?.ordinal ?: Kind.OTHER.ordinal
    }.getOrDefault(Kind.OTHER.ordinal)
}
