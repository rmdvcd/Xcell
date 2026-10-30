#!/usr/bin/env python3
"""Agente de PC del módulo PC-Móvil de DeviceScope.

Uso rápido::

    python agent.py                # arranca con el PIN que se muestra
    python agent.py --pin 123456    # PIN fijo para una demo
    python agent.py --diagnose      # comprueba qué herramientas funcionan
    python agent.py --devices       # equipos emparejados
    python agent.py --revoke DS-XXXX
    python agent.py --rotate-pin

Requisitos: Python 3.9+ y nada más para arrancar. Las herramientas completas
dependen de paquetes opcionales (ver README.md):

* ``psutil`` — métricas de CPU/RAM/procesos y batería.
* ``pillow`` + ``mss`` — espejo de pantalla y ventana de cámara.
* ``pynput`` — teclado y mouse (Windows/macOS/X11) y captura local.
* ``sounddevice`` — micrófono remoto y audio de la PC al teléfono.
"""

from __future__ import annotations

import argparse
import signal
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from devicescope_agent import protocol  # noqa: E402
from devicescope_agent.discovery import DiscoveryResponder  # noqa: E402
from devicescope_agent.server import AGENT_VERSION, AgentServer  # noqa: E402
from devicescope_agent.state import AgentState, CONFIG_FILE, local_ip  # noqa: E402


def print_banner(state: AgentState, port: int) -> None:
    line = "─" * 62
    print(line)
    print(" DeviceScope · agente PC-Móvil")
    print(line)
    print(f" Equipo      : {state.name}")
    print(f" Huella      : {state.fingerprint}")
    print(f" Dirección   : {local_ip()}:{port}")
    print(f" PIN         : {state.pin}   (cámbialo con --rotate-pin)")
    print(f" Configuración: {CONFIG_FILE}")
    print(line)
    print(" En el teléfono: PC-Móvil → «Buscar en la red local» y empareja con el PIN.")
    print(" Ctrl+C para detener el agente.")
    print(line)


def print_capabilities(server: AgentServer) -> None:
    caps = server.capabilities()
    print("Capacidades detectadas")
    print("─" * 62)
    for key in (
        "keyboard",
        "mouse",
        "screen_mirror",
        "mic",
        "speaker",
        "camera",
        "clipboard",
        "files",
        "vpn",
        "lan_scan",
    ):
        print(f"  {'sí' if caps.get(key) else 'no ':>3}  {key}")
    print(f"  backend de entrada: {caps.get('input_backend')}")
    print(f"  captura PC→móvil  : {'sí' if caps.get('capture') else 'no'}")
    print("─" * 62)
    if not caps.get("screen_mirror"):
        print("  · Instala pillow y mss para el espejo de pantalla.")
    if caps.get("input_backend") == "ninguno":
        print("  · Instala pynput o xdotool para controlar teclado y mouse.")
    if not caps.get("mic") or not caps.get("speaker"):
        print("  · Instala sounddevice para el audio bidireccional.")
    print()


def configure_console() -> None:
    """Fuerza UTF-8 en la salida para no romper en consolas de Windows.

    ``cmd.exe`` suele usar cp850/cp1252: los caracteres de las cajas y las
    tildes del informe harían saltar ``UnicodeEncodeError`` con la salida
    redirigida. Se degrada a ``?`` en vez de abortar.
    """

    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (OSError, ValueError):
            pass


def main() -> int:
    configure_console()
    parser = argparse.ArgumentParser(description="Agente PC-Móvil de DeviceScope")
    parser.add_argument("--port", type=int, default=protocol.DEFAULT_PORT, help="puerto de control")
    parser.add_argument("--pin", type=str, default=None, help="PIN fijo de 6 dígitos")
    parser.add_argument("--name", type=str, default=None, help="nombre visible de la PC")
    parser.add_argument("--no-discovery", action="store_true", help="no responder al barrido UDP")
    parser.add_argument("--diagnose", action="store_true", help="muestra capacidades y sale")
    parser.add_argument("--devices", action="store_true", help="lista los equipos emparejados")
    parser.add_argument("--revoke", type=str, default=None, help="revoca un equipo por huella")
    parser.add_argument("--rotate-pin", action="store_true", help="genera un PIN nuevo")
    parser.add_argument("--version", action="version", version=f"DeviceScope agent {AGENT_VERSION}")
    args = parser.parse_args()

    if args.pin is not None:
        digits = "".join(char for char in args.pin if char.isdigit())
        if len(digits) < 4:
            parser.error("el PIN debe tener al menos 4 dígitos")
        args.pin = digits
    state = AgentState(name=args.name, pin=args.pin)

    if args.devices:
        summary = state.summary()
        print(f"Equipos emparejados con {summary['name']} ({summary['fingerprint']}):")
        if not summary["devices"]:
            print("  (ninguno)")
        for device in summary["devices"]:
            print(f"  · {device['name']} — {device['fingerprint']} — {device['platform']}")
        return 0

    if args.revoke:
        print("revocado" if state.revoke(args.revoke) else "no existe esa huella")
        return 0

    if args.rotate_pin:
        print(f"nuevo PIN: {state.rotate_pin()}")
        return 0

    server = AgentServer(state, port=args.port)
    if args.diagnose:
        print_banner(state, args.port)
        print_capabilities(server)
        server.shutdown()
        return 0

    discovery = None
    if not args.no_discovery:
        discovery = DiscoveryResponder(state, control_port=args.port)
        if not discovery.start():
            discovery = None

    print_banner(state, args.port)
    print_capabilities(server)

    stopping = threading.Event()

    def handle_signal(_signum, _frame) -> None:
        stopping.set()

    signal.signal(signal.SIGINT, handle_signal)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, handle_signal)

    worker = threading.Thread(target=server.serve_forever, name="agent", daemon=True)
    worker.start()

    try:
        while not stopping.is_set():
            stopping.wait(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        print("\n[agente] deteniendo…")
        if discovery is not None:
            discovery.stop()
        server.stop()
        server.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
