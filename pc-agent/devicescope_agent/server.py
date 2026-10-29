"""Servidor del agente: sesión, emparejamiento y herramientas.

Un solo hilo lee del socket (el bucle de ``Session.run``); los flujos que
producen datos (métricas, espejo, audio, cámara, análisis de LAN) corren en
hilos aparte y escriben con el candado de ``Wire``.
"""

from __future__ import annotations

import json
import os
import platform
import socket
import threading
import time
import traceback
from pathlib import Path
from typing import Optional

from . import protocol
from .input_backend import InputBackend, screen_size
from .media import AudioBridge, CameraWindow, ScreenStreamer
from .protocol import Type, Wire, WireError
from .state import AgentState, local_ip
from .system_info import MetricsSampler, scan_lan
from .vpn import VpnRelay

AGENT_VERSION = "1.0.0"
UPLOAD_DIRNAME = "DeviceScope"


def _capabilities(inputs: InputBackend, screen: ScreenStreamer, audio: AudioBridge, camera: bool) -> dict:
    caps = inputs.capabilities()
    return {
        "keyboard": caps.keyboard,
        "mouse": caps.mouse,
        "relative_mouse": caps.relative_mouse,
        "screen_mirror": screen.available,
        "screen_view": screen.available,
        "mic": audio.mic_available,
        "speaker": audio.speaker_available,
        "files": True,
        "vpn": True,
        "lan_scan": True,
        "clipboard": caps.clipboard,
        "camera": camera,
        "input_backend": caps.backend,
        "capture": caps.capture,
    }


def _agent_descriptor(state: AgentState) -> dict:
    return {
        "name": state.name,
        "platform": f"{platform.system()} {platform.release()}",
        "version": AGENT_VERSION,
        "fp": state.fingerprint,
    }


class AgentServer:
    def __init__(self, state: AgentState, port: int = protocol.DEFAULT_PORT) -> None:
        self.state = state
        self.port = port
        self.inputs = InputBackend()
        self.screen = ScreenStreamer()
        self.audio = AudioBridge()
        self.camera_window = CameraWindow()
        self.vpn = VpnRelay()
        self.sampler = MetricsSampler()
        self._server: Optional[socket.socket] = None
        self._running = False
        self._session: Optional["Session"] = None
        self._session_lock = threading.Lock()

    # ------------------------------------------------------------- ciclo de vida

    def capabilities(self) -> dict:
        return _capabilities(
            self.inputs,
            self.screen,
            self.audio,
            self.camera_window.available,
        )

    def serve_forever(self) -> None:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", self.port))
        server.listen(4)
        self._server = server
        self._running = True
        print(f"[agente] escuchando en 0.0.0.0:{self.port} · IP {local_ip()}")
        try:
            while self._running:
                try:
                    client, address = server.accept()
                except OSError:
                    break
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                client.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                self._release_stale_session()
                with self._session_lock:
                    if self._session is not None and self._session.trusted and self._session.alive:
                        try:
                            wire = Wire(client)
                            wire.send_json(
                                Type.ERROR,
                                data={
                                    "reason": "Otra sesión emparejada está activa. "
                                    "Cierra la app en el otro dispositivo e inténtalo de nuevo."
                                },
                            )
                            wire.close()
                        except (WireError, OSError):
                            pass
                        continue
                    session = Session(self, client, address)
                    self._session = session
                threading.Thread(
                    target=session.run, name=f"session-{address[0]}", daemon=True
                ).start()
        finally:
            self._running = False
            try:
                server.close()
            except OSError:
                pass
            self.shutdown()

    def _release_stale_session(self, grace: float = 0.8) -> None:
        """Espera a que una sesión anterior muera (reconexión tras un corte)."""

        deadline = time.time() + grace
        while time.time() < deadline:
            with self._session_lock:
                session = self._session
            if session is None or not session.trusted or not session.alive:
                return
            if not session.probe_alive():
                session.close("socket muerto")
                return
            time.sleep(0.05)

    def shutdown(self) -> None:
        self._running = False
        self.vpn.stop()
        self.audio.close()
        self.screen.close()
        self.camera_window.stop()
        with self._session_lock:
            session = self._session
            self._session = None
        if session is not None:
            session.close()

    def stop(self) -> None:
        self._running = False
        if self._server is not None:
            try:
                self._server.close()
            except OSError:
                pass

    def clear_session(self, session: "Session") -> None:
        with self._session_lock:
            if self._session is session:
                self._session = None


class Session:
    def __init__(self, server: AgentServer, sock: socket.socket, address) -> None:
        self.server = server
        self.state = server.state
        self.sock = sock
        self.address = address
        self.wire = Wire(sock)
        self.trusted = False
        self.alive = True
        self.device: dict = {}
        self.device_fp = ""
        self._stats_thread: Optional[threading.Thread] = None
        self._stats_stop = threading.Event()
        self._stats_interval = 1.0
        self._mirror_thread: Optional[threading.Thread] = None
        self._mirror_stop = threading.Event()
        self._mirror_quality = 55
        self._mirror_fps = 8
        self._mirror_scale = 0.6
        self._upload: Optional[dict] = None
        self._scan_thread: Optional[threading.Thread] = None

    # ------------------------------------------------------------------ bucle

    def run(self) -> None:
        print(f"[agente] conexión desde {self.address[0]}")
        try:
            while self.alive:
                frame = self.wire.read_frame()
                if frame is None:
                    break
                if frame[0] == "json":
                    if not self._handle_control(frame[1]):
                        break
                elif frame[0] == "binary":
                    self._handle_binary(frame[1], frame[2], frame[3])
            reason = "cerrada por el teléfono"
        except (WireError, OSError) as error:
            reason = str(error) or "enlace cerrado"
        except Exception as error:  # noqa: BLE001 - un fallo no debe matar el agente
            reason = f"error interno: {error}"
            traceback.print_exc()
        finally:
            self.close(reason)

    def close(self, reason: str = "cerrada") -> None:
        if not self.alive:
            return
        self.alive = False
        self._stats_stop.set()
        self._mirror_stop.set()
        self.server.audio.stop_playback()
        self.server.audio.stop_capture()
        self.server.camera_window.stop()
        self.server.inputs.stop_capture()
        try:
            self._finish_upload(completed=False)
        except OSError:
            pass
        self.wire.close()
        self.server.clear_session(self)
        print(f"[agente] sesión finalizada con {self.address[0]} ({reason})")

    def probe_alive(self) -> bool:
        """Comprueba el socket sin consumir datos (para reconexiones)."""

        try:
            self.sock.setblocking(False)
            try:
                data = self.sock.recv(1, socket.MSG_PEEK)
            finally:
                self.sock.setblocking(True)
                self.sock.settimeout(None)
            return bool(data)
        except (BlockingIOError, InterruptedError):
            return True
        except OSError:
            return False

    # --------------------------------------------------------------- control

    def _handle_control(self, message: dict) -> bool:
        type_ = message.get("type", "")
        data = message.get("data") or {}
        message_id = message.get("id")
        try:
            if type_ == Type.HELLO:
                return self._on_hello(message_id, data)
            if not self.trusted:
                if type_ == Type.PAIR:
                    return self._on_pair(message_id, data)
                if type_ == Type.PING:
                    self._reply(Type.PONG, message_id)
                    return True
                self._reply(Type.ERROR, message_id, {"reason": "Empareja el dispositivo primero."})
                return True

            if type_ == Type.PING:
                self._reply(Type.PONG, message_id)
            elif type_ == Type.STATS_REQUEST:
                interval = float(data.get("interval_ms", 1000) or 1000) / 1000.0
                self._stats_interval = max(0.5, min(5.0, interval))
                self._start_stats()
                self._reply(Type.STATS, message_id, self.server.sampler.snapshot())
            elif type_ == Type.KEY:
                self._on_key(data, message_id)
            elif type_ == Type.TEXT:
                self._on_text(data, message_id)
            elif type_ == Type.MOUSE:
                self._on_mouse(data, message_id)
            elif type_ == Type.SCROLL:
                self._on_scroll(data, message_id)
            elif type_ == Type.CLIPBOARD:
                self._on_clipboard(data, message_id)
            elif type_ == Type.CAPTURE_START:
                self._on_capture_start(message_id)
            elif type_ == Type.CAPTURE_STOP:
                self.server.inputs.stop_capture()
                self._reply(Type.INPUT_ACK, message_id, {"capture": False})
            elif type_ == Type.MIRROR_START:
                self._on_mirror_start(message_id, data)
            elif type_ == Type.MIRROR_STOP:
                self._mirror_stop.set()
                self._reply(Type.INPUT_ACK, message_id, {"mirror": False})
            elif type_ in (Type.CAMERA_START,):
                self._on_camera_start(message_id, data)
            elif type_ in (Type.CAMERA_STOP,):
                self.server.camera_window.stop()
                self._reply(Type.INPUT_ACK, message_id, {"camera": False})
            elif type_ == Type.AUDIO_START:
                self._on_audio_start(message_id, data)
            elif type_ == Type.AUDIO_STOP:
                direction = str(data.get("direction", "mic"))
                if direction == "mic":
                    self.server.audio.stop_playback()
                else:
                    self.server.audio.stop_capture()
                self._reply(Type.INPUT_ACK, message_id, {"audio": False})
            elif type_ == Type.FILE_LIST:
                self._on_file_list(message_id, data)
            elif type_ == Type.FILE_PULL:
                self._on_file_pull(message_id, data)
            elif type_ == Type.FILE_START:
                self._on_upload_start(message_id, data)
            elif type_ == Type.FILE_END:
                self._reply(Type.FILE_END, message_id, self._finish_upload(completed=True) or {})
            elif type_ == Type.VPN_HELLO:
                port = self.server.vpn.start()
                self._reply(
                    Type.VPN_OPENED,
                    message_id,
                    {"host": local_ip(), "relay_port": port, "proto": "tcp"},
                )
            elif type_ == Type.VPN_CLOSE:
                self.server.vpn.stop()
                self._reply(Type.VPN_STATE, message_id, {"state": "closed", "relay_port": 0})
            elif type_ == Type.SCAN_START:
                self._on_scan(message_id, data)
            elif type_ in (Type.SCAN_STOP,):
                self._reply(Type.SCAN_RESULTS, message_id, {"hosts": []})
            elif type_ == Type.DISCONNECT:
                return False
            else:
                self._reply(Type.ERROR, message_id, {"reason": f"tipo desconocido: {type_}"})
        except Exception as error:  # noqa: BLE001
            traceback.print_exc()
            self._reply(Type.ERROR, message_id, {"reason": str(error)})
        return True

    def _reply(self, type_: str, message_id, data: Optional[dict] = None) -> None:
        try:
            self.wire.send_json(type_, id_=message_id, data=data or {})
        except WireError:
            self.alive = False

    # ----------------------------------------------------------- protocolo

    def _on_hello(self, message_id, data: dict) -> bool:
        device = data.get("device") or {}
        token = data.get("token")
        pin = device.get("pin") or data.get("pin")
        self.device = device
        self.device_fp = str(device.get("fp") or "")

        if self.state.verify_token(self.device_fp, token):
            self._confirm_pairing(message_id, reused_token=token)
            return True
        # Un PIN válido siempre permite (re)emparejar: sirve para recuperar el
        # acceso si el teléfono perdió el token, y lo controla quien ve la PC.
        if pin:
            ok, reason = self.state.verify_pin(str(pin))
            if ok:
                pairing = self.state.register_device(device)
                self._confirm_pairing(message_id, token_override=pairing.token)
                return True
            self._reply(
                Type.PAIR_FAIL,
                message_id,
                {"reason": reason, "attempts_left": self.state.attempts_left()},
            )
            return True
        self._reply(
            Type.PAIR_REQUIRED,
            message_id,
            {
                "agent": _agent_descriptor(self.state),
                "attempts_left": self.state.attempts_left(),
                "message": "Escribe el PIN que muestra el agente en la PC.",
            },
        )
        return True

    def _on_pair(self, message_id, data: dict) -> bool:
        device = data.get("device") or self.device
        self.device = device or self.device
        self.device_fp = str(self.device.get("fp") or self.device_fp)
        ok, reason = self.state.verify_pin(str(data.get("pin") or ""))
        if not ok:
            self._reply(
                Type.PAIR_FAIL,
                message_id,
                {"reason": reason, "attempts_left": self.state.attempts_left()},
            )
            return True
        pairing = self.state.register_device(self.device)
        self._confirm_pairing(message_id, token_override=pairing.token)
        print(f"[agente] emparejado con {pairing.name} ({pairing.fingerprint})")
        return True

    def _confirm_pairing(
        self,
        message_id,
        token_override: Optional[str] = None,
        reused_token: Optional[str] = None,
    ) -> None:
        pairing = self.state.pairing_for(self.device_fp)
        token = token_override or reused_token or (pairing.token if pairing else None)
        self.trusted = True
        self._reply(
            Type.HELLO_ACK if reused_token else Type.PAIR_OK,
            message_id,
            {
                "agent": _agent_descriptor(self.state),
                "capabilities": self.server.capabilities(),
                "token": token,
            },
        )
        print(f"[agente] sesión activa: {self.device.get('name', 'dispositivo')}")

    # -------------------------------------------------------------- entrada

    def _target_is_phone(self, data: dict) -> bool:
        return str(data.get("target", "pc")) == "phone"

    def _on_key(self, data: dict, message_id) -> None:
        if self._target_is_phone(data):
            self._reply(Type.INPUT_ACK, message_id, {"ignored": "target=phone"})
            return
        key = str(data.get("key") or "")
        modifiers = [str(item) for item in (data.get("modifiers") or [])]
        ok = self.server.inputs.press_key(key, modifiers) if key else False
        self._reply(Type.INPUT_ACK, message_id, {"ok": ok, "key": key})

    def _on_text(self, data: dict, message_id) -> None:
        if self._target_is_phone(data):
            self._reply(Type.INPUT_ACK, message_id, {"ignored": "target=phone"})
            return
        text = str(data.get("text") or "")
        ok = self.server.inputs.type_text(text)
        self._reply(Type.INPUT_ACK, message_id, {"ok": ok, "chars": len(text)})

    def _on_mouse(self, data: dict, message_id) -> None:
        if self._target_is_phone(data):
            self._reply(Type.INPUT_ACK, message_id, {"ignored": "target=phone"})
            return
        action = str(data.get("action") or "move")
        inputs = self.server.inputs
        ok = False
        if action == "move":
            if bool(data.get("absolute")):
                ok = inputs.move_pointer_absolute(float(data.get("x", 0.5)), float(data.get("y", 0.5)))
            else:
                ok = inputs.move_pointer(float(data.get("dx", 0)), float(data.get("dy", 0)))
        elif action in ("down", "up"):
            ok = inputs.button(str(data.get("button", "left")), action == "down")
        elif action == "click":
            ok = inputs.click(str(data.get("button", "left")))
        elif action == "double":
            ok = inputs.click(str(data.get("button", "left")), double=True)
        if action != "move":
            self._reply(Type.INPUT_ACK, message_id, {"ok": ok, "action": action})

    def _on_scroll(self, data: dict, message_id) -> None:
        dy = float(data.get("dy", 0))
        # La app envía píxeles; el backend espera "pasos" de rueda.
        steps = dy / 120.0 if str(data.get("unit", "px")) == "px" else dy
        ok = self.server.inputs.scroll(steps)
        self._reply(Type.INPUT_ACK, message_id, {"ok": ok})

    def _on_clipboard(self, data: dict, message_id) -> None:
        action = str(data.get("action") or "set")
        inputs = self.server.inputs
        if action == "get":
            text = inputs.clipboard_get() or ""
            self._reply(Type.CLIPBOARD, message_id, {"text": text, "action": "get"})
            return
        text = str(data.get("text") or "")
        ok = inputs.clipboard_set(text)
        if ok and action == "paste":
            inputs.press_key("paste")
        self._reply(Type.INPUT_ACK, message_id, {"ok": ok})

    # ------------------------------------------------- captura PC → móvil

    def _on_capture_start(self, message_id) -> None:
        started = self.server.inputs.start_capture(
            on_key=lambda event: self._forward_key(event),
            on_mouse=lambda event: self._forward_mouse(event),
        )
        self._reply(Type.INPUT_ACK, message_id, {"capture": started})
        if not started:
            self._reply(
                Type.ERROR,
                None,
                {"reason": "La captura local necesita pynput y una sesión gráfica."},
            )

    def _forward_key(self, event: dict) -> None:
        if not self.alive or not self.trusted:
            return
        try:
            if event.get("type") == "text":
                self.wire.send_json(
                    Type.TEXT,
                    data={"target": "phone", "text": str(event.get("text", ""))},
                )
            else:
                self.wire.send_json(
                    Type.KEY,
                    data={"target": "phone", "key": str(event.get("key", ""))},
                )
        except WireError:
            self.alive = False

    def _forward_mouse(self, event: dict) -> None:
        if not self.alive or not self.trusted:
            return
        width, height = screen_size()
        try:
            if event.get("type") == "scroll":
                self.wire.send_json(
                    Type.SCROLL,
                    data={"target": "phone", "dy": event.get("dy", 0), "unit": "steps"},
                )
            elif event.get("action") == "down":
                x = float(event.get("x", 0)) / max(1, width)
                y = float(event.get("y", 0)) / max(1, height)
                self.wire.send_json(
                    Type.GESTURE,
                    data={
                        "target": "phone",
                        "action": "tap",
                        "x": x,
                        "y": y,
                        "x2": x,
                        "y2": y,
                        "duration": 60,
                    },
                )
        except WireError:
            self.alive = False

    # ------------------------------------------------------------- métricas

    def _start_stats(self) -> None:
        if self._stats_thread is not None and self._stats_thread.is_alive():
            return
        self._stats_stop.clear()

        def loop() -> None:
            while self.alive and not self._stats_stop.is_set():
                try:
                    snapshot = self.server.sampler.snapshot()
                    self.wire.send_json(Type.STATS, data=snapshot)
                except WireError:
                    return
                except Exception:  # noqa: BLE001
                    return
                self._stats_stop.wait(self._stats_interval)

        self._stats_thread = threading.Thread(target=loop, name="stats", daemon=True)
        self._stats_thread.start()

    # --------------------------------------------------------------- espejo

    def _on_mirror_start(self, message_id, data: dict) -> None:
        if not self.server.screen.available:
            self._reply(
                Type.ERROR,
                message_id,
                {"reason": "Instala Pillow y mss (pip install pillow mss) para ver la pantalla."},
            )
            return
        self._mirror_quality = int(data.get("quality", 55) or 55)
        self._mirror_fps = max(1, min(15, int(data.get("fps", 8) or 8)))
        self._mirror_scale = max(0.2, min(1.0, float(data.get("scale", 0.6) or 0.6)))
        size = self.server.screen.size()
        self._reply(
            Type.MIRROR_INFO,
            message_id,
            {
                "width": size[0] if size else 0,
                "height": size[1] if size else 0,
                "fps": self._mirror_fps,
                "format": "jpeg",
            },
        )
        if self._mirror_thread is not None and self._mirror_thread.is_alive():
            return
        self._mirror_stop.clear()

        def loop() -> None:
            interval = 1.0 / self._mirror_fps
            while self.alive and not self._mirror_stop.is_set():
                started = time.time()
                jpeg = self.server.screen.capture_jpeg(self._mirror_quality, self._mirror_scale)
                if jpeg:
                    try:
                        self.wire.send_binary("mirror", protocol.KIND_FRAME, jpeg)
                    except WireError:
                        return
                remaining = interval - (time.time() - started)
                if remaining > 0:
                    self._mirror_stop.wait(remaining)

        self._mirror_thread = threading.Thread(target=loop, name="mirror", daemon=True)
        self._mirror_thread.start()

    # --------------------------------------------------------------- cámara

    def _on_camera_start(self, message_id, data: dict) -> None:
        available = self.server.camera_window.available
        if available:
            self.server.camera_window.start()
        self._reply(
            Type.CAMERA_INFO,
            message_id,
            {
                "window": available,
                "width": data.get("width", 0),
                "height": data.get("height", 0),
                "reason": "" if available else "Instala Pillow y tkinter para ver la cámara.",
            },
        )

    # ---------------------------------------------------------------- audio

    def _on_audio_start(self, message_id, data: dict) -> None:
        direction = str(data.get("direction") or "mic")
        rate = int(data.get("sample_rate", 48000) or 48000)
        if direction == "mic":
            ok = self.server.audio.start_playback(rate, 1)
            self._reply(
                Type.AUDIO_CONFIG,
                message_id,
                {"direction": "mic", "sample_rate": rate, "channels": 1, "ok": ok},
            )
            return
        channels = 2

        def send(pcm: bytes) -> None:
            try:
                self.wire.send_binary("speaker", protocol.KIND_AUDIO, pcm)
            except WireError:
                self.server.audio.stop_capture()

        ok = self.server.audio.start_capture(send, rate)
        self._reply(
            Type.AUDIO_CONFIG,
            message_id,
            {"direction": "speaker", "sample_rate": rate, "channels": channels, "ok": ok},
        )

    # -------------------------------------------------------------- archivos

    def _roots(self) -> list[Path]:
        roots = [Path.home()]
        if platform.system() == "Windows":
            for letter in "CDEFGH":
                candidate = Path(f"{letter}:\\")
                if candidate.exists():
                    roots.append(candidate)
        else:
            for candidate in ("/media", "/mnt", "/Volumes"):
                path = Path(candidate)
                if path.exists():
                    roots.append(path)
        return roots

    def _safe_path(self, raw: str) -> Optional[Path]:
        text = (raw or "").strip()
        base = Path.home()
        if not text or text in ("~", "/", "\\"):
            return base
        if text in ("*", "roots"):
            return base
        candidate = Path(os.path.expanduser(text))
        if not candidate.is_absolute():
            candidate = base / candidate
        try:
            resolved = candidate.resolve()
        except OSError:
            return None
        for root in self._roots():
            try:
                if resolved == root.resolve() or root.resolve() in resolved.parents:
                    return resolved
            except OSError:
                continue
        return None

    def _on_file_list(self, message_id, data: dict) -> None:
        path = self._safe_path(str(data.get("path") or ""))
        if path is None or not path.is_dir():
            self._reply(Type.FILE_ERROR, message_id, {"reason": "Carpeta no accesible."})
            return
        entries = []
        try:
            for item in sorted(path.iterdir(), key=lambda entry: (not entry.is_dir(), entry.name.lower())):
                try:
                    stat = item.stat()
                except OSError:
                    continue
                entries.append(
                    {
                        "name": item.name,
                        "path": str(item),
                        "dir": item.is_dir(),
                        "size": 0 if item.is_dir() else int(stat.st_size),
                        "modified": int(stat.st_mtime * 1000),
                    }
                )
                if len(entries) >= 400:
                    break
        except OSError as error:
            self._reply(Type.FILE_ERROR, message_id, {"reason": str(error)})
            return
        self._reply(Type.FILE_LIST, message_id, {"path": str(path), "entries": entries})

    def _on_file_pull(self, message_id, data: dict) -> None:
        path = self._safe_path(str(data.get("path") or ""))
        if path is None or not path.is_file():
            self._reply(Type.FILE_ERROR, message_id, {"reason": "Archivo no accesible."})
            return
        try:
            size = path.stat().st_size
        except OSError as error:
            self._reply(Type.FILE_ERROR, message_id, {"reason": str(error)})
            return
        self._reply(
            Type.FILE_START,
            message_id,
            {"name": path.name, "size": size, "path": str(path)},
        )

        def stream() -> None:
            try:
                with path.open("rb") as handle:
                    while True:
                        chunk = handle.read(64 * 1024)
                        if not chunk:
                            break
                        self.wire.send_binary("files", protocol.KIND_FILE_CHUNK, chunk)
                marker = json.dumps({"end": True, "size": size}).encode("utf-8")
                self.wire.send_binary("files", protocol.KIND_CONTROL, marker)
            except (OSError, WireError):
                try:
                    self.wire.send_json(Type.FILE_ERROR, data={"reason": "La transferencia se cortó."})
                except WireError:
                    pass

        threading.Thread(target=stream, name="file-pull", daemon=True).start()

    def _on_upload_start(self, message_id, data: dict) -> None:
        name = Path(str(data.get("name") or "archivo")).name
        directory = Path.home() / UPLOAD_DIRNAME / "uploads"
        try:
            directory.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            self._reply(Type.FILE_ERROR, message_id, {"reason": str(error)})
            return
        target = directory / name
        try:
            handle = target.open("wb")
        except OSError as error:
            self._reply(Type.FILE_ERROR, message_id, {"reason": str(error)})
            return
        self._finish_upload(completed=False)
        self._upload = {
            "handle": handle,
            "path": target,
            "expected": int(data.get("size", 0) or 0),
            "received": 0,
        }
        self._reply(
            Type.FILE_START,
            message_id,
            {"name": name, "path": str(target), "size": int(data.get("size", 0) or 0)},
        )

    def _finish_upload(self, completed: bool) -> Optional[dict]:
        upload = self._upload
        if upload is None:
            return None
        self._upload = None
        try:
            upload["handle"].close()
        except OSError:
            pass
        path: Path = upload["path"]
        received = upload["received"]
        if completed:
            print(f"[agente] archivo recibido: {path} ({received} bytes)")
            return {"name": path.name, "size": received, "path": str(path)}
        if received == 0 and path.exists():
            try:
                path.unlink()
            except OSError:
                pass
        return None

    # ------------------------------------------------------------ escaneo LAN

    def _on_scan(self, message_id, data: dict) -> None:
        if self._scan_thread is not None and self._scan_thread.is_alive():
            self._reply(Type.ERROR, message_id, {"reason": "Ya hay un barrido en curso."})
            return
        deep = bool(data.get("deep"))

        def progress(percent: int) -> None:
            try:
                self.wire.send_json(Type.SCAN_PROGRESS, data={"percent": percent})
            except WireError:
                pass

        def run() -> None:
            try:
                hosts = scan_lan(deep=deep, progress=progress)
                self.wire.send_json(Type.SCAN_RESULTS, data={"hosts": hosts, "deep": deep})
            except Exception as error:  # noqa: BLE001
                try:
                    self.wire.send_json(Type.ERROR, data={"reason": f"Barrido: {error}"})
                except WireError:
                    pass

        self._scan_thread = threading.Thread(target=run, name="lan-scan", daemon=True)
        self._scan_thread.start()
        self._reply(Type.SCAN_PROGRESS, message_id, {"percent": 0, "started": True})

    # --------------------------------------------------------------- binario

    def _handle_binary(self, channel: str, kind: int, payload: bytes) -> None:
        if channel == "mic":
            self.server.audio.write_playback(payload)
        elif channel == "camera":
            self.server.camera_window.push(payload)
        elif channel == "files":
            if kind == protocol.KIND_CONTROL:
                try:
                    marker = json.loads(payload.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    return
                if marker.get("end"):
                    result = self._finish_upload(completed=True)
                    if result:
                        self._reply(Type.FILE_END, None, result)
                return
            upload = self._upload
            if upload is None:
                return
            try:
                upload["handle"].write(payload)
                upload["received"] += len(payload)
            except OSError as error:
                self._finish_upload(completed=False)
                self._reply(Type.FILE_ERROR, None, {"reason": str(error)})
        elif channel == "speaker":
            # Este canal solo se usa en sentido agente → teléfono.
            return
