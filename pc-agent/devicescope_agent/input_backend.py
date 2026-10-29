"""Inyección de teclado y mouse en la PC, y captura para el sentido inverso.

Orden de preferencia del backend:

1. ``pynput`` — teclado y mouse completos, portátil (Windows/macOS/X11).
2. ``ctypes`` + ``SendInput`` — Windows sin dependencias.
3. ``xdotool`` — X11 sin dependencias.
4. ``osascript`` — macOS sin dependencias para teclas y clics.

Si ninguno está disponible, el agente arranca igual, informa
``keyboard: false``/``mouse: false`` y la app lo muestra en pantalla en lugar de
fingir que escribe.
"""

from __future__ import annotations

import ctypes
import platform
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass
from typing import Callable, Optional

SYSTEM = platform.system()

try:  # pragma: no cover - dependencia opcional
    from pynput import keyboard as pynput_keyboard
    from pynput import mouse as pynput_mouse

    HAS_PYNPUT = True
except Exception:  # noqa: BLE001 - sin servidor gráfico también falla
    pynput_keyboard = None
    pynput_mouse = None
    HAS_PYNPUT = False


# Nombre del protocolo -> atributo de pynput.
PYNPUT_KEYS = {
    "enter": "enter",
    "tab": "tab",
    "backspace": "backspace",
    "delete": "delete",
    "escape": "esc",
    "space": "space",
    "up": "up",
    "down": "down",
    "left": "left",
    "right": "right",
    "home": "home",
    "end": "end",
    "page_up": "page_up",
    "page_down": "page_down",
    "volume_up": "media_volume_up",
    "volume_down": "media_volume_down",
    "play_pause": "media_play_pause",
    "next_track": "media_next",
    "prev_track": "media_previous",
}

MODIFIER_KEYS = {
    "ctrl": "ctrl",
    "alt": "alt",
    "shift": "shift",
    "win": "cmd",
}

# VK de Windows para el backend ctypes.
WINDOWS_VK = {
    "enter": 0x0D,
    "tab": 0x09,
    "backspace": 0x08,
    "delete": 0x2E,
    "escape": 0x1B,
    "space": 0x20,
    "up": 0x26,
    "down": 0x28,
    "left": 0x25,
    "right": 0x27,
    "home": 0x24,
    "end": 0x23,
    "page_up": 0x21,
    "page_down": 0x22,
    "ctrl": 0x11,
    "alt": 0x12,
    "shift": 0x10,
    "win": 0x5B,
    "volume_up": 0xAF,
    "volume_down": 0xAE,
    "play_pause": 0xB3,
    "next_track": 0xB0,
    "prev_track": 0xB1,
}

SHORTCUTS = {
    "copy": ("ctrl", "c"),
    "paste": ("ctrl", "v"),
    "cut": ("ctrl", "x"),
    "undo": ("ctrl", "z"),
    "redo": ("ctrl", "y"),
    "save": ("ctrl", "s"),
    "select_all": ("ctrl", "a"),
    "alt_tab": ("alt", "tab"),
}


@dataclass
class InputCapabilities:
    keyboard: bool
    mouse: bool
    relative_mouse: bool
    capture: bool
    clipboard: bool
    backend: str


class InputBackend:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._keyboard_controller = None
        self._mouse_controller = None
        self._backend = "ninguno"
        self._capture_active = False
        self._keyboard_listener = None
        self._mouse_listener = None
        self._last_mouse = (0.0, 0.0)
        self._on_key: Optional[Callable[[dict], None]] = None
        self._on_mouse: Optional[Callable[[dict], None]] = None
        self._detect()

    # --------------------------------------------------------------- detección

    def _detect(self) -> None:
        if HAS_PYNPUT:
            try:
                self._keyboard_controller = pynput_keyboard.Controller()
                self._mouse_controller = pynput_mouse.Controller()
                self._backend = "pynput"
                return
            except Exception:  # noqa: BLE001
                self._keyboard_controller = None
                self._mouse_controller = None
        if SYSTEM == "Windows":
            self._backend = "windows-sendinput"
        elif SYSTEM == "Linux" and shutil.which("xdotool"):
            self._backend = "xdotool"
        elif SYSTEM == "Darwin" and shutil.which("osascript"):
            self._backend = "osascript"
        else:
            self._backend = "ninguno"

    def capabilities(self) -> InputCapabilities:
        usable = self._backend != "ninguno"
        return InputCapabilities(
            keyboard=usable,
            mouse=usable,
            relative_mouse=usable,
            capture=HAS_PYNPUT,
            clipboard=usable,
            backend=self._backend,
        )

    # ------------------------------------------------------------------ teclas

    def type_text(self, text: str) -> bool:
        if not text:
            return True
        with self._lock:
            if self._keyboard_controller is not None:
                try:
                    self._keyboard_controller.type(text)
                    return True
                except Exception:  # noqa: BLE001
                    return False
            if self._backend == "windows-sendinput":
                return _windows_type_text(text)
            if self._backend == "xdotool":
                try:
                    subprocess.run(
                        ["xdotool", "type", "--clearmodifiers", "--delay", "8", text],
                        timeout=10,
                        check=False,
                        capture_output=True,
                    )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            if self._backend == "osascript":
                escaped = text.replace("\\", "\\\\").replace('"', '\\"')
                return _run_osascript(
                    f'tell application "System Events" to keystroke "{escaped}"'
                )
            return False

    def press_key(self, key: str, modifiers: Optional[list[str]] = None) -> bool:
        modifiers = modifiers or []
        if key in SHORTCUTS:
            combo = list(SHORTCUTS[key])
            modifiers = modifiers or combo[:-1]
            key = combo[-1]
        with self._lock:
            if self._keyboard_controller is not None:
                return self._pynput_key(key, modifiers)
            if self._backend == "windows-sendinput":
                return _windows_key(key, modifiers)
            if self._backend == "xdotool":
                names = [MODIFIER_KEYS.get(mod, mod) for mod in modifiers]
                chord = "+".join(names + [key.replace("page_up", "Prior").replace("page_down", "Next")])
                try:
                    subprocess.run(
                        ["xdotool", "key", "--clearmodifiers", chord],
                        timeout=5,
                        check=False,
                        capture_output=True,
                    )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            if self._backend == "osascript":
                mapping = {
                    "enter": "return",
                    "tab": "tab",
                    "space": "space",
                    "escape": "escape",
                    "backspace": "delete",
                }
                name = mapping.get(key, key)
                command = f'tell application "System Events" to key code {_mac_keycode(key)}'
                if name not in mapping:
                    command = f'tell application "System Events" to keystroke "{name}"'
                return _run_osascript(command)
            return False

    def _pynput_key(self, key: str, modifiers: list[str]) -> bool:
        key_object = None
        if len(key) == 1:
            key_object = key
        else:
            attribute = PYNPUT_KEYS.get(key)
            if attribute:
                key_object = getattr(pynput_keyboard.Key, attribute, None)
        if key_object is None:
            # Atajo con letra final ("c", "z", …).
            if len(key) == 1:
                key_object = key
            else:
                return False
        pressed = []
        try:
            for modifier in modifiers:
                attribute = MODIFIER_KEYS.get(modifier)
                if not attribute:
                    continue
                key_mod = getattr(pynput_keyboard.Key, attribute, None)
                if key_mod is None:
                    continue
                self._keyboard_controller.press(key_mod)
                pressed.append(key_mod)
            self._keyboard_controller.press(key_object)
            self._keyboard_controller.release(key_object)
            return True
        except Exception:  # noqa: BLE001
            return False
        finally:
            for key_mod in reversed(pressed):
                try:
                    self._keyboard_controller.release(key_mod)
                except Exception:  # noqa: BLE001
                    pass

    # ------------------------------------------------------------------ mouse

    def move_pointer(self, dx: float, dy: float) -> bool:
        with self._lock:
            if self._mouse_controller is not None:
                try:
                    self._mouse_controller.move(dx, dy)
                    return True
                except Exception:  # noqa: BLE001
                    return False
            if self._backend == "windows-sendinput":
                return _windows_mouse_move(int(dx), int(dy))
            if self._backend == "xdotool":
                try:
                    subprocess.run(
                        ["xdotool", "mousemove_relative", "--", str(int(dx)), str(int(dy))],
                        timeout=3,
                        check=False,
                        capture_output=True,
                    )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            # macOS no permite mover el puntero de forma relativa sin permisos extra.
            return False

    def move_pointer_absolute(self, x: float, y: float) -> bool:
        """``x``/``y`` normalizados 0..1 sobre la pantalla principal."""

        width, height = screen_size()
        target_x = max(0, min(width - 1, int(x * width)))
        target_y = max(0, min(height - 1, int(y * height)))
        with self._lock:
            if self._mouse_controller is not None:
                try:
                    self._mouse_controller.position = (target_x, target_y)
                    return True
                except Exception:  # noqa: BLE001
                    return False
            if self._backend == "windows-sendinput":
                return _windows_mouse_absolute(target_x, target_y)
            if self._backend == "xdotool":
                try:
                    subprocess.run(
                        ["xdotool", "mousemove", str(target_x), str(target_y)],
                        timeout=3,
                        check=False,
                        capture_output=True,
                    )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            return False

    def button(self, name: str, down: bool) -> bool:
        button = _button_object(name)
        with self._lock:
            if self._mouse_controller is not None and button is not None:
                try:
                    if down:
                        self._mouse_controller.press(button)
                    else:
                        self._mouse_controller.release(button)
                    return True
                except Exception:  # noqa: BLE001
                    return False
            if self._backend == "windows-sendinput":
                return _windows_mouse_button(name, down)
            if self._backend == "xdotool":
                mapping = {"left": "1", "middle": "2", "right": "3"}
                action = "mousedown" if down else "mouseup"
                try:
                    subprocess.run(
                        ["xdotool", action, mapping.get(name, "1")],
                        timeout=3,
                        check=False,
                        capture_output=True,
                    )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            return False

    def click(self, name: str = "left", double: bool = False) -> bool:
        count = 2 if double else 1
        with self._lock:
            if self._mouse_controller is not None:
                button = _button_object(name)
                if button is None:
                    return False
                try:
                    self._mouse_controller.click(button, count)
                    return True
                except Exception:  # noqa: BLE001
                    return False
        for _ in range(count):
            if not self.button(name, True):
                return False
            time.sleep(0.03)
            if not self.button(name, False):
                return False
            time.sleep(0.05 if double else 0.0)
        return True

    def scroll(self, dy: float) -> bool:
        steps = int(dy) if abs(dy) >= 1 else (1 if dy > 0 else -1 if dy < 0 else 0)
        if steps == 0:
            return True
        with self._lock:
            if self._mouse_controller is not None:
                try:
                    self._mouse_controller.scroll(0, steps)
                    return True
                except Exception:  # noqa: BLE001
                    return False
            if self._backend == "windows-sendinput":
                return _windows_scroll(steps)
            if self._backend == "xdotool":
                button = "4" if steps > 0 else "5"
                try:
                    for _ in range(min(10, abs(steps))):
                        subprocess.run(
                            ["xdotool", "click", button],
                            timeout=2,
                            check=False,
                            capture_output=True,
                        )
                    return True
                except (OSError, subprocess.SubprocessError):
                    return False
            return False

    # ------------------------------------------------------------ portapapeles

    def clipboard_get(self) -> Optional[str]:
        for command in (["wl-paste", "-n"], ["xclip", "-selection", "clipboard", "-o"], ["xsel", "-b"]):
            if shutil.which(command[0]) is None:
                continue
            try:
                output = subprocess.run(command, capture_output=True, text=True, timeout=2)
                if output.returncode == 0:
                    return output.stdout
            except (OSError, subprocess.SubprocessError):
                continue
        if SYSTEM == "Darwin" and shutil.which("pbpaste"):
            try:
                return subprocess.run(["pbpaste"], capture_output=True, text=True, timeout=2).stdout
            except (OSError, subprocess.SubprocessError):
                return None
        if SYSTEM == "Windows":
            return _windows_clipboard_get()
        return None

    def clipboard_set(self, text: str) -> bool:
        for command in (["wl-copy"], ["xclip", "-selection", "clipboard"], ["xsel", "-b", "-i"]):
            if shutil.which(command[0]) is None:
                continue
            try:
                subprocess.run(command, input=text, text=True, timeout=2, check=False)
                return True
            except (OSError, subprocess.SubprocessError):
                continue
        if SYSTEM == "Darwin" and shutil.which("pbcopy"):
            try:
                subprocess.run(["pbcopy"], input=text, text=True, timeout=2, check=False)
                return True
            except (OSError, subprocess.SubprocessError):
                return False
        if SYSTEM == "Windows":
            return _windows_clipboard_set(text)
        return False

    # --------------------------------------------------------- captura local

    def start_capture(
        self,
        on_key: Callable[[dict], None],
        on_mouse: Callable[[dict], None],
    ) -> bool:
        if not HAS_PYNPUT:
            return False
        if self._capture_active:
            return True
        self._on_key = on_key
        self._on_mouse = on_mouse
        try:
            self._keyboard_listener = pynput_keyboard.Listener(
                on_press=self._handle_key_press,
            )
            self._mouse_listener = pynput_mouse.Listener(
                on_move=self._handle_mouse_move,
                on_click=self._handle_mouse_click,
                on_scroll=self._handle_mouse_scroll,
            )
            self._keyboard_listener.start()
            self._mouse_listener.start()
            self._capture_active = True
            return True
        except Exception:  # noqa: BLE001
            self.stop_capture()
            return False

    def stop_capture(self) -> None:
        self._capture_active = False
        for listener in (self._keyboard_listener, self._mouse_listener):
            if listener is not None:
                try:
                    listener.stop()
                except Exception:  # noqa: BLE001
                    pass
        self._keyboard_listener = None
        self._mouse_listener = None

    @property
    def capturing(self) -> bool:
        return self._capture_active

    def _handle_key_press(self, key) -> None:
        callback = self._on_key
        if callback is None or not self._capture_active:
            return
        try:
            char = getattr(key, "char", None)
            if char:
                callback({"type": "text", "text": char})
                return
            name = getattr(key, "name", None)
            if not name:
                return
            reversed_map = {value: key_name for key_name, value in PYNPUT_KEYS.items()}
            wire = reversed_map.get(name, name)
            callback({"type": "key", "key": wire})
        except Exception:  # noqa: BLE001
            pass

    def _handle_mouse_move(self, x: int, y: int) -> None:
        callback = self._on_mouse
        if callback is None or not self._capture_active:
            return
        last_x, last_y = self._last_mouse
        self._last_mouse = (x, y)
        if last_x == 0.0 and last_y == 0.0:
            return
        dx = x - last_x
        dy = y - last_y
        if abs(dx) < 1 and abs(dy) < 1:
            return
        callback({"type": "mouse", "action": "move", "dx": dx, "dy": dy})

    def _handle_mouse_click(self, x: int, y: int, button, pressed: bool) -> None:
        callback = self._on_mouse
        if callback is None or not self._capture_active:
            return
        name = getattr(button, "name", "left")
        callback(
            {
                "type": "mouse",
                "action": "down" if pressed else "up",
                "button": name,
                "x": x,
                "y": y,
            }
        )

    def _handle_mouse_scroll(self, x: int, y: int, dx: int, dy: int) -> None:
        callback = self._on_mouse
        if callback is None or not self._capture_active:
            return
        callback({"type": "scroll", "dy": dy})


def _button_object(name: str):
    if pynput_mouse is None:
        return None
    return {
        "left": pynput_mouse.Button.left,
        "right": pynput_mouse.Button.right,
        "middle": pynput_mouse.Button.middle,
    }.get(name, pynput_mouse.Button.left)


def _run_osascript(script: str) -> bool:
    try:
        result = subprocess.run(
            ["osascript", "-e", script],
            capture_output=True,
            text=True,
            timeout=4,
        )
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def _mac_keycode(key: str) -> int:
    return {
        "enter": 36,
        "tab": 48,
        "space": 49,
        "escape": 53,
        "backspace": 51,
        "delete": 117,
        "up": 126,
        "down": 125,
        "left": 123,
        "right": 124,
        "home": 115,
        "end": 119,
        "page_up": 116,
        "page_down": 121,
    }.get(key, 0)


def screen_size() -> tuple[int, int]:
    return _os_screen()


def _os_screen() -> tuple[int, int]:
    if SYSTEM == "Windows":
        try:
            user32 = ctypes.windll.user32
            user32.SetProcessDPIAware()
            return (user32.GetSystemMetrics(0), user32.GetSystemMetrics(1))
        except Exception:  # noqa: BLE001
            return (1920, 1080)
    if SYSTEM == "Darwin":
        try:
            output = subprocess.run(
                ["system_profiler", "SPDisplaysDataType"],
                capture_output=True,
                text=True,
                timeout=3,
            )
            for line in output.stdout.splitlines():
                if "Resolution" in line:
                    parts = line.split()
                    width = int(parts[1])
                    height = int(parts[3])
                    return (width, height)
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
    if SYSTEM == "Linux":
        try:
            output = subprocess.run(["xdotool", "getdisplaygeometry"], capture_output=True, text=True, timeout=2)
            if output.returncode == 0:
                width, height = output.stdout.split()
                return (int(width), int(height))
        except (OSError, ValueError, subprocess.SubprocessError):
            pass
        try:
            from .screen import capture_size

            size = capture_size()
            if size:
                return size
        except Exception:  # noqa: BLE001
            pass
    return (1920, 1080)


# --------------------------------------------------------- backend de Windows


def _windows_type_text(text: str) -> bool:
    try:
        user32 = ctypes.windll.user32
        for char in text:
            code = ord(char)
            user32.keybd_event(0, code, 0x0004, 0)  # KEYEVENTF_UNICODE
            user32.keybd_event(0, code, 0x0004 | 0x0002, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_key(key: str, modifiers: list[str]) -> bool:
    try:
        user32 = ctypes.windll.user32
        pressed = []
        for modifier in modifiers:
            vk = WINDOWS_VK.get(modifier)
            if vk:
                user32.keybd_event(vk, 0, 0, 0)
                pressed.append(vk)
        vk = WINDOWS_VK.get(key)
        if vk is None and len(key) == 1:
            vk = ord(key.upper())
        if vk is None:
            return False
        user32.keybd_event(vk, 0, 0, 0)
        user32.keybd_event(vk, 0, 0x0002, 0)
        for code in reversed(pressed):
            user32.keybd_event(code, 0, 0x0002, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_mouse_move(dx: int, dy: int) -> bool:
    try:
        ctypes.windll.user32.mouse_event(0x0001, dx, dy, 0, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_mouse_absolute(x: int, y: int) -> bool:
    try:
        ctypes.windll.user32.SetCursorPos(int(x), int(y))
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_mouse_button(name: str, down: bool) -> bool:
    flags = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010), "middle": (0x0020, 0x0040)}
    pair = flags.get(name)
    if pair is None:
        return False
    try:
        ctypes.windll.user32.mouse_event(pair[0] if down else pair[1], 0, 0, 0, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_scroll(steps: int) -> bool:
    try:
        ctypes.windll.user32.mouse_event(0x0800, 0, 0, int(steps) * 120, 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def _windows_clipboard_get() -> Optional[str]:
    try:
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        if not user32.OpenClipboard(0):
            return None
        try:
            handle = user32.GetClipboardData(13)  # CF_UNICODETEXT
            if not handle:
                return None
            pointer = kernel32.GlobalLock(handle)
            if not pointer:
                return None
            try:
                return ctypes.wstring_at(pointer)
            finally:
                kernel32.GlobalUnlock(handle)
        finally:
            user32.CloseClipboard()
    except Exception:  # noqa: BLE001
        return None


def _windows_clipboard_set(text: str) -> bool:
    try:
        user32 = ctypes.windll.user32
        kernel32 = ctypes.windll.kernel32
        size = (len(text) + 1) * ctypes.sizeof(ctypes.c_wchar)
        handle = kernel32.GlobalAlloc(0x0042, size)
        if not handle:
            return False
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            return False
        ctypes.memmove(pointer, ctypes.create_unicode_buffer(text), size)
        kernel32.GlobalUnlock(handle)
        if not user32.OpenClipboard(0):
            return False
        try:
            user32.EmptyClipboard()
            user32.SetClipboardData(13, handle)
        finally:
            user32.CloseClipboard()
        return True
    except Exception:  # noqa: BLE001
        return False
