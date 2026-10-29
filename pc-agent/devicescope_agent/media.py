"""Pantalla, cámara y audio del agente.

Todo es opcional y con degradación explícita:

* **Pantalla**: ``mss`` (rápido) o ``PIL.ImageGrab``; el JPEG necesita Pillow.
* **Cámara del teléfono**: ventana de vista previa con ``tkinter`` + Pillow.
* **Audio**: ``sounddevice`` para reproducir el micrófono del teléfono y para
  capturar la salida del sistema (WASAPI loopback en Windows, monitor de
  PipeWire/PulseAudio en Linux).
"""

from __future__ import annotations

import io
import platform
import queue
import threading
from typing import Callable, Optional

try:  # pragma: no cover - dependencias opcionales
    from PIL import Image
except Exception:  # noqa: BLE001
    Image = None

try:  # pragma: no cover
    import mss
except Exception:  # noqa: BLE001
    mss = None

try:  # pragma: no cover
    import sounddevice as sd
except Exception:  # noqa: BLE001
    sd = None

try:  # pragma: no cover
    import tkinter
except Exception:  # noqa: BLE001
    tkinter = None

SYSTEM = platform.system()


def capture_size() -> Optional[tuple[int, int]]:
    if mss is not None:
        try:
            with mss.mss() as grabber:
                monitor = grabber.monitors[1]
                return (int(monitor["width"]), int(monitor["height"]))
        except Exception:  # noqa: BLE001
            pass
    if Image is not None:
        try:
            from PIL import ImageGrab

            image = ImageGrab.grab()
            return image.size
        except Exception:  # noqa: BLE001
            pass
    return None


class ScreenStreamer:
    """Captura y comprime la pantalla principal en JPEG."""

    def __init__(self) -> None:
        self._available = mss is not None or Image is not None
        self._lock = threading.Lock()
        if mss is not None:
            try:
                self._grabber = mss.mss()
            except Exception:  # noqa: BLE001
                self._grabber = None
                if Image is None:
                    self._available = False
        else:
            self._grabber = None
            if Image is None:
                self._available = False

    @property
    def available(self) -> bool:
        return self._available and Image is not None

    def size(self) -> Optional[tuple[int, int]]:
        return capture_size()

    def capture_jpeg(self, quality: int = 55, scale: float = 0.6) -> Optional[bytes]:
        if not self.available or Image is None:
            return None
        with self._lock:
            image = self._grab(None)
            if image is None:
                return None
            try:
                if scale < 0.99:
                    width = max(320, int(image.width * scale))
                    height = max(200, int(image.height * scale))
                    image = image.resize((width, height))
                buffer = io.BytesIO()
                image.convert("RGB").save(buffer, format="JPEG", quality=max(20, min(90, quality)))
                return buffer.getvalue()
            except Exception:  # noqa: BLE001
                return None

    def _grab(self, _ignored):
        if self._grabber is not None:
            try:
                monitor = self._grabber.monitors[1]
                shot = self._grabber.grab(monitor)
                return Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
            except Exception:  # noqa: BLE001
                pass
        if Image is not None:
            try:
                from PIL import ImageGrab

                return ImageGrab.grab()
            except Exception:  # noqa: BLE001
                return None
        return None

    def close(self) -> None:
        if self._grabber is not None:
            try:
                self._grabber.close()
            except Exception:  # noqa: BLE001
                pass


class CameraWindow:
    """Ventana de vista previa para los fotogramas JPEG del teléfono."""

    def __init__(self, title: str = "DeviceScope · cámara del teléfono") -> None:
        self._available = tkinter is not None and Image is not None
        self._title = title
        self._queue: "queue.Queue[Optional[bytes]]" = queue.Queue(maxsize=4)
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._frames = 0

    @property
    def available(self) -> bool:
        return self._available

    def start(self) -> bool:
        if not self._available or self._running:
            return self._available
        self._running = True
        self._thread = threading.Thread(target=self._run, name="camera-window", daemon=True)
        self._thread.start()
        return True

    def push(self, jpeg: bytes) -> None:
        if not self._running:
            self.start()
        try:
            self._queue.put_nowait(jpeg)
        except queue.Full:
            try:
                self._queue.get_nowait()
                self._queue.put_nowait(jpeg)
            except queue.Empty:
                pass

    def stop(self) -> None:
        self._running = False
        try:
            self._queue.put_nowait(None)
        except queue.Full:
            pass

    def _run(self) -> None:
        try:
            root = tkinter.Tk()
        except Exception:  # noqa: BLE001 - sin pantalla gráfica
            self._available = False
            self._running = False
            return
        root.title(self._title)
        root.configure(bg="#101522")
        label = tkinter.Label(root, bg="#101522")
        label.pack(fill="both", expand=True)
        photo_holder: dict[str, object] = {}

        def tick() -> None:
            if not self._running:
                root.destroy()
                return
            try:
                frame = self._queue.get_nowait()
            except queue.Empty:
                frame = b""
            if frame is None:
                root.destroy()
                return
            if frame:
                try:
                    image = Image.open(io.BytesIO(frame)).convert("RGB")
                    width = min(960, image.width)
                    height = int(image.height * width / max(1, image.width))
                    image = image.resize((width, height))
                    photo = tkinter.PhotoImage(data=_png_bytes(image))
                    photo_holder["photo"] = photo
                    label.configure(image=photo)
                    self._frames += 1
                except Exception:  # noqa: BLE001
                    pass
            root.after(33, tick)

        try:
            tick()
            root.mainloop()
        finally:
            self._running = False

    @property
    def frames(self) -> int:
        return self._frames


def _png_bytes(image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class AudioBridge:
    """Reproduce el micrófono del teléfono y captura la salida del sistema."""

    def __init__(self) -> None:
        self._playback_stream = None
        self._capture_stream = None
        self._playback_lock = threading.Lock()
        self._capture_callback: Optional[Callable[[bytes], None]] = None
        self._loopback_device: Optional[int] = None

    @property
    def mic_available(self) -> bool:
        if sd is None:
            return False
        try:
            sd.query_devices(kind="output")
            return True
        except Exception:  # noqa: BLE001
            return False

    @property
    def speaker_available(self) -> bool:
        return sd is not None and self._find_loopback() is not None

    def _find_loopback(self) -> Optional[int]:
        if sd is None:
            return None
        if self._loopback_device is not None:
            return self._loopback_device
        try:
            devices = sd.query_devices()
        except Exception:  # noqa: BLE001
            return None
        keywords = ("monitor", "loopback", "stereo mix", "mezcla estéreo", "what u hear")
        for index, device in enumerate(devices):
            if device.get("max_input_channels", 0) <= 0:
                continue
            name = str(device.get("name", "")).lower()
            if any(keyword in name for keyword in keywords):
                self._loopback_device = index
                return index
        return None

    def start_playback(self, sample_rate: int = 48000, channels: int = 1) -> bool:
        if not self.mic_available:
            return False
        with self._playback_lock:
            if self._playback_stream is not None:
                return True
            try:
                self._playback_stream = sd.RawOutputStream(
                    samplerate=sample_rate,
                    channels=channels,
                    dtype="int16",
                    blocksize=int(sample_rate * 0.02),
                )
                self._playback_stream.start()
                return True
            except Exception:  # noqa: BLE001
                self._playback_stream = None
                return False

    def write_playback(self, pcm: bytes) -> None:
        stream = self._playback_stream
        if stream is None:
            return
        try:
            stream.write(pcm)
        except Exception:  # noqa: BLE001
            self.stop_playback()

    def stop_playback(self) -> None:
        with self._playback_lock:
            if self._playback_stream is not None:
                try:
                    self._playback_stream.stop()
                    self._playback_stream.close()
                except Exception:  # noqa: BLE001
                    pass
                self._playback_stream = None

    def start_capture(self, callback: Callable[[bytes], None], sample_rate: int = 48000) -> bool:
        device = self._find_loopback()
        if sd is None or device is None:
            return False
        if self._capture_stream is not None:
            return True
        self._capture_callback = callback
        try:
            channels = int(sd.query_devices(device).get("max_input_channels", 2))
            channels = 2 if channels >= 2 else 1

            def on_audio(indata, _frames, _time, _status) -> None:
                handler = self._capture_callback
                if handler is not None:
                    handler(bytes(indata))

            self._capture_stream = sd.RawInputStream(
                samplerate=sample_rate,
                channels=channels,
                dtype="int16",
                blocksize=int(sample_rate * 0.02),
                device=device,
                callback=on_audio,
            )
            self._capture_stream.start()
            return True
        except Exception:  # noqa: BLE001
            self._capture_stream = None
            return False

    def stop_capture(self) -> None:
        self._capture_callback = None
        if self._capture_stream is not None:
            try:
                self._capture_stream.stop()
                self._capture_stream.close()
            except Exception:  # noqa: BLE001
                pass
            self._capture_stream = None

    @property
    def capturing(self) -> bool:
        return self._capture_stream is not None

    def close(self) -> None:
        self.stop_playback()
        self.stop_capture()
