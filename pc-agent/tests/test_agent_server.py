"""Pruebas de integración del agente por loopback (sin Android de por medio).

Se habla el protocolo a mano, igual que lo hace ``PcWire`` en la app: líneas JSON
y frames binarios con cabecera de 6 bytes. Sirven para verificar el contrato que
consume el teléfono.
"""

from __future__ import annotations

import json
import os
import socket
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path

os.environ.setdefault("DEVICESCOPE_HOME", tempfile.mkdtemp(prefix="devicescope-test-"))

from devicescope_agent import protocol  # noqa: E402
from devicescope_agent.server import AgentServer  # noqa: E402
from devicescope_agent.state import AgentState  # noqa: E402

PIN = "246810"
DEVICE = {
    "name": "Pixel de prueba",
    "fp": "DS-TEST0001",
    "platform": "Android 15",
    "model": "Pixel",
}


def free_port() -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    return port


class Client:
    def __init__(self, port: int) -> None:
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock.settimeout(6)
        self.reader = self.sock.makefile("rb")
        self.next_id = 1

    def send(self, type_: str, data: dict | None = None, message_id=None, channel=None) -> int:
        if message_id is None:
            message_id = self.next_id
            self.next_id += 1
        payload = {"v": 1, "type": type_, "id": message_id}
        if channel:
            payload["ch"] = channel
        if data:
            payload["data"] = data
        self.sock.sendall(json.dumps(payload).encode("utf-8") + b"\n")
        return message_id

    def send_binary(self, channel: str, kind: int, payload: bytes) -> None:
        self.sock.sendall(protocol.binary_frame(channel, kind, payload))

    def read(self, timeout: float = 6.0) -> dict:
        self.sock.settimeout(timeout)
        first = self.reader.read(1)
        if not first:
            raise ConnectionError("cerrado")
        if first != b"{":
            raise AssertionError(f"frame binario inesperado: {first!r}")
        line = bytearray(b"{")
        while True:
            char = self.reader.read(1)
            if char == b"\n" or not char:
                break
            line += char
        return json.loads(line.decode("utf-8"))

    def read_binary(self, timeout: float = 6.0):
        self.sock.settimeout(timeout)
        header = self.reader.read(6)
        if len(header) < 6:
            raise ConnectionError("cerrado")
        channel = protocol.CHANNEL_NAMES[header[0]]
        kind = header[1]
        length = struct.unpack("!I", header[2:6])[0]
        payload = self.reader.read(length) if length else b""
        return channel, kind, payload

    def close(self) -> None:
        try:
            self.reader.close()
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class AgentServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.port = free_port()
        cls.state = AgentState(name="PC de prueba", pin=PIN)
        cls.server = AgentServer(cls.state, port=cls.port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls._wait_for_port()

    @classmethod
    def _wait_for_port(cls, timeout: float = 5.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", cls.port), timeout=0.3):
                    return
            except OSError:
                time.sleep(0.05)
        raise RuntimeError("el agente no abrió el puerto")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.stop()
        cls.server.shutdown()

    # ------------------------------------------------------------------ ayuda

    def pair(self) -> tuple[Client, str]:
        client = Client(self.port)
        client.send("hello", {"device": dict(DEVICE, pin=PIN)})
        response = client.read()
        self.assertEqual(response["type"], "pair_ok", response)
        token = response["data"]["token"]
        self.assertTrue(token)
        self.assertIn("capabilities", response["data"])
        self.assertTrue(response["data"]["capabilities"]["files"])
        self.assertTrue(response["data"]["capabilities"]["vpn"])
        return client, token

    # ----------------------------------------------------------------- pruebas

    def test_01_pairing_with_pin(self) -> None:
        client, token = self.pair()
        try:
            self.assertGreater(len(token), 20)
        finally:
            client.close()

    def test_02_hello_with_cached_token_needs_no_pin(self) -> None:
        client, token = self.pair()
        client.close()
        second = Client(self.port)
        try:
            second.send("hello", {"device": DEVICE, "token": token})
            response = second.read()
            self.assertEqual(response["type"], "hello_ack")
            self.assertEqual(response["data"]["agent"]["fp"], self.state.fingerprint)
        finally:
            second.close()

    def test_03_wrong_pin_is_rejected(self) -> None:
        client = Client(self.port)
        try:
            client.send("hello", {"device": dict(DEVICE, fp="DS-OTRO", pin="000000")})
            response = client.read()
            self.assertEqual(response["type"], "pair_fail")
            self.assertIn("attempts_left", response["data"])
        finally:
            client.close()

    def test_04_untrusted_commands_are_refused(self) -> None:
        client = Client(self.port)
        try:
            client.send("hello", {"device": dict(DEVICE, fp="DS-SINPIN")})
            self.assertEqual(client.read()["type"], "pair_required")
            client.send("stats_request", {"interval_ms": 1000})
            response = client.read()
            self.assertEqual(response["type"], "error")
        finally:
            client.close()

    def test_05_stats_are_pushed_and_answered(self) -> None:
        client, _ = self.pair()
        try:
            message_id = client.send("stats_request", {"interval_ms": 500})
            # El agente también empuja `stats` por intervalo, así que hay que
            # esperar la respuesta concreta a esta petición (mismo `id`).
            response = None
            while True:
                candidate = client.read()
                if candidate["type"] == "stats" and candidate.get("id") == message_id:
                    response = candidate
                    break
            data = response["data"]
            for key in ("cpu", "ram_total", "cores", "uptime"):
                self.assertIn(key, data)
            self.assertGreaterEqual(data["ram_total"], 0)
            self.assertIsInstance(data["disks"], list)
            self.assertEqual(response.get("id"), message_id)
        finally:
            client.close()

    def test_06_file_listing_and_download(self) -> None:
        client, _ = self.pair()
        home = Path.home()
        probe = home / "devicescope-test-file.txt"
        probe.write_text("hola desde la PC", encoding="utf-8")
        try:
            client.send("file_list", {"path": str(home)})
            listing = client.read()
            self.assertEqual(listing["type"], "file_list")
            names = [entry["name"] for entry in listing["data"]["entries"]]
            self.assertIn(probe.name, names)

            client.send("file_pull", {"path": str(probe)})
            start = client.read()
            self.assertEqual(start["type"], "file_start")
            self.assertEqual(start["data"]["size"], probe.stat().st_size)
            collected = b""
            while True:
                channel, kind, payload = client.read_binary()
                self.assertEqual(channel, "files")
                if kind == protocol.KIND_CONTROL:
                    marker = json.loads(payload)
                    self.assertTrue(marker["end"])
                    break
                collected += payload
            self.assertEqual(collected, probe.read_bytes())
        finally:
            probe.unlink(missing_ok=True)
            client.close()

    def test_07_upload_lands_in_agent_folder(self) -> None:
        client, _ = self.pair()
        content = b"datos del telefono" * 1000
        try:
            client.send("file_start", {"name": "subida-test.bin", "size": len(content)})
            ack = client.read()
            self.assertEqual(ack["type"], "file_start")
            for index in range(0, len(content), 4096):
                client.send_binary("files", protocol.KIND_FILE_CHUNK, content[index : index + 4096])
            client.send_binary("files", protocol.KIND_CONTROL, json.dumps({"end": True}).encode())
            deadline = time.time() + 5
            while time.time() < deadline:
                response = client.read(timeout=3)
                if response["type"] == "file_end":
                    break
            target = Path(ack["data"]["path"])
            self.assertTrue(target.exists())
            self.assertEqual(target.read_bytes(), content)
            target.unlink(missing_ok=True)
        finally:
            client.close()

    def test_08_vpn_relay_forwards_real_sockets(self) -> None:
        # Servidor eco local: el relay debe conectar y devolver los mismos bytes.
        echo = socket.socket()
        echo.bind(("127.0.0.1", 0))
        echo.listen(1)
        echo_port = echo.getsockname()[1]

        def echo_loop() -> None:
            conn, _ = echo.accept()
            try:
                data = conn.recv(1024)
                conn.sendall(b"eco:" + data)
            finally:
                conn.close()
                echo.close()

        threading.Thread(target=echo_loop, daemon=True).start()

        client, _ = self.pair()
        try:
            client.send("vpn_hello", {"device": DEVICE})
            response = client.read()
            self.assertEqual(response["type"], "vpn_opened")
            relay_port = response["data"]["relay_port"]
            self.assertGreater(relay_port, 0)

            relay = socket.create_connection(("127.0.0.1", relay_port), timeout=5)
            address = b"127.0.0.1"
            sid = 1
            relay.sendall(struct.pack("!IBBH", sid, 1, 6, len(address)) + address + struct.pack("!H", echo_port))
            opened = relay.recv(6)
            self.assertEqual(struct.unpack("!IBB", opened)[1:], (4, 1))
            relay.sendall(struct.pack("!IBH", sid, 2, 4) + b"ping")
            time.sleep(0.3)
            relay.settimeout(5)
            header = relay.recv(5)
            _, kind = struct.unpack("!IB", header)
            self.assertEqual(kind, 2)
            length = struct.unpack("!H", relay.recv(2))[0]
            payload = relay.recv(length)
            self.assertEqual(payload, b"eco:ping")
            relay.close()
        finally:
            client.close()

    def test_09_second_trusted_session_is_refused(self) -> None:
        first, _ = self.pair()
        try:
            second = Client(self.port)
            try:
                second.send("hello", {"device": DEVICE})
                response = second.read()
                self.assertEqual(response["type"], "error")
                self.assertIn("sesión", response["data"]["reason"])
            finally:
                second.close()
        finally:
            first.close()

    def test_10_ping_pong(self) -> None:
        client, _ = self.pair()
        try:
            message_id = client.send("ping")
            response = client.read()
            self.assertEqual(response["type"], "pong")
            self.assertEqual(response.get("id"), message_id)
        finally:
            client.close()

    def test_11_state_persists_pairings(self) -> None:
        client, token = self.pair()
        client.close()
        reloaded = AgentState(name="PC de prueba")
        pairing = reloaded.pairing_for(DEVICE["fp"])
        self.assertIsNotNone(pairing)
        self.assertEqual(pairing.token, token)


if __name__ == "__main__":
    unittest.main(verbosity=2)
