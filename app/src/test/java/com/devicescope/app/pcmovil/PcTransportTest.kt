package com.devicescope.app.pcmovil

import com.devicescope.app.pcmovil.link.PcTransport
import org.junit.Assert.*
import org.junit.Test

class PcTransportTest {
    @Test fun priorityIsUsbHotspotWifi() {
        val kinds = listOf("wlan0", "ap0", "rndis0", "tun0")
            .sortedBy { PcTransport.kind(it).ordinal }
        assertEquals(listOf("rndis0", "ap0", "wlan0", "tun0"), kinds)
        assertEquals(PcTransport.Kind.USB, PcTransport.kind("ncm0"))
    }

    @Test fun directSubnetMatchesOnlyItsPrefix() {
        val local = byteArrayOf(192.toByte(), 168.toByte(), 42, 1)
        assertTrue(PcTransport.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 42, 99), 24))
        assertFalse(PcTransport.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 1, 99), 24))
        assertFalse(PcTransport.sameSubnet(local, local, 0))
        assertFalse(PcTransport.sameSubnet(local, ByteArray(16), 24))
        assertTrue(PcTransport.sameSubnet(local, local, 32))
    }
}
