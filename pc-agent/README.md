# Agente PC · DeviceScope PC-Móvil

Servicio que se ejecuta en tu PC y expone las herramientas del módulo **PC-Móvil**
de DeviceScope por la red local. No hay nube, ni cuentas, ni telemetría: el
teléfono habla directo con este proceso.

```
Python 3.9+ · solo biblioteca estándar para arrancar · Windows / macOS / Linux
```

## Arranque rápido

```bash
cd pc-agent
python agent.py
```

Salida típica:

```
──────────────────────────────────────────────────────────────
 DeviceScope · agente PC-Móvil
──────────────────────────────────────────────────────────────
 Equipo      : DESKTOP-DEV
 Huella      : PC-04C540C1
 Dirección   : 192.168.1.42:8787
 PIN         : 794536   (cámbialo con --rotate-pin)
 Configuración: /home/usuario/.devicescope/agent.json
──────────────────────────────────────────────────────────────
```

En el teléfono: **PC-Móvil → Buscar en la red local** y escribe el PIN.

Si el firewall pregunta, permite **TCP 8787** y **UDP 8788** en redes privadas.

## Opciones

| Opción | Qué hace |
|---|---|
| `--port 8787` | puerto de control |
| `--pin 123456` | PIN fijo (útil para demos) |
| `--name "Mi PC"` | nombre visible en el teléfono |
| `--no-discovery` | no responde al barrido UDP (conéctate por IP) |
| `--diagnose` | muestra qué herramientas funcionan y sale |
| `--devices` | equipos emparejados |
| `--revoke DS-XXXXXXXX` | expulsa un equipo |
| `--rotate-pin` | genera un PIN nuevo |
| `--version` | versión del agente |

## Dependencias opcionales

El agente arranca y funciona sin instalar nada: emparejamiento, métricas básicas
(en Linux/macOS con `/proc` y `shutil`), archivos, VPN y barrido de LAN.
Cada herramienta extra necesita su paquete y **se anuncia como capacidad**; si
falta, la app lo dice en pantalla en lugar de fallar en silencio.

```bash
python -m pip install psutil          # métricas completas, batería, top de procesos
python -m pip install pillow mss      # espejo de pantalla y ventana de cámara
python -m pip install pynput          # teclado/mouse y captura PC→móvil
python -m pip install sounddevice     # micrófono remoto y audio PC→teléfono
```

| Paquete | Habilita | Alternativa sin instalarlo |
|---|---|---|
| `psutil` | CPU, RAM/swap, discos, red, procesos, batería | `/proc` en Linux, `shutil` para discos |
| `pillow` + `mss` | espejo de pantalla y cámara en ventana | `PIL.ImageGrab` (solo Pillow) |
| `pynput` | inyectar teclado/mouse y capturar la entrada local | `SendInput` en Windows, `xdotool` en X11 |
| `sounddevice` | audio bidireccional | — (el resto del módulo sigue) |

En **Linux X11** también necesitas `xdotool` si no usas `pynput`
(`sudo apt install xdotool`). En **Wayland**, la inyección de entrada y la
captura de pantalla dependen del compositor; lo más fiable es `pynput` con
XWayland o un compositor que permita `wlr-screencopy`.

Para publicar la cámara del teléfono como cámara del sistema en Linux:

```bash
sudo modprobe v4l2loopback exclusive_caps=1 card_label="Teléfono"
# luego apunta OBS (fuente: ventana del agente) a /dev/videoN
```

## Qué se ejecuta y qué se expone

* **TCP 8787** — protocolo de control y canales binarios (ver
  [`docs/PC-MOVIL.md`](../docs/PC-MOVIL.md)).
* **UDP 8788** — responde al descubrimiento con nombre, puerto y huella.
* **Relay VPN** — puerto TCP efímero que se abre solo al pedir el túnel y se
  cierra al desactivarlo. Los sockets los abre la PC hacia la red de destino.
* **Archivos** — lectura dentro del home y de los puntos de montaje habituales
  (`/media`, `/mnt`, `/Volumes`, `C:`…`H:`); las subidas van a
  `~/DeviceScope/uploads`. Las rutas se resuelven y se comprueba que no salgan de
  esos árboles.
* **Estado** — `~/.devicescope/agent.json` (permisos `0600`) con nombre, huella y
  tokens de los equipos emparejados.

## Seguridad

* El PIN se compara en tiempo constante y bloquea 60 s tras 5 fallos.
* El token de 24 bytes se genera en el emparejamiento y se puede revocar.
* Una sola sesión activa; una segunda conexión emparejada recibe un error claro.
* **El tráfico no va cifrado.** Es un enlace de LAN: úsalo en redes propias y
  con el firewall en modo privado.
* No se envía nada a Internet; el agente no hace peticiones salientes salvo los
  sockets que tú provoques con la VPN o con una descarga de archivos.

## Pruebas

```bash
cd pc-agent
python -m unittest discover -s tests -v
```

Cubren el protocolo (codificación exacta que consume la app), el emparejamiento
(PIN correcto/incorrecto, token recordado, bloqueo), métricas, listado y
descarga de archivos, subida, relay VPN real por loopback y el rechazo de una
segunda sesión.

`--diagnose` es la comprobación rápida en la máquina real:

```bash
python agent.py --diagnose
```

## Estructura

```
agent.py                    entrada CLI
devicescope_agent/
  protocol.py               framing JSON/binario y tipos de mensaje
  state.py                  identidad, PIN, tokens
  discovery.py              respondedor UDP
  server.py                 sesión, emparejamiento y herramientas
  input_backend.py          inyección/captura de teclado y mouse (pynput, ctypes, xdotool)
  system_info.py            métricas y barrido de subred
  media.py                  pantalla, cámara y audio
  vpn.py                    relay del túnel
tests/                      unittest del protocolo y de la integración
```

## Solución de problemas

| Síntoma | Solución |
|---|---|
| El teléfono no lo encuentra | mismo WiFi; `--no-discovery` desactiva UDP; revisa el firewall; algunos routers aíslan clientes |
| «Conexión rechazada» | puerto ocupado por otro proceso o firewall |
| No escribe en la PC | `--diagnose` muestra el backend; instala `pynput` o `xdotool` |
| No escribe en el teléfono | activa el servicio de accesibilidad en el teléfono |
| Sin espejo | instala `pillow` y `mss`; en Wayland usa XWayland |
| Sin audio | instala `sounddevice`; comprueba el dispositivo de loopback |
| La VPN no carga nada | el destino no es una red privada marcada, o el firewall bloquea el relay |
