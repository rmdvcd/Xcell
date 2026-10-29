package com.devicescope.app.pcmovil.link

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Motor del túnel VPN: puente entre el `tun` del teléfono y el relay del agente.
 *
 * Modelo real de lo que hace:
 * - El teléfono crea un `tun` IPv4 con las rutas privadas que elija el usuario.
 * - Cada flujo TCP/UDP se abre en el relay del agente con un identificador de
 *   flujo (`OPEN`), que a su vez abre un socket real desde la PC. Así el tráfico
 *   sale con la IP de la PC y alcanza hosts que el teléfono no ve.
 * - Para TCP se sintetizan los paquetes de vuelta (SYN/ACK, ACK, PSH, FIN) con
 *   números de secuencia propios. No hay retransmisión: el `tun` entrega en
 *   orden y sin pérdidas dentro del propio kernel.
 *
 * Límites conocidos y documentados en la UI:
 * - Solo IPv4. ICMP (ping) se descarta.
 * - Las conexiones existentes antes de activar la VPN no se reenganchan.
 * - Es un túnel a la LAN de la PC, no una salida a Internet: las rutas por
 *   defecto las decide el usuario con las casillas de red.
 */
class PcVpnEngine(
    private val tun: ParcelFileDescriptor,
    private val relay: Socket,
    private val localIp: Int,
    private val log: (String) -> Unit = {},
) {

    private sealed interface Event {
        class FromTun(val data: ByteArray) : Event
        class RelayData(val sid: Int, val payload: ByteArray) : Event
        class RelayOpened(val sid: Int, val ok: Boolean) : Event
        class RelayClosed(val sid: Int) : Event
        class RelayFailed(val reason: String) : Event
        object Sweep : Event
    }

    private data class FlowKey(
        val protocol: Int,
        val sourcePort: Int,
        val destinationIp: Int,
        val destinationPort: Int,
    )

    private class Flow(
        val key: FlowKey,
        val sid: Int,
        var mySeq: Long,
        var theirSeq: Long,
        val isn: Long = mySeq,
        var opened: Boolean = false,
        var closed: Boolean = false,
        var lastActive: Long = System.currentTimeMillis(),
    ) {
        val pending = ByteArrayOutputStream()
        var finQueued = false
    }

    private val queue = LinkedBlockingQueue<Event>()
    private val running = AtomicBoolean(false)
    private val flows = LinkedHashMap<FlowKey, Flow>()
    private val bySid = HashMap<Int, Flow>()
    private var nextSid = 1
    private var threads = mutableListOf<Thread>()

    private val packetsIn = AtomicLong(0)
    private val packetsOut = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val tunOutput = FileOutputStream(tun.fileDescriptor)
    private val relayOutputStream: OutputStream = relay.getOutputStream()
    private val relayWriter = DataOutputStream(relayOutputStream)
    private val relayInput: InputStream = relay.getInputStream()
    private val writeLock = Any()

    val receivedPackets: Long get() = packetsIn.get()
    val sentPackets: Long get() = packetsOut.get()
    val droppedPackets: Long get() = dropped.get()
    val openFlows: Int get() = synchronized(flows) { flows.size }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        threads = mutableListOf(
            thread("pc-vpn-tun-reader") { tunLoop() },
            thread("pc-vpn-relay-reader") { relayLoop() },
            thread("pc-vpn-worker") { workerLoop() },
            thread("pc-vpn-sweeper") { sweeperLoop() },
        )
        threads.forEach { it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { relay.close() }
        runCatching { tunOutput.close() }
        runCatching { tun.close() }
        threads.forEach { it.interrupt() }
        threads.clear()
        queue.clear()
        synchronized(flows) {
            flows.clear()
            bySid.clear()
        }
    }

    private fun thread(name: String, body: () -> Unit) = Thread({
        try {
            body()
        } catch (_: Exception) {
            // Cierre normal del túnel.
        }
    }, name).apply { isDaemon = true }

    // ------------------------------------------------------------- lectores

    private fun tunLoop() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(32 * 1024)
        try {
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) continue
                queue.put(Event.FromTun(buffer.copyOf(read)))
            }
        } finally {
            runCatching { input.close() }
        }
    }

    private fun relayLoop() {
        val input = DataInputStream(relayInput)
        try {
            while (running.get()) {
                val sid = input.readInt()
                val type = input.readUnsignedByte()
                when (type) {
                    TYPE_OPENED -> queue.put(Event.RelayOpened(sid, input.readUnsignedByte() != 0))
                    TYPE_DATA -> {
                        val length = input.readUnsignedShort()
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        queue.put(Event.RelayData(sid, payload))
                    }
                    TYPE_CLOSE -> queue.put(Event.RelayClosed(sid))
                    else -> throw IllegalStateException("Frame de relay desconocido: $type")
                }
            }
        } catch (error: Exception) {
            if (running.get()) queue.put(Event.RelayFailed(error.localizedMessage ?: "relay cerrado"))
        }
    }

    // -------------------------------------------------------------- worker

    private fun workerLoop() {
        while (running.get()) {
            val event = queue.take()
            try {
                when (event) {
                    is Event.FromTun -> handleTun(event.data)
                    is Event.RelayOpened -> handleOpened(event)
                    is Event.RelayData -> handleRelayData(event)
                    is Event.RelayClosed -> handleRelayClosed(event)
                    is Event.RelayFailed -> {
                        log("Relay: ${event.reason}")
                        running.set(false)
                    }
                    Event.Sweep -> sweep()
                }
            } catch (error: Exception) {
                log("Error procesando paquete: ${error.localizedMessage}")
            }
        }
    }

    private fun handleTun(data: ByteArray) {
        val parsed = IpPacketCodec.parse(data) ?: run {
            dropped.incrementAndGet()
            return
        }
        packetsIn.incrementAndGet()
        val key = FlowKey(parsed.protocol, parsed.sourcePort, parsed.destinationIp, parsed.destinationPort)

        if (parsed.isTcp) {
            handleTcp(key, parsed)
        } else if (parsed.isUdp) {
            handleUdp(key, parsed)
        } else {
            dropped.incrementAndGet()
        }
    }

    private fun handleTcp(key: FlowKey, packet: IpPacketCodec.ParsedPacket) {
        val existing = synchronized(flows) { flows[key] }
        if (packet.hasFlag(IpPacketCodec.FLAG_SYN) && !packet.hasFlag(IpPacketCodec.FLAG_ACK)) {
            val flow = existing ?: allocateFlow(key, protocol = TYPE_PROTO_TCP, packet)
            if (flow.opened) {
                // SYN repetido: reenviar el mismo SYN|ACK sin consumir secuencia.
                emitTcp(
                    flow,
                    packet.destinationIp,
                    packet.destinationPort,
                    IpPacketCodec.FLAG_SYN or IpPacketCodec.FLAG_ACK,
                    sequence = flow.isn,
                    consume = false,
                )
            }
            return
        }
        val active = existing ?: run {
            dropped.incrementAndGet()
            return
        }
        active.lastActive = System.currentTimeMillis()
        if (packet.hasFlag(IpPacketCodec.FLAG_RST)) {
            closeFlow(active, notifyRelay = true)
            return
        }
        if (packet.payload.isNotEmpty()) {
            if (active.opened) {
                sendRelayData(active.sid, packet.payload)
                active.theirSeq = (packet.sequence + packet.payload.size) and 0xFFFFFFFFL
                emitTcp(active, packet.destinationIp, packet.destinationPort, IpPacketCodec.FLAG_ACK)
            } else if (active.pending.size() < MAX_PENDING) {
                active.pending.write(packet.payload)
            } else {
                dropped.incrementAndGet()
            }
        }
        if (packet.hasFlag(IpPacketCodec.FLAG_FIN)) {
            active.theirSeq = (packet.sequence + packet.payload.size + 1) and 0xFFFFFFFFL
            if (!active.finQueued) {
                active.finQueued = true
                emitTcp(
                    active,
                    packet.destinationIp,
                    packet.destinationPort,
                    IpPacketCodec.FLAG_FIN or IpPacketCodec.FLAG_ACK,
                )
                closeFlow(active, notifyRelay = true)
            }
        }
    }

    private fun handleUdp(key: FlowKey, packet: IpPacketCodec.ParsedPacket) {
        val existing = synchronized(flows) { flows[key] }
        val flow = existing ?: allocateFlow(key, protocol = TYPE_PROTO_UDP, packet)
        flow.lastActive = System.currentTimeMillis()
        // Los flujos UDP no esperan confirmación: se encola igual y el relay abre
        // el socket en cuanto reciba el primer datagrama.
        sendRelayData(flow.sid, packet.payload)
    }

    private fun allocateFlow(
        key: FlowKey,
        protocol: Int,
        packet: IpPacketCodec.ParsedPacket,
    ): Flow {
        val flow = synchronized(flows) {
            if (flows.size >= MAX_FLOWS) {
                val oldest = flows.entries.minByOrNull { it.value.lastActive }
                if (oldest != null) {
                    flows.remove(oldest.key)
                    bySid.remove(oldest.value.sid)
                }
            }
            val id = nextSid++
            val flow = Flow(
                key = key,
                sid = id,
                mySeq = (Random.nextLong() and 0x7FFFFFFFL),
                theirSeq = (packet.sequence + if (packet.isTcp) 1 else 0) and 0xFFFFFFFFL,
            )
            flows[key] = flow
            bySid[id] = flow
            flow
        }
        sendOpen(flow.sid, protocol, key)
        return flow
    }

    private fun handleOpened(event: Event.RelayOpened) {
        val flow = synchronized(flows) { bySid[event.sid] } ?: return
        if (!event.ok) {
            log("El relay rechazó el flujo ${event.sid}")
            if (flow.key.protocol == IpPacketCodec.PROTOCOL_TCP) {
                emitTcp(
                    flow,
                    flow.key.destinationIp,
                    flow.key.destinationPort,
                    IpPacketCodec.FLAG_RST or IpPacketCodec.FLAG_ACK,
                )
            }
            closeFlow(flow, notifyRelay = false)
            return
        }
        flow.opened = true
        flow.lastActive = System.currentTimeMillis()
        if (flow.key.protocol == IpPacketCodec.PROTOCOL_TCP) {
            emitTcp(
                flow,
                flow.key.destinationIp,
                flow.key.destinationPort,
                IpPacketCodec.FLAG_SYN or IpPacketCodec.FLAG_ACK,
                sequence = flow.isn,
            )
            val buffered = synchronized(flow) {
                val bytes = flow.pending.toByteArray()
                flow.pending.reset()
                bytes
            }
            if (buffered.isNotEmpty()) sendRelayData(flow.sid, buffered)
        }
    }

    private fun handleRelayData(event: Event.RelayData) {
        val flow = synchronized(flows) { bySid[event.sid] } ?: return
        flow.lastActive = System.currentTimeMillis()
        if (flow.key.protocol == IpPacketCodec.PROTOCOL_TCP) {
            var offset = 0
            while (offset < event.payload.size) {
                val size = minOf(MAX_SEGMENT, event.payload.size - offset)
                val chunk = if (size == event.payload.size && offset == 0) {
                    event.payload
                } else {
                    event.payload.copyOfRange(offset, offset + size)
                }
                emitTcp(
                    flow,
                    flow.key.destinationIp,
                    flow.key.destinationPort,
                    IpPacketCodec.FLAG_PSH or IpPacketCodec.FLAG_ACK,
                    chunk,
                )
                offset += size
            }
        } else {
            val packet = IpPacketCodec.buildUdp(
                sourceIp = flow.key.destinationIp,
                destinationIp = localIp,
                sourcePort = flow.key.destinationPort,
                destinationPort = flow.key.sourcePort,
                payload = event.payload,
            )
            writeTun(packet)
        }
    }

    private fun handleRelayClosed(event: Event.RelayClosed) {
        val flow = synchronized(flows) { bySid[event.sid] } ?: return
        if (flow.key.protocol == IpPacketCodec.PROTOCOL_TCP && !flow.finQueued) {
            flow.finQueued = true
            emitTcp(
                flow,
                flow.key.destinationIp,
                flow.key.destinationPort,
                IpPacketCodec.FLAG_FIN or IpPacketCodec.FLAG_ACK,
            )
        }
        synchronized(flows) {
            flows.remove(flow.key)
            bySid.remove(flow.sid)
        }
    }

    /**
     * Emite un segmento TCP hacia el teléfono. El ACK apunta al siguiente byte
     * esperado del teléfono; el SEQ es el nuestro y avanza con los datos.
     */
    private fun emitTcp(
        flow: Flow,
        destinationIp: Int,
        destinationPort: Int,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        sequence: Long = flow.mySeq,
        consume: Boolean = true,
    ) {
        val packet = IpPacketCodec.buildTcp(
            sourceIp = destinationIp,
            destinationIp = localIp,
            sourcePort = destinationPort,
            destinationPort = flow.key.sourcePort,
            sequence = sequence,
            acknowledgement = flow.theirSeq,
            flags = flags,
            payload = payload,
        )
        var advance = payload.size.toLong()
        if (flags and IpPacketCodec.FLAG_SYN != 0) advance += 1
        if (flags and IpPacketCodec.FLAG_FIN != 0) advance += 1
        if (consume && advance > 0) {
            flow.mySeq = (flow.mySeq + advance) and 0xFFFFFFFFL
        }
        writeTun(packet)
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunOutput.write(packet)
                tunOutput.flush()
                packetsOut.incrementAndGet()
            } catch (error: Exception) {
                log("No se pudo escribir en el tun: ${error.localizedMessage}")
            }
        }
    }

    private fun closeFlow(flow: Flow, notifyRelay: Boolean) {
        if (notifyRelay) {
            runCatching {
                synchronized(writeLock) {
                    relayWriter.writeInt(flow.sid)
                    relayWriter.writeByte(TYPE_CLOSE)
                    relayWriter.flush()
                }
            }
        }
        synchronized(flows) {
            flows.remove(flow.key)
            bySid.remove(flow.sid)
        }
    }

    private fun sendOpen(sid: Int, protocol: Int, key: FlowKey) {
        val address = IpPacketCodec.formatIp(key.destinationIp).toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            relayWriter.writeInt(sid)
            relayWriter.writeByte(TYPE_OPEN)
            relayWriter.writeByte(protocol)
            relayWriter.writeShort(address.size)
            relayWriter.write(address)
            relayWriter.writeShort(key.destinationPort)
            relayWriter.flush()
        }
    }

    private fun sendRelayData(sid: Int, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val size = minOf(MAX_RELAY_CHUNK, payload.size - offset)
            synchronized(writeLock) {
                relayWriter.writeInt(sid)
                relayWriter.writeByte(TYPE_DATA)
                relayWriter.writeShort(size)
                relayWriter.write(payload, offset, size)
                relayWriter.flush()
            }
            offset += size
        }
    }

    private fun sweeperLoop() {
        while (running.get()) {
            Thread.sleep(15_000)
            queue.put(Event.Sweep)
        }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        val expired = synchronized(flows) {
            flows.values.filter { now - it.lastActive > FLOW_TIMEOUT_MS }.toList()
        }
        expired.forEach { closeFlow(it, notifyRelay = true) }
    }

    companion object {
        const val TYPE_OPEN = 1
        const val TYPE_DATA = 2
        const val TYPE_CLOSE = 3
        const val TYPE_OPENED = 4

        const val TYPE_PROTO_TCP = 6
        const val TYPE_PROTO_UDP = 17

        private const val MAX_SEGMENT = 1400
        private const val MAX_RELAY_CHUNK = 8192
        private const val MAX_PENDING = 64 * 1024
        private const val MAX_FLOWS = 192
        private const val FLOW_TIMEOUT_MS = 180_000L
    }
}
