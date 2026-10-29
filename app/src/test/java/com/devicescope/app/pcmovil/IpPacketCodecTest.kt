package com.devicescope.app.pcmovil

import com.devicescope.app.pcmovil.link.IpPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketCodecTest {

    private val phone = IpPacketCodec.parseIp("10.111.0.2")!!
    private val remote = IpPacketCodec.parseIp("192.168.1.50")!!

    @Test
    fun `direcciones ida y vuelta`() {
        assertEquals("10.111.0.2", IpPacketCodec.formatIp(phone))
        assertEquals("192.168.1.50", IpPacketCodec.formatIp(remote))
        assertEquals("255.255.255.255", IpPacketCodec.formatIp(-1))
        assertNull(IpPacketCodec.parseIp("10.0.0"))
        assertNull(IpPacketCodec.parseIp("10.0.0.999"))
        assertNull(IpPacketCodec.parseIp("no.es.ip"))
    }

    @Test
    fun `construye y parsea tcp con datos`() {
        val payload = "GET / HTTP/1.0".toByteArray()
        val packet = IpPacketCodec.buildTcp(
            sourceIp = remote,
            destinationIp = phone,
            sourcePort = 80,
            destinationPort = 41000,
            sequence = 1000,
            acknowledgement = 2000,
            flags = IpPacketCodec.FLAG_PSH or IpPacketCodec.FLAG_ACK,
            payload = payload,
        )
        assertEquals(20 + 20 + payload.size, packet.size)
        // La suma de comprobación de la cabecera IP es válida cuando el pliegue da 0.
        assertEquals(0, IpPacketCodec.checksum(packet, 0, 20))

        val parsed = IpPacketCodec.parse(packet)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertTrue(parsed.isTcp)
        assertEquals(IpPacketCodec.PROTOCOL_TCP, parsed.protocol)
        assertEquals(80, parsed.sourcePort)
        assertEquals(41000, parsed.destinationPort)
        assertEquals(1000L, parsed.sequence)
        assertEquals(2000L, parsed.acknowledgement)
        assertTrue(parsed.hasFlag(IpPacketCodec.FLAG_PSH))
        assertTrue(parsed.hasFlag(IpPacketCodec.FLAG_ACK))
        assertFalse(parsed.hasFlag(IpPacketCodec.FLAG_SYN))
        assertArrayEquals(payload, parsed.payload)
        assertEquals(remote, parsed.sourceIp)
        assertEquals(phone, parsed.destinationIp)
    }

    @Test
    fun `tcp sin payload para ack y fin`() {
        val packet = IpPacketCodec.buildTcp(
            sourceIp = remote,
            destinationIp = phone,
            sourcePort = 443,
            destinationPort = 32_000,
            sequence = 42,
            acknowledgement = 43,
            flags = IpPacketCodec.FLAG_FIN or IpPacketCodec.FLAG_ACK,
        )
        val parsed = IpPacketCodec.parse(packet)!!
        assertFalse(parsed.hasPayload)
        assertTrue(parsed.hasFlag(IpPacketCodec.FLAG_FIN))
        assertEquals(0L, parsed.payload.size.toLong())
    }

    @Test
    fun `flags de syn y rst`() {
        val syn = IpPacketCodec.parse(
            IpPacketCodec.buildTcp(
                sourceIp = remote,
                destinationIp = phone,
                sourcePort = 8080,
                destinationPort = 40_001,
                sequence = 7,
                acknowledgement = 0,
                flags = IpPacketCodec.FLAG_SYN,
            ),
        )!!
        assertTrue(syn.hasFlag(IpPacketCodec.FLAG_SYN))
        assertFalse(syn.hasFlag(IpPacketCodec.FLAG_ACK))

        val rst = IpPacketCodec.parse(
            IpPacketCodec.buildTcp(
                sourceIp = remote,
                destinationIp = phone,
                sourcePort = 1,
                destinationPort = 2,
                sequence = 0,
                acknowledgement = 0,
                flags = IpPacketCodec.FLAG_RST or IpPacketCodec.FLAG_ACK,
            ),
        )!!
        assertTrue(rst.hasFlag(IpPacketCodec.FLAG_RST))
    }

    @Test
    fun `construye y parsea udp de dns`() {
        val query = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val packet = IpPacketCodec.buildUdp(
            sourceIp = remote,
            destinationIp = phone,
            sourcePort = 53,
            destinationPort = 53_535,
            payload = query,
        )
        val parsed = IpPacketCodec.parse(packet)!!
        assertTrue(parsed.isUdp)
        assertEquals(53, parsed.sourcePort)
        assertEquals(53_535, parsed.destinationPort)
        assertArrayEquals(query, parsed.payload)
        assertEquals(0, IpPacketCodec.checksum(packet, 0, 20))
    }

    @Test
    fun `rechaza paquetes invalidos`() {
        assertNull(IpPacketCodec.parse(ByteArray(10)))
        assertNull(IpPacketCodec.parse(ByteArray(40)))
        // Versión 6 en el primer nibble.
        val ipv6ish = ByteArray(40).also { it[0] = 0x60 }
        assertNull(IpPacketCodec.parse(ipv6ish))
        // Longitud total menor que la cabecera.
        val broken = IpPacketCodec.buildUdp(remote, phone, 1, 2, ByteArray(4)).also {
            it[2] = 0
            it[3] = 10
        }
        assertNull(IpPacketCodec.parse(broken))
    }

    @Test
    fun `icmp se parsea sin puertos para contarlo`() {
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[2] = 0
        packet[3] = 28
        packet[9] = IpPacketCodec.PROTOCOL_ICMP.toByte()
        val parsed = IpPacketCodec.parse(packet)
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(IpPacketCodec.PROTOCOL_ICMP, parsed.protocol)
        assertEquals(0, parsed.sourcePort)
        assertFalse(parsed.hasPayload)
    }

    @Test
    fun `la comprobacion de tcp depende de la pseudo cabecera`() {
        val payload = ByteArray(0)
        val first = IpPacketCodec.buildTcp(remote, phone, 80, 1000, 1, 2, IpPacketCodec.FLAG_ACK, payload = payload)
        val second = IpPacketCodec.buildTcp(phone, remote, 80, 1000, 1, 2, IpPacketCodec.FLAG_ACK, payload = payload)
        // El checksum incluye las direcciones: invertirlas cambia el resultado.
        val firstChecksum = ((first[36].toInt() and 0xFF) shl 8) or (first[37].toInt() and 0xFF)
        val secondChecksum = ((second[36].toInt() and 0xFF) shl 8) or (second[37].toInt() and 0xFF)
        assertTrue(firstChecksum != secondChecksum)
    }
}
