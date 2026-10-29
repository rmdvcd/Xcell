"""Pruebas del protocolo: es la única cosa que comparten Android y la PC."""

from __future__ import annotations

import json
import struct
import unittest

from devicescope_agent import protocol


class EncodingTests(unittest.TestCase):
    def test_encode_minimal(self) -> None:
        raw = protocol.encode("ping")
        self.assertEqual(json.loads(raw), {"v": 1, "type": "ping"})

    def test_encode_with_id_channel_and_data(self) -> None:
        raw = protocol.encode(
            "mouse",
            id_=7,
            channel="input",
            data={"target": "pc", "action": "move", "dx": 1.5, "dy": -2, "absolute": False},
        )
        decoded = json.loads(raw)
        self.assertEqual(decoded["v"], 1)
        self.assertEqual(decoded["type"], "mouse")
        self.assertEqual(decoded["id"], 7)
        self.assertEqual(decoded["ch"], "input")
        self.assertEqual(decoded["data"]["dx"], 1.5)
        self.assertIs(decoded["data"]["absolute"], False)
        self.assertNotIn(b" ", raw)  # una línea compacta, sin espacios

    def test_encode_keeps_utf8(self) -> None:
        raw = protocol.encode("text", data={"text": "hola ñandú ✓"})
        decoded = json.loads(raw.decode("utf-8"))
        self.assertEqual(decoded["data"]["text"], "hola ñandú ✓")

    def test_decode_rejects_garbage(self) -> None:
        self.assertIsNone(protocol.decode(b"{no es json"))
        self.assertIsNone(protocol.decode(b'{"sin":"tipo"}'))
        self.assertIsNone(protocol.decode(b'["lista"]'))

    def test_decode_accepts_message(self) -> None:
        message = protocol.decode(b'{"v":1,"type":"ping","id":2}')
        self.assertEqual(message["type"], "ping")
        self.assertEqual(message["id"], 2)


class FramingTests(unittest.TestCase):
    def test_binary_header(self) -> None:
        payload = b"\x01\x02\x03"
        frame = protocol.binary_frame("mirror", protocol.KIND_FRAME, payload)
        self.assertEqual(frame[0], protocol.CHANNELS["mirror"])
        self.assertEqual(frame[1], protocol.KIND_FRAME)
        self.assertEqual(struct.unpack("!I", frame[2:6])[0], 3)
        self.assertEqual(frame[6:], payload)

    def test_channel_ordinals_match_kotlin_enum(self) -> None:
        # PcProtocol.Channel: CONTROL, INPUT, METRICS, MIRROR, CAMERA, MIC, SPEAKER, FILES
        self.assertEqual(
            [protocol.CHANNELS[name] for name in ("control", "input", "metrics", "mirror",
                                                  "camera", "mic", "speaker", "files")],
            [0, 1, 2, 3, 4, 5, 6, 7],
        )

    def test_unknown_channel(self) -> None:
        with self.assertRaises(ValueError):
            protocol.binary_frame("telepatia", protocol.KIND_FRAME, b"")


class DiscoveryTests(unittest.TestCase):
    def test_reply_roundtrip(self) -> None:
        reply = protocol.DiscoveryReply(
            name="DESKTOP-DEV",
            port=8787,
            fingerprint="PC-AAAA1111",
            platform="Windows 11",
        )
        encoded = json.loads(reply.encode())
        self.assertEqual(encoded["magic"], protocol.DISCOVERY_REPLY_PREFIX)
        self.assertEqual(encoded["name"], "DESKTOP-DEV")
        self.assertEqual(encoded["port"], 8787)
        self.assertIs(encoded["pin"], True)

    def test_magic_matches_android(self) -> None:
        self.assertEqual(protocol.DISCOVERY_MAGIC, b"DEVICESCOPE_PCMOVIL_DISCOVER_V1")
        self.assertEqual(protocol.DISCOVERY_REPLY_PREFIX, "DEVICESCOPE_PCMOVIL_AGENT_V1")
        self.assertEqual(protocol.DISCOVERY_PORT, 8788)
        self.assertEqual(protocol.DEFAULT_PORT, 8787)


if __name__ == "__main__":
    unittest.main()
