"""Relay del túnel VPN: abre los sockets reales desde la PC.

El teléfono crea un ``tun`` IPv4 y manda cada flujo como:

* ``OPEN``  ``[sid u32][1][proto u8][addrLen u16][addr][port u16]``
* ``DATA``  ``[sid u32][2][len u16][bytes]``
* ``CLOSE`` ``[sid u32][3]``

El agente contesta con ``OPENED`` ``[sid][4][ok u8]``, ``DATA`` y ``CLOSE`` con
el mismo formato. Un hilo por flujo escribe en la conexión del teléfono con un
candado: el orden dentro de un flujo se respeta y entre flujos no importa.
"""

from __future__ import annotations

import socket
import struct
import threading
from typing import Optional

TYPE_OPEN = 1
TYPE_DATA = 2
TYPE_CLOSE = 3
TYPE_OPENED = 4

PROTO_TCP = 6
PROTO_UDP = 17

MAX_FLOWS = 192
CONNECT_TIMEOUT = 8.0


class _Flow:
    def __init__(self, sid: int, sock: socket.socket, protocol: int) -> None:
        self.sid = sid
        self.sock = sock
        self.protocol = protocol
        self.closed = False


class VpnRelay:
    """Servidor TCP local que traduce flujos del túnel a sockets reales."""

    def __init__(self) -> None:
        self._server: Optional[socket.socket] = None
        self._port = 0
        self._thread: Optional[threading.Thread] = None
        self._client: Optional[socket.socket] = None
        self._lock = threading.Lock()
        self._write_lock = threading.Lock()
        self._flows: dict[int, _Flow] = {}
        self._running = False
        self.bytes_in = 0
        self.bytes_out = 0

    @property
    def port(self) -> int:
        return self._port

    @property
    def active(self) -> bool:
        return self._running

    def start(self) -> int:
        if self._running:
            return self._port
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", 0))
        server.listen(2)
        self._port = int(server.getsockname()[1])
        self._server = server
        self._running = True
        self._thread = threading.Thread(target=self._accept_loop, name="vpn-accept", daemon=True)
        self._thread.start()
        return self._port

    def stop(self) -> None:
        self._running = False
        with self._lock:
            for flow in list(self._flows.values()):
                self._close_flow(flow, notify_phone=False)
        client = self._client
        self._client = None
        if client is not None:
            try:
                client.close()
            except OSError:
                pass
        if self._server is not None:
            try:
                self._server.close()
            except OSError:
                pass
            self._server = None
        self._port = 0

    # ------------------------------------------------------------- aceptación

    def _accept_loop(self) -> None:
        while self._running and self._server is not None:
            try:
                client, _ = self._server.accept()
            except OSError:
                return
            client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._lock:
                if self._client is not None:
                    try:
                        self._client.close()
                    except OSError:
                        pass
                self._client = client
            threading.Thread(target=self._client_loop, args=(client,), name="vpn-client", daemon=True).start()

    def _client_loop(self, client: socket.socket) -> None:
        reader = client.makefile("rb")
        try:
            while self._running:
                header = reader.read(5)
                if len(header) < 5:
                    return
                sid, kind = struct.unpack("!IB", header)
                if kind == TYPE_OPEN:
                    extra = reader.read(3)
                    if len(extra) < 3:
                        return
                    protocol, addr_len = struct.unpack("!BH", extra)
                    address = reader.read(addr_len).decode("utf-8", "replace")
                    port_raw = reader.read(2)
                    if len(port_raw) < 2:
                        return
                    port = struct.unpack("!H", port_raw)[0]
                    self._open_flow(sid, protocol, address, port)
                elif kind == TYPE_DATA:
                    length_raw = reader.read(2)
                    if len(length_raw) < 2:
                        return
                    length = struct.unpack("!H", length_raw)[0]
                    payload = reader.read(length) if length else b""
                    self._data_from_phone(sid, payload)
                elif kind == TYPE_CLOSE:
                    with self._lock:
                        flow = self._flows.pop(sid, None)
                    if flow is not None:
                        self._close_flow(flow, notify_phone=False)
                else:
                    return
        except (OSError, struct.error):
            return
        finally:
            try:
                reader.close()
            except OSError:
                pass
            with self._lock:
                if self._client is client:
                    self._client = None
                flows = list(self._flows.values())
                self._flows.clear()
            for flow in flows:
                self._close_flow(flow, notify_phone=False)

    # ------------------------------------------------------------------ flujos

    def _open_flow(self, sid: int, protocol: int, address: str, port: int) -> None:
        with self._lock:
            if len(self._flows) >= MAX_FLOWS:
                self._send_opened(sid, False)
                return
        try:
            if protocol == PROTO_UDP:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.settimeout(30)
                sock.connect((address, port))
            else:
                sock = socket.create_connection((address, port), timeout=CONNECT_TIMEOUT)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sock.settimeout(None)
        except OSError:
            self._send_opened(sid, False)
            return
        flow = _Flow(sid, sock, protocol)
        with self._lock:
            self._flows[sid] = flow
        self._send_opened(sid, True)
        threading.Thread(target=self._reader_loop, args=(flow,), name=f"vpn-flow-{sid}", daemon=True).start()

    def _reader_loop(self, flow: _Flow) -> None:
        try:
            while not flow.closed:
                if flow.protocol == PROTO_UDP:
                    data, _ = flow.sock.recvfrom(65535)
                else:
                    data = flow.sock.recv(16384)
                if not data:
                    break
                self.bytes_out += len(data)
                self._send_data_to_phone(flow.sid, data)
        except OSError:
            pass
        finally:
            self._send_close_to_phone(flow.sid)
            with self._lock:
                self._flows.pop(flow.sid, None)
            self._close_flow(flow, notify_phone=False)

    def _data_from_phone(self, sid: int, payload: bytes) -> None:
        with self._lock:
            flow = self._flows.get(sid)
        if flow is None:
            return
        try:
            self.bytes_in += len(payload)
            flow.sock.sendall(payload)
        except OSError:
            self._send_close_to_phone(sid)
            with self._lock:
                self._flows.pop(sid, None)
            self._close_flow(flow, notify_phone=False)

    def _close_flow(self, flow: _Flow, notify_phone: bool) -> None:
        flow.closed = True
        try:
            flow.sock.close()
        except OSError:
            pass
        if notify_phone:
            self._send_close_to_phone(flow.sid)

    # --------------------------------------------------------------- escritura

    def _write(self, payload: bytes) -> None:
        client = self._client
        if client is None:
            return
        with self._write_lock:
            try:
                client.sendall(payload)
            except OSError:
                pass

    def _send_opened(self, sid: int, ok: bool) -> None:
        self._write(struct.pack("!IBB", sid, TYPE_OPENED, 1 if ok else 0))

    def _send_data_to_phone(self, sid: int, payload: bytes) -> None:
        chunk = 8192
        for index in range(0, len(payload), chunk):
            piece = payload[index : index + chunk]
            self._write(struct.pack("!IBH", sid, TYPE_DATA, len(piece)) + piece)

    def _send_close_to_phone(self, sid: int) -> None:
        self._write(struct.pack("!IB", sid, TYPE_CLOSE))
