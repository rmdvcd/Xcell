"""Estado persistente del agente: identidad, PIN y tokens de emparejamiento.

Todo se guarda en ``~/.devicescope/agent.json`` con permisos 0600. No se envía
nada a ninguna red externa: los tokens solo sirven en la LAN.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import platform
import secrets
import socket
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

CONFIG_DIR = Path(os.environ.get("DEVICESCOPE_HOME", Path.home() / ".devicescope"))
CONFIG_FILE = CONFIG_DIR / "agent.json"

MAX_PAIR_ATTEMPTS = 5
LOCKOUT_SECONDS = 60


def _machine_fingerprint() -> str:
    seed = "|".join(
        [
            platform.system(),
            platform.node(),
            platform.machine(),
            str(secrets.token_hex(8)) if not CONFIG_FILE.exists() else "stable",
        ]
    )
    digest = hashlib.sha256(seed.encode("utf-8")).hexdigest().upper()
    return "PC-" + digest[:8]


def local_ip() -> str:
    """IP de la interfaz que sale a la LAN (no abre conexiones reales)."""

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.168.1.1", 9))
        return sock.getsockname()[0]
    except OSError:
        try:
            return socket.gethostbyname(socket.gethostname())
        except OSError:
            return "127.0.0.1"
    finally:
        sock.close()


@dataclass
class Pairing:
    fingerprint: str
    name: str
    token: str
    platform: str
    paired_at: float = field(default_factory=time.time)
    last_seen: float = field(default_factory=time.time)


class AgentState:
    def __init__(self, name: Optional[str] = None, pin: Optional[str] = None) -> None:
        self.lock = threading.RLock()
        self.name = name or platform.node() or "PC"
        self.pin = pin or f"{secrets.randbelow(1_000_000):06d}"
        self.pairings: dict[str, Pairing] = {}
        self.failed_attempts = 0
        self.locked_until = 0.0
        self.fingerprint = ""
        self._load()

    # ------------------------------------------------------------------ carga

    def _load(self) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        if CONFIG_FILE.exists():
            try:
                data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                data = {}
            self.fingerprint = data.get("fingerprint") or ""
            self.name = data.get("name") or self.name
            for entry in data.get("pairings", []):
                try:
                    pairing = Pairing(
                        fingerprint=entry["fingerprint"],
                        name=entry.get("name", "Dispositivo"),
                        token=entry["token"],
                        platform=entry.get("platform", "Android"),
                        paired_at=entry.get("paired_at", time.time()),
                        last_seen=entry.get("last_seen", time.time()),
                    )
                except KeyError:
                    continue
                self.pairings[pairing.fingerprint] = pairing
        if not self.fingerprint:
            self.fingerprint = _machine_fingerprint()
        self._save()

    def _save(self) -> None:
        payload = {
            "name": self.name,
            "fingerprint": self.fingerprint,
            "pairings": [
                {
                    "fingerprint": p.fingerprint,
                    "name": p.name,
                    "token": p.token,
                    "platform": p.platform,
                    "paired_at": p.paired_at,
                    "last_seen": p.last_seen,
                }
                for p in self.pairings.values()
            ],
        }
        tmp = CONFIG_FILE.with_suffix(".tmp")
        try:
            tmp.write_text(json.dumps(payload, indent=2), encoding="utf-8")
            os.chmod(tmp, 0o600)
            tmp.replace(CONFIG_FILE)
        except OSError as error:  # pragma: no cover - solo si el FS es de solo lectura
            print(f"[agente] no se pudo guardar la configuración: {error}")

    # ------------------------------------------------------------ validación

    def verify_token(self, device_fp: str, token: Optional[str]) -> bool:
        if not device_fp or not token:
            return False
        with self.lock:
            pairing = self.pairings.get(device_fp)
            if pairing is None:
                return False
            if not hmac.compare_digest(pairing.token, token):
                return False
            pairing.last_seen = time.time()
            self._save()
            return True

    def pairing_for(self, device_fp: str) -> Optional[Pairing]:
        with self.lock:
            return self.pairings.get(device_fp)

    def attempts_left(self) -> int:
        return max(0, MAX_PAIR_ATTEMPTS - self.failed_attempts)

    def locked(self) -> bool:
        return time.time() < self.locked_until

    def lockout_remaining(self) -> int:
        return max(0, int(self.locked_until - time.time()))

    def verify_pin(self, pin: Optional[str]) -> tuple[bool, str]:
        with self.lock:
            if self.locked():
                return False, f"Demasiados intentos. Espera {self.lockout_remaining()} s."
            if not pin:
                return False, "Falta el PIN."
            if not hmac.compare_digest(pin.strip(), self.pin):
                self.failed_attempts += 1
                if self.failed_attempts >= MAX_PAIR_ATTEMPTS:
                    self.locked_until = time.time() + LOCKOUT_SECONDS
                    self.failed_attempts = 0
                    return False, f"PIN incorrecto. Bloqueado {LOCKOUT_SECONDS} s."
                return False, "PIN incorrecto."
            self.failed_attempts = 0
            return True, ""

    def register_device(self, device: dict) -> Pairing:
        device_fp = str(device.get("fp") or "DS-DESCONOCIDO")
        with self.lock:
            token = secrets.token_hex(24)
            pairing = Pairing(
                fingerprint=device_fp,
                name=str(device.get("name") or "Teléfono"),
                token=token,
                platform=str(device.get("platform") or "Android"),
            )
            self.pairings[device_fp] = pairing
            self._save()
            return pairing

    def revoke(self, device_fp: str) -> bool:
        with self.lock:
            removed = self.pairings.pop(device_fp, None)
            if removed:
                self._save()
            return removed is not None

    def rotate_pin(self) -> str:
        with self.lock:
            self.pin = f"{secrets.randbelow(1_000_000):06d}"
            self.failed_attempts = 0
            self.locked_until = 0.0
            return self.pin

    def summary(self) -> dict:
        with self.lock:
            return {
                "name": self.name,
                "fingerprint": self.fingerprint,
                "devices": [
                    {"name": p.name, "fingerprint": p.fingerprint, "platform": p.platform}
                    for p in self.pairings.values()
                ],
            }
