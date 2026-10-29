"""Respondedor de descubrimiento UDP.

El teléfono envía ``DEVICESCOPE_PCMOVIL_DISCOVER_V1`` a 255.255.255.255:8788 y
cada agente contesta con su nombre, puerto y huella. Sin DNS, sin mDNS, sin
nube: un datagrama y una respuesta.
"""

from __future__ import annotations

import platform
import socket
import threading
from typing import Optional

from . import protocol
from .state import AgentState


class DiscoveryResponder:
    def __init__(self, state: AgentState, control_port: int, port: int = protocol.DISCOVERY_PORT) -> None:
        self.state = state
        self.control_port = control_port
        self.port = port
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False

    def start(self) -> bool:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                except OSError:
                    pass
            sock.bind(("0.0.0.0", self.port))
            sock.settimeout(1.0)
        except OSError as error:
            print(f"[agente] descubrimiento desactivado: {error}")
            return False
        self._sock = sock
        self._running = True
        self._thread = threading.Thread(target=self._loop, name="discovery", daemon=True)
        self._thread.start()
        print(f"[agente] descubrimiento UDP en el puerto {self.port}")
        return True

    def _loop(self) -> None:
        assert self._sock is not None
        while self._running:
            try:
                payload, address = self._sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                return
            if not protocol.decode_discovery_request(payload):
                continue
            reply = protocol.DiscoveryReply(
                name=self.state.name,
                port=self.control_port,
                fingerprint=self.state.fingerprint,
                platform=f"{platform.system()} {platform.release()}",
                requires_pin=True,
            )
            try:
                self._sock.sendto(reply.encode(), address)
            except OSError:
                continue

    def stop(self) -> None:
        self._running = False
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None
