# PC-Móvil · diseño, protocolo y límites reales

Módulo que convierte el teléfono en una extensión de la PC a través de la **red
local**. No hay nube ni cuentas: el teléfono habla con un agente que el usuario
ejecuta en su propia PC (`pc-agent/agent.py`).

## Qué hace cada herramienta

| Herramienta | Sentido | Implementación real |
|---|---|---|
| **Teclado** | móvil → PC | Texto y atajos enviados por el enlace; el agente los inyecta con `pynput`, `SendInput` (Windows) o `xdotool` (X11). |
| **Teclado** | PC → móvil | El agente captura el teclado con `pynput` y el teléfono los aplica con un `AccessibilityService`: `ACTION_SET_TEXT` (o `ACTION_PASTE`) en el campo con foco. |
| **Mouse** | móvil → PC | Touchpad relativo (`move_pointer`), clics izq/der/medio, arrastre y rueda. |
| **Mouse** | móvil → PC (espejo) | La PC envía JPEG de su pantalla; el toque se traduce a posición absoluta (`SetCursorPos`/`xdotool`). |
| **Mouse** | PC → móvil | Clics y rueda del mouse de la PC se convierten en `dispatchGesture()` (toques) y scroll de accesibilidad sobre el teléfono. |
| **Cámara** | móvil → PC | Camera2 en JPEG a 1–12 fps; el agente abre una ventana de vista previa. Con `v4l2loopback` puede publicarse como `/dev/videoN` para Zoom/Meet/OBS. |
| **Micrófono** | móvil → PC | `AudioRecord` PCM16 mono 48 kHz en bloques de 20 ms; el agente lo reproduce con `sounddevice`. |
| **Altavoces** | PC → móvil | El agente captura su salida (WASAPI loopback / monitor de PulseAudio) y el teléfono reproduce con `AudioTrack`. |
| **DataTransfer** | ambos | Explorador de carpetas de la PC, descarga con `CreateDocument`, subida con `OpenDocument` a `~/DeviceScope/uploads`. |
| **VPN** | móvil → red de la PC | `VpnService` con `tun` IPv4 + relay TCP en el agente que abre sockets reales. TCP y UDP; ICMP descartado. |
| **PC-Resources** | PC → móvil | `psutil` (o `/proc`) + `nvidia-smi`: CPU, RAM/swap, GPU, discos, red, batería, uptime y top de procesos. |
| **A-D Scanner** | local + PC | WiFi (`WifiManager.getScanResults`), BLE (`BluetoothLeScanner`), clásico emparejado, celdas (`getAllCellInfo`), sensores y, opcionalmente, barrido de la subred desde la PC. |

## Arquitectura en el teléfono

```
ui/PcMovilScreen.kt            panel: descubrimiento, PIN, catálogo
ui/Pc*ToolScreen.kt            8 pantallas de herramientas
ui/PcUi.kt, ui/PcFormat.kt     componentes y formato compartidos

pcmovil/data/                  modelos, estado del enlace, preferencias
pcmovil/link/
  PcProtocol.kt                mensajes, canales, cabecera binaria
  MiniJson.kt                  JSON mínimo, testeable en JVM
  PcWire.kt                    framing multiplexado (JSON + binario)
  PcConnection.kt              bucle de lectura, peticiones correladas, flujos
  PcDiscovery.kt               barrido UDP en la LAN
  PcLinkManager.kt             singleton: estado, entrada, espejo, archivos, VPN
  PcFileTransfers.kt           subida/descarga con progreso
  PcAudioBridge.kt             micrófono y altavoces
  PcCameraStreamer.kt          Camera2 → JPEG
  IpPacketCodec.kt             IPv4/TCP/UDP puro (testeable en JVM)
  PcVpnEngine.kt               tun ↔ relay, máquina de estados TCP mínima
  PcVpnService.kt              VpnService, rutas y contadores
  DataScanner.kt               radios y sensores
  PcRemoteInputService.kt      AccessibilityService para PC → móvil
```

No hay ViewModel ni DI: el estado de pantalla vive en `remember` y el enlace en
un singleton creado con el `applicationContext`, igual que el resto de la app.

## Transporte y protocolo

* **TCP** en el puerto `8787` (configurable en el agente).
* **UDP** en el puerto `8788` para descubrimiento por broadcast.
* Un solo socket multiplexa todo:

```
{"v":1,"type":"mouse","id":7,"ch":"input","data":{"target":"pc","action":"move","dx":1.5}}
```

```
[canal u8][tipo u8][longitud u32 BE][payload]
```

El primer byte de un mensaje JSON es `{` (0x7B); los canales binarios usan
ordinales 0–7, así que no hay ambigüedad.

| Canal | Ordinal | Contenido |
|---|---|---|
| control | 0 | JSON (no se usa para binario) |
| input | 1 | JSON de entrada |
| metrics | 2 | JSON de métricas |
| mirror | 3 | JPEG de la pantalla de la PC |
| camera | 4 | JPEG de la cámara del teléfono |
| mic | 5 | PCM16 del micrófono |
| speaker | 6 | PCM16 de la PC |
| files | 7 | bloques de archivo + marcador de fin |

### Emparejamiento

1. El teléfono envía `hello` con su huella (`DS-XXXXXXXX`), su nombre y el token
   guardado (si lo hay).
2. El agente responde `hello_ack` si el token es válido o `pair_required` si no.
3. El usuario escribe el PIN de 6 dígitos que muestra la consola del agente.
   `pair` → `pair_ok` con un token de 48 hex.
4. El token se guarda en `SharedPreferences` (privadas de la app) y en
   `~/.devicescope/agent.json` con permisos `0600`.
5. Cinco PIN incorrectos bloquean el emparejamiento 60 s. Un PIN válido permite
   recuperar el acceso si el teléfono perdió el token.
6. Solo hay **una sesión emparejada activa**: el agente rechaza una segunda con
   un `error` explicativo (y libera la anterior si el socket está muerto).

### Seguridad: lo que sí y lo que no

* El tráfico **no va cifrado**. El enlace es LAN y el modelo de amenaza es «otro
  equipo de la misma WiFi». El PIN y el token impiden que un vecino controle la
  PC; no protegen del dueño de la red ni de un MITM activo.
* El agente no expone el sistema de archivos completo: solo el home y los puntos
  de montaje habituales, con resolución de rutas que impide `../` fuera de ellos.
* Los binarios de audio y vídeo nunca se escriben en disco.
* Todo se puede revocar: en el teléfono «Olvidar», en la PC
  `python agent.py --revoke DS-XXXXXXXX`.

## Límites honestos

* **Teclado PC → móvil**: depende de accesibilidad. Apps que no exponen el nodo
  editable (banca, algunos juegos) no reciben texto. No hay forma de saltarlo sin
  root.
* **Espejo de pantalla**: necesita Pillow; si falta `mss`, usa `ImageGrab`. En
  Wayland puro la captura puede fallar (usa XWayland o `wlr-screencopy`).
* **Cámara como webcam del sistema**: el agente muestra una ventana. Para que
  Zoom/Meet la vean como cámara hace falta `v4l2loopback` (Linux) u OBS Virtual
  Camera. La app no instala drivers.
* **Audio**: la captura de la salida del sistema requiere loopback; sin él, la
  herramienta avisa y solo funciona el micrófono → PC.
* **VPN**: IPv4, TCP y UDP; ICMP y IPv6 se descartan. Las conexiones abiertas
  antes de activarla siguen por su ruta original. Con `0.0.0.0/0` todo el
  tráfico del teléfono sale por la PC: la UI lo advierte.
* **A-D Scanner**: el WiFi necesita `NEARBY_WIFI_DEVICES` (Android 13+) o
  ubicación (Android 12 y anteriores) y el sistema con WiFi encendido; en 13+ los
  resultados pueden venir de la caché del sistema. El Bluetooth clásico solo
  muestra equipos ya emparejados (el descubrimiento clásico tarda 12 s y bloquea
  la radio). Las distancias son estimaciones logarítmicas por RSSI. No se leen
  coordenadas, ni contactos, ni SMS.
* **Frecuencia de métricas**: 0,5–5 s; por encima de 1 s el panel gasta menos
  batería.

## Permisos y por qué

| Permiso | Uso | Si se deniega |
|---|---|---|
| `INTERNET` | sockets con el agente en la LAN | No hay enlace: el diagnóstico sigue funcionando |
| `CHANGE_WIFI_MULTICAST_STATE` | descubrimiento UDP | Se puede escribir la IP a mano |
| `CAMERA` | webcam remota | La herramienta avisa |
| `RECORD_AUDIO` | micrófono remoto y prueba del diagnóstico | Se avisa explícitamente |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | escaneo WiFi en 13+ | Sin resultados WiFi en el barrido |
| `BLUETOOTH_SCAN` (`neverForLocation`) / `BLUETOOTH_CONNECT` | BLE y equipos clásicos | Sin resultados Bluetooth |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | solo WiFi/celdas en Android ≤ 12 | Se piden al encender el barrido; sin ellos no hay celdas |
| `BIND_ACCESSIBILITY_SERVICE` (servicio) | inyección de entrada desde la PC | Teclado/mouse PC → móvil desactivado |
| `BIND_VPN_SERVICE` (servicio) | túnel | VPN desactivada |

El **diagnóstico exhaustivo** no usa la red: sigue siendo offline y no necesita
`INTERNET` para funcionar.

## Puesta en marcha

1. En la PC: `python pc-agent/agent.py` (ver `pc-agent/README.md`).
2. En el teléfono: **PC-Móvil → Buscar en la red local** (o IP manual) y escribe
   el PIN.
3. Para el sentido PC → móvil, activa **Ajustes → Accesibilidad → DeviceScope
   PC-Móvil**.
4. Si el firewall de la PC pregunta, permite TCP 8787 y UDP 8788 en la red
   privada.

## Diagnóstico de problemas

| Síntoma | Causa probable |
|---|---|
| «No se encontró ningún agente» | WiFi distinta, firewall, agente sin `--pin`/caído, o router con aislamiento de clientes |
| «Conexión rechazada» | puerto cerrado o agente escuchando en otra interfaz |
| «La PC no respondió a tiempo» | AP con aislamiento o IP incorrecta |
| El agente dice «otra sesión activa» | otro teléfono emparejado sigue conectado |
| El espejo no aparece | falta Pillow/mss o sesión Wayland |
| No se escribe en el teléfono | servicio de accesibilidad desactivado o app sin nodo editable |
| El túnel no abre sitios | la red destino no es privada o el firewall de la PC bloquea el relay |

## Pruebas

* Kotlin (JVM): `./gradlew :app:testDebugUnitTest` — `MiniJsonTest`,
  `PcProtocolTest`, `IpPacketCodecTest`.
* Agente: `cd pc-agent && python -m unittest discover -s tests -v` — protocolo,
  emparejamiento, métricas, archivos, relay VPN real por loopback y límites de
  sesión.
