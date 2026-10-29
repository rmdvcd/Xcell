package com.devicescope.app.pcmovil.link

/**
 * Códec IPv4/TCP/UDP en Kotlin puro, sin dependencias de Android.
 *
 * El túnel VPN del módulo no usa librerías nativas: el teléfono crea un `tun`,
 * lee paquetes IP, y los convierte en flujos hacia el relay del agente. Para
 * devolver datos al teléfono hay que fabricar paquetes TCP con números de
 * secuencia propios, de ahí este códec.
 *
 * Alcance real: IPv4, TCP sin opciones y UDP. ICMP y IPv6 se cuentan y se
 * descartan (ver notas en la UI del túnel).
 */
object IpPacketCodec {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17
    const val PROTOCOL_ICMP = 1

    const val FLAG_FIN = 0x01
    const val FLAG_SYN = 0x02
    const val FLAG_RST = 0x04
    const val FLAG_PSH = 0x08
    const val FLAG_ACK = 0x10

    /** Paquete IPv4 ya despiezado. */
    data class ParsedPacket(
        val protocol: Int,
        val sourceIp: Int,
        val destinationIp: Int,
        val sourcePort: Int,
        val destinationPort: Int,
        val tcpFlags: Int,
        val sequence: Long,
        val acknowledgement: Long,
        val window: Int,
        val payload: ByteArray,
        val totalLength: Int,
    ) {
        val isTcp: Boolean get() = protocol == PROTOCOL_TCP
        val isUdp: Boolean get() = protocol == PROTOCOL_UDP
        val hasPayload: Boolean get() = payload.isNotEmpty()
        fun hasFlag(flag: Int): Boolean = (tcpFlags and flag) != 0
    }

    fun parse(packet: ByteArray, length: Int = packet.size): ParsedPacket? {
        if (length < 20) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || headerLength > length) return null
        val protocol = packet[9].toInt() and 0xFF
        val totalLength = get16(packet, 2)
        if (totalLength < headerLength || totalLength > length) return null
        val source = get32(packet, 12)
        val destination = get32(packet, 16)
        val payloadOffset = headerLength

        if (protocol == PROTOCOL_TCP) {
            if (totalLength < headerLength + 20) return null
            val sourcePort = get16(packet, headerLength)
            val destinationPort = get16(packet, headerLength + 2)
            val sequence = get32(packet, headerLength + 4).toLong() and 0xFFFFFFFFL
            val acknowledgement = get32(packet, headerLength + 8).toLong() and 0xFFFFFFFFL
            val dataOffset = ((packet[headerLength + 12].toInt() and 0xF0) shr 4) * 4
            val flags = packet[headerLength + 13].toInt() and 0x3F
            val window = get16(packet, headerLength + 14)
            val tcpPayloadOffset = headerLength + maxOf(dataOffset, 20)
            if (tcpPayloadOffset > totalLength) return null
            val payload = packet.copyOfRange(tcpPayloadOffset, totalLength)
            return ParsedPacket(
                protocol = protocol,
                sourceIp = source,
                destinationIp = destination,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                tcpFlags = flags,
                sequence = sequence,
                acknowledgement = acknowledgement,
                window = window,
                payload = payload,
                totalLength = totalLength,
            )
        }
        if (protocol == PROTOCOL_UDP) {
            if (totalLength < headerLength + 8) return null
            val sourcePort = get16(packet, headerLength)
            val destinationPort = get16(packet, headerLength + 2)
            val payload = packet.copyOfRange(headerLength + 8, totalLength)
            return ParsedPacket(
                protocol = protocol,
                sourceIp = source,
                destinationIp = destination,
                sourcePort = sourcePort,
                destinationPort = destinationPort,
                tcpFlags = 0,
                sequence = 0,
                acknowledgement = 0,
                window = 0,
                payload = payload,
                totalLength = totalLength,
            )
        }
        // ICMP u otros: se devuelve sin puertos para poder contarlos y descartarlos.
        return ParsedPacket(
            protocol = protocol,
            sourceIp = source,
            destinationIp = destination,
            sourcePort = 0,
            destinationPort = 0,
            tcpFlags = 0,
            sequence = 0,
            acknowledgement = 0,
            window = 0,
            payload = ByteArray(0),
            totalLength = totalLength,
        )
    }

    /** Construye un paquete IPv4 + TCP completo. */
    fun buildTcp(
        sourceIp: Int,
        destinationIp: Int,
        sourcePort: Int,
        destinationPort: Int,
        sequence: Long,
        acknowledgement: Long,
        flags: Int,
        window: Int = 65535,
        payload: ByteArray = ByteArray(0),
        ttl: Int = 64,
    ): ByteArray {
        val totalLength = 20 + 20 + payload.size
        val packet = ByteArray(totalLength)
        writeIpHeader(
            packet = packet,
            totalLength = totalLength,
            protocol = PROTOCOL_TCP,
            sourceIp = sourceIp,
            destinationIp = destinationIp,
            ttl = ttl,
        )
        val tcpOffset = 20
        put16(packet, tcpOffset, sourcePort)
        put16(packet, tcpOffset + 2, destinationPort)
        put32(packet, tcpOffset + 4, sequence)
        put32(packet, tcpOffset + 8, acknowledgement)
        packet[tcpOffset + 12] = 0x50
        packet[tcpOffset + 13] = flags.toByte()
        put16(packet, tcpOffset + 14, window)
        put16(packet, tcpOffset + 16, 0)
        put16(packet, tcpOffset + 18, 0)
        payload.copyInto(packet, tcpOffset + 20)
        val checksum = tcpChecksum(sourceIp, destinationIp, packet, tcpOffset, 20 + payload.size)
        put16(packet, tcpOffset + 16, checksum)
        return packet
    }

    fun buildUdp(
        sourceIp: Int,
        destinationIp: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        ttl: Int = 64,
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)
        writeIpHeader(packet, totalLength, PROTOCOL_UDP, sourceIp, destinationIp, ttl)
        val udpOffset = 20
        put16(packet, udpOffset, sourcePort)
        put16(packet, udpOffset + 2, destinationPort)
        put16(packet, udpOffset + 4, 8 + payload.size)
        put16(packet, udpOffset + 6, 0)
        payload.copyInto(packet, udpOffset + 8)
        val checksum = udpChecksum(sourceIp, destinationIp, packet, udpOffset, 8 + payload.size)
        put16(packet, udpOffset + 6, checksum)
        return packet
    }

    private fun writeIpHeader(
        packet: ByteArray,
        totalLength: Int,
        protocol: Int,
        sourceIp: Int,
        destinationIp: Int,
        ttl: Int,
    ) {
        packet[0] = 0x45
        packet[1] = 0
        put16(packet, 2, totalLength)
        put16(packet, 4, 0)
        put16(packet, 6, 0x4000)
        packet[8] = ttl.toByte()
        packet[9] = protocol.toByte()
        put16(packet, 10, 0)
        put32(packet, 12, sourceIp.toLong() and 0xFFFFFFFFL)
        put32(packet, 16, destinationIp.toLong() and 0xFFFFFFFFL)
        put16(packet, 10, checksum(packet, 0, 20))
    }

    private fun tcpChecksum(
        sourceIp: Int,
        destinationIp: Int,
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sum = 0L
        sum += (sourceIp.toLong() and 0xFFFFFFFFL) ushr 16
        sum += sourceIp.toLong() and 0xFFFF
        sum += (destinationIp.toLong() and 0xFFFFFFFFL) ushr 16
        sum += destinationIp.toLong() and 0xFFFF
        sum += PROTOCOL_TCP.toLong()
        sum += length.toLong()
        sum += segmentSum(packet, offset, length)
        return fold(sum)
    }

    private fun udpChecksum(
        sourceIp: Int,
        destinationIp: Int,
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sum = 0L
        sum += (sourceIp.toLong() and 0xFFFFFFFFL) ushr 16
        sum += sourceIp.toLong() and 0xFFFF
        sum += (destinationIp.toLong() and 0xFFFFFFFFL) ushr 16
        sum += destinationIp.toLong() and 0xFFFF
        sum += PROTOCOL_UDP.toLong()
        sum += length.toLong()
        sum += segmentSum(packet, offset, length)
        val result = fold(sum)
        return if (result == 0) 0xFFFF else result
    }

    private fun segmentSum(packet: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) {
            sum += (packet[index].toInt() and 0xFF) shl 8
        }
        return sum
    }

    fun checksum(packet: ByteArray, offset: Int, length: Int): Int = fold(segmentSum(packet, offset, length))

    private fun fold(sum: Long): Int {
        var value = sum
        while (value shr 16 != 0L) {
            value = (value and 0xFFFF) + (value shr 16)
        }
        return (value.inv() and 0xFFFF).toInt()
    }

    private fun get16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private fun get32(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 24) or
            ((packet[offset + 1].toInt() and 0xFF) shl 16) or
            ((packet[offset + 2].toInt() and 0xFF) shl 8) or
            (packet[offset + 3].toInt() and 0xFF)

    private fun put16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 1] = (value and 0xFF).toByte()
    }

    private fun put32(packet: ByteArray, offset: Int, value: Long) {
        packet[offset] = ((value shr 24) and 0xFF).toByte()
        packet[offset + 1] = ((value shr 16) and 0xFF).toByte()
        packet[offset + 2] = ((value shr 8) and 0xFF).toByte()
        packet[offset + 3] = (value and 0xFF).toByte()
    }

    fun formatIp(address: Int): String =
        "${(address ushr 24) and 0xFF}.${(address ushr 16) and 0xFF}.${(address ushr 8) and 0xFF}.${address and 0xFF}"

    fun parseIp(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = (result shl 8) or value
        }
        return result
    }
}
