"""Protocolo del enlace PC-Móvil (lado agente).

El teléfono (Kotlin) y el agente hablan el mismo protocolo por TCP:

* **Mensajes de control**: un objeto JSON por línea, terminado en ``\\n``.
  ``{"v":1,"type":"hello","id":3,"ch":"control","data":{...}}``
* **Frames binarios**: ``[canal u8][tipo u8][longitud u32 BE][payload]``.
  El primer byte nunca colisiona con ``{`` (0x7B), así que un solo socket puede
  multiplexar control, pantalla, audio, cámara y archivos.

Los ordinales de canal deben coincidir con ``PcProtocol.Channel`` en la app.
"""

from __future__ import annotations

import json
import socket
import struct
import threading
from dataclasses import dataclass
from typing import Any, Iterable, Optional

VERSION = 1
DEFAULT_PORT = 8787
DISCOVERY_PORT = 8788
DISCOVERY_MAGIC = b"DEVICESCOPE_PCMOVIL_DISCOVER_V1"
DISCOVERY_REPLY_PREFIX = "DEVICESCOPE_PCMOVIL_AGENT_V1"
MAX_LINE_BYTES = 512 * 1024
MAX_BINARY_FRAME = 8 * 1024 * 1024

# Canales: el índice es el byte de cabecera y debe calcar el enum de Kotlin.
CHANNELS = {
    "control": 0,
    "input": 1,
    "metrics": 2,
    "mirror": 3,
    "camera": 4,
    "mic": 5,
    "speaker": 6,
    "files": 7,
}
CHANNEL_NAMES = {value: key for key, value in CHANNELS.items()}

KIND_FRAME = 0x01
KIND_AUDIO = 0x02
KIND_FILE_CHUNK = 0x03
KIND_CONTROL = 0x04
KIND_PING = 0x05


class Type:
    """Tipos de mensaje. Deben coincidir con ``PcProtocol.Type``."""

    HELLO = "hello"
    HELLO_ACK = "hello_ack"
    PAIR = "pair"
    PAIR_OK = "pair_ok"
    PAIR_FAIL = "pair_fail"
    PAIR_REQUIRED = "pair_required"
    PING = "ping"
    PONG = "pong"
    ERROR = "error"
    DISCONNECT = "disconnect"

    KEY = "key"
    TEXT = "text"
    MOUSE = "mouse"
    SCROLL = "scroll"
    GESTURE = "gesture"
    CLIPBOARD = "clipboard"
    INPUT_ACK = "input_ack"
    CAPTURE_START = "capture_start"
    CAPTURE_STOP = "capture_stop"

    STATS = "stats"
    STATS_REQUEST = "stats_request"

    MIRROR_START = "mirror_start"
    MIRROR_STOP = "mirror_stop"
    MIRROR_INFO = "mirror_info"
    MIRROR_QUALITY = "mirror_quality"

    CAMERA_START = "camera_start"
    CAMERA_STOP = "camera_stop"
    CAMERA_INFO = "camera_info"

    AUDIO_START = "audio_start"
    AUDIO_STOP = "audio_stop"
    AUDIO_CONFIG = "audio_config"

    FILE_LIST = "file_list"
    FILE_PULL = "file_pull"
    FILE_START = "file_start"
    FILE_END = "file_end"
    FILE_ERROR = "file_error"

    VPN_HELLO = "vpn_hello"
    VPN_OPEN = "vpn_open"
    VPN_OPENED = "vpn_opened"
    VPN_CLOSE = "vpn_close"
    VPN_STATE = "vpn_state"

    SCAN_START = "scan_start"
    SCAN_STOP = "scan_stop"
    SCAN_PROGRESS = "scan_progress"
    SCAN_RESULTS = "scan_results"


def encode(
    type_: str,
    id_: Optional[int] = None,
    channel: Optional[str] = None,
    data: Optional[dict] = None,
) -> bytes:
    """Serializa un mensaje de control como una línea JSON en UTF-8."""

    payload: dict[str, Any] = {"v": VERSION, "type": type_}
    if id_ is not None:
        payload["id"] = id_
    if channel is not None:
        payload["ch"] = channel
    if data:
        payload["data"] = data
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def decode(line: bytes) -> Optional[dict]:
    try:
        message = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(message, dict) or "type" not in message:
        return None
    return message


def binary_frame(channel: str, kind: int, payload: bytes) -> bytes:
    """Cabecera de 6 bytes + payload, tal como la lee ``PcWire``."""

    if channel not in CHANNELS:
        raise ValueError(f"canal desconocido: {channel}")
    return bytes([CHANNELS[channel], kind]) + struct.pack("!I", len(payload)) + payload


def decode_discovery_request(payload: bytes) -> bool:
    return payload.strip() == DISCOVERY_MAGIC


@dataclass
class DiscoveryReply:
    name: str
    port: int
    fingerprint: str
    platform: str
    requires_pin: bool = True

    def encode(self) -> bytes:
        return json.dumps(
            {
                "magic": DISCOVERY_REPLY_PREFIX,
                "name": self.name,
                "port": self.port,
                "fp": self.fingerprint,
                "platform": self.platform,
                "pin": self.requires_pin,
                "v": VERSION,
            },
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")


class WireError(IOError):
    pass


class Wire:
    """Lectura/escritura del enlace multiplexado."""

    def __init__(self, sock: socket.socket) -> None:
        self.sock = sock
        self._reader = sock.makefile("rb")
        self._write_lock = threading.Lock()
        self._closed = False

    def send_json(
        self,
        type_: str,
        id_: Optional[int] = None,
        channel: Optional[str] = None,
        data: Optional[dict] = None,
    ) -> None:
        self._send(encode(type_, id_=id_, channel=channel, data=data) + b"\n")

    def send_binary(self, channel: str, kind: int, payload: bytes) -> None:
        self._send(binary_frame(channel, kind, payload))

    def _send(self, raw: bytes) -> None:
        with self._write_lock:
            if self._closed:
                raise WireError("enlace cerrado")
            try:
                self.sock.sendall(raw)
            except OSError as error:
                self._closed = True
                raise WireError(str(error)) from error

    def read_frame(self) -> Optional[tuple]:
        """Devuelve ``("json", dict)``, ``("binary", canal, tipo, payload)`` o ``None``."""

        first = self._reader.read(1)
        if not first:
            return None
        if first == b"{":
            chunks = bytearray(first)
            while True:
                char = self._reader.read(1)
                if not char:
                    return None
                if char == b"\n":
                    break
                if char != b"\r":
                    chunks += char
                if len(chunks) > MAX_LINE_BYTES:
                    raise WireError("mensaje de control demasiado grande")
            message = decode(bytes(chunks))
            if message is None:
                return ("invalid", bytes(chunks))
            return ("json", message)
        ordinal = first[0]
        channel = CHANNEL_NAMES.get(ordinal)
        if channel is None:
            raise WireError(f"canal binario desconocido: {ordinal}")
        header = self._read_exact(5)
        kind = header[0]
        length = struct.unpack("!I", header[1:5])[0]
        if length > MAX_BINARY_FRAME:
            raise WireError(f"frame binario de {length} bytes")
        payload = self._read_exact(length) if length else b""
        return ("binary", channel, kind, payload)

    def _read_exact(self, size: int) -> bytes:
        data = self._reader.read(size)
        if data is None or len(data) != size:
            raise WireError("enlace cerrado a mitad de un frame")
        return data

    def close(self) -> None:
        self._closed = True
        try:
            self._reader.close()
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def iter_frames(wire: Wire) -> Iterable[tuple]:
    while True:
        frame = wire.read_frame()
        if frame is None:
            return
        yield frame
