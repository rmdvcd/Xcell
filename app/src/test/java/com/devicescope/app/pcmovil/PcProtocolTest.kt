package com.devicescope.app.pcmovil

import com.devicescope.app.pcmovil.link.PcProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El agente de Python replica este formato en ``pc-agent/tests/test_protocol.py``.
 * Si cambia una línea, cambian las dos suites.
 */
class PcProtocolTest {

    @Test
    fun `codifica mouse con id y canal`() {
        val line = PcProtocol.encode(
            type = PcProtocol.Type.MOUSE,
            id = 7,
            channel = PcProtocol.Channel.INPUT,
            data = linkedMapOf("target" to "pc", "action" to "move", "dx" to 1.5, "dy" to -2, "absolute" to false),
        )
        assertEquals(
            """{"v":1,"type":"mouse","id":7,"ch":"input","data":{"target":"pc","action":"move","dx":1.5,"dy":-2,"absolute":false}}""",
            line,
        )
    }

    @Test
    fun `hello no lleva canal ni id`() {
        val line = PcProtocol.encode(PcProtocol.Type.HELLO, data = mapOf("token" to null))
        assertEquals("""{"v":1,"type":"hello","data":{"token":null}}""", line)
    }

    @Test
    fun `decodifica un mensaje de control`() {
        val envelope = PcProtocol.decode(
            """{"v":1,"type":"stats","data":{"cpu":12.5,"ram_total":16000000000,"disks":[{"mount":"C:\\","used":1,"total":2}]}}""",
        )
        assertNotNull(envelope)
        requireNotNull(envelope)
        assertEquals(PcProtocol.Type.STATS, envelope.type)
        assertEquals(12.5, envelope.double("cpu"), 0.0001)
        assertEquals(16_000_000_000L, envelope.long("ram_total"))
        val disks = envelope.list("disks")
        assertEquals(1, disks.size)
        val disk = envelope.list("disks").first() as Map<*, *>
        assertEquals("C:\\", disk["mount"])
    }

    @Test
    fun `reconoce solicitud de PIN del agente`() {
        val envelope = PcProtocol.decode(
            """{"v":1,"type":"pair_required","id":1,"data":{"agent":{"name":"Mi PC"},"attempts_left":3,"message":"Escribe el PIN"}}""",
        )
        requireNotNull(envelope)
        assertEquals(PcProtocol.Type.PAIR_REQUIRED, envelope.type)
        assertEquals(1L, envelope.id)
        assertEquals("Mi PC", envelope.map("agent")["name"])
        assertEquals(3, envelope.int("attempts_left"))
        assertEquals("Escribe el PIN", envelope.string("message"))
    }

    @Test
    fun `metodos tolerantes del envelope`() {
        val envelope = PcProtocol.decode(
            """{"v":1,"type":"pair_fail","data":{"reason":"PIN incorrecto","attempts_left":2,"ok":"true","flag":1}}""",
        )
        requireNotNull(envelope)
        assertEquals("PIN incorrecto", envelope.string("reason"))
        assertEquals("sin datos", envelope.stringOr("otra", "sin datos"))
        assertEquals(2, envelope.int("attempts_left"))
        assertEquals(0, envelope.int("inexistente"))
        assertTrue(envelope.boolean("ok"))
        assertTrue(envelope.boolean("flag"))
        assertFalse(envelope.boolean("nada"))
        assertTrue(envelope.map("no_existe").isEmpty())
    }

    @Test
    fun `decode devuelve null con basura`() {
        assertNull(PcProtocol.decode("no es json"))
        assertNull(PcProtocol.decode("""{"sin":"tipo"}"""))
        assertNull(PcProtocol.decode(""))
    }

    @Test
    fun `respuesta de descubrimiento`() {
        val reply = PcProtocol.encodeDiscoveryReply(
            name = "DESKTOP-DEV",
            port = 8787,
            fingerprint = "PC-AAAA1111",
            platform = "Windows 11",
            requiresPin = true,
        )
        val announcement = PcProtocol.decodeDiscoveryReply(reply, "192.168.1.20")
        assertNotNull(announcement)
        requireNotNull(announcement)
        assertEquals("DESKTOP-DEV", announcement.name)
        assertEquals(8787, announcement.port)
        assertEquals("192.168.1.20", announcement.address)
        assertTrue(announcement.requiresPin)
    }

    @Test
    fun `descubrimiento rechaza respuestas ajenas`() {
        val other = """{"magic":"OTRA_COSA","name":"x","port":1}"""
        assertNull(PcProtocol.decodeDiscoveryReply(other, "10.0.0.2"))
        assertNull(PcProtocol.decodeDiscoveryReply("{}", "10.0.0.2"))
    }

    @Test
    fun `cabecera binaria de ida y vuelta`() {
        val header = PcProtocol.BinHeader.encode(PcProtocol.Channel.MIRROR, PcProtocol.BinHeader.KIND_FRAME, 65_536)
        assertEquals(6, header.size)
        assertEquals(PcProtocol.Channel.MIRROR, PcProtocol.BinHeader.channelOf(header))
        assertEquals(PcProtocol.BinHeader.KIND_FRAME, PcProtocol.BinHeader.kindOf(header))
        assertEquals(65_536, PcProtocol.BinHeader.lengthOf(header))
    }

    @Test
    fun `ordinales de canal coinciden con el agente`() {
        assertEquals(0, PcProtocol.Channel.CONTROL.ordinal)
        assertEquals(1, PcProtocol.Channel.INPUT.ordinal)
        assertEquals(2, PcProtocol.Channel.METRICS.ordinal)
        assertEquals(3, PcProtocol.Channel.MIRROR.ordinal)
        assertEquals(4, PcProtocol.Channel.CAMERA.ordinal)
        assertEquals(5, PcProtocol.Channel.MIC.ordinal)
        assertEquals(6, PcProtocol.Channel.SPEAKER.ordinal)
        assertEquals(7, PcProtocol.Channel.FILES.ordinal)
    }

    @Test
    fun `huella estable y sin datos personales`() {
        val first = PcProtocol.deviceFingerprint("Pixel 7", "Google", "abc123")
        val second = PcProtocol.deviceFingerprint("Pixel 7", "Google", "abc123")
        val other = PcProtocol.deviceFingerprint("Pixel 8", "Google", "abc123")
        assertEquals(first, second)
        assertTrue(first != other)
        assertTrue(first.startsWith("DS-"))
        assertTrue(first == first.uppercase())
        assertFalse(first.contains("abc123"))
    }

    @Test
    fun `generador de ids correlativo`() {
        val ids = PcProtocol.IdGenerator()
        assertEquals(1L, ids.nextId())
        assertEquals(2L, ids.nextId())
        assertEquals(3L, ids.peek())
    }

    @Test
    fun `constantes compartidas`() {
        assertEquals(1, PcProtocol.VERSION)
        assertEquals(8787, PcProtocol.DEFAULT_PORT)
        assertEquals(8788, PcProtocol.DISCOVERY_PORT)
        assertEquals("DEVICESCOPE_PCMOVIL_DISCOVER_V1", PcProtocol.DISCOVERY_MAGIC)
        assertEquals("DEVICESCOPE_PCMOVIL_AGENT_V1", PcProtocol.DISCOVERY_REPLY_PREFIX)
    }
}
