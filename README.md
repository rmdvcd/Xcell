# DeviceScope

Aplicación Android nativa (Kotlin + Jetpack Compose) con **dos módulos**:

1. **Diagnóstico exhaustivo** — analiza cada componente del teléfono. Funciona
   por completo sin conexión.
2. **PC-Móvil** — usa el teléfono como teclado, mouse, cámara, micrófono,
   altavoces, disco y túnel de la PC, y muestra los recursos de esa PC en vivo.
   Funciona sobre la red local con un agente que ejecutas tú en la PC.

`minSdk 26` · `compileSdk/targetSdk 35` · `com.devicescope.app`

## Módulo 1 · Diagnóstico exhaustivo

Genera 14 secciones, cada una con métricas reales, hallazgos y notas de interpretación:

1. **Identidad y antifalsificación** — `Build`, `getprop`, `build.prop`, `/proc/cpuinfo`, `/proc/meminfo`, `/proc/partitions`, `wm size/density`, sysfs y `BatteryManager` contrastados entre sí.
2. **Micrófono** — dispositivos de entrada, estado de silencio y grabación de 2 s con nivel pico/RMS en dBFS.
3. **RAM** — `ActivityManager`, `/proc/meminfo`, heap de Dalvik y coherencia de capacidad.
4. **Almacenamiento** — `StatFs`, `/proc/partitions`, `df`, volúmenes extraíbles y prueba real de escritura/lectura de 5 MB.
5. **Antenas y transmisores** — radios declaradas, telefonía, nivel/detalle de señal celular, WiFi, Bluetooth, NFC y proveedor GPS (sin coordenadas).
6. **Pantalla** — resolución, densidad, refresco, HDR, brillo, `wm size/density` y prueba de píxeles con seis colores.
7. **Táctil** — capacidades multitouch, dispositivos de entrada, cobertura de cuadrícula y punteros simultáneos.
8. **Bocinas** — dispositivos de salida, volúmenes por stream y tono estéreo 440/660 Hz.
9. **Plugs y conectores** — conector de auriculares, USB host/accessory, audio cableado y fuente de carga.
10. **Batería** — nivel, salud, temperatura, corriente, contador de carga, energía, ciclos, `charge_full` y desgaste estimado.
11. **Cable y cargador** — medición guiada de 10 s con voltaje/corriente/potencia/temperatura y referencias de 5/10/18 W.
12. **Red (Datos / WiFi / Bluetooth)** — red activa, capacidades, ancho de banda, tipos de red y estado de radios.
13. **Mapa de voltajes internos** — `/sys/class/power_supply`, reguladores, zonas térmicas, frecuencias de CPU y `BatteryManager`.
14. **Todas las propiedades del equipo** — volcado crudo con buscador de claves y exportación.

El informe se puede guardar como **TXT** o **JSON** mediante el selector de archivos del sistema.

### Cómo detecta falsificaciones

No existe una API que "desenmascare" un teléfono con root. Lo que hace la app es comparar **fuentes independientes** que los clones suelen parchear por separado: `Build.MODEL` frente a `ro.product.model`, `ActivityManager.totalMem` frente a `/proc/meminfo`, `DisplayMetrics` frente a `wm size`, `/proc/cpuinfo` frente a `Runtime.availableProcessors()`, particiones frente a `StatFs`, capacidad de batería de sysfs frente a `BATTERY_PROPERTY_CAPACITY`, etc. Cada contradicción se marca como hallazgo con severidad.

Más detalle y límites en [`DIAGNOSTIC_NOTES.md`](DIAGNOSTIC_NOTES.md).

## Módulo 2 · PC-Móvil

Nueve herramientas, todas sobre la red local y sin intermediarios:

| Herramienta | Qué hace |
|---|---|
| **Teclado** | El teclado del móvil escribe en la PC; el de la PC escribe en el móvil mediante el servicio de accesibilidad. |
| **Mouse** | Touchpad relativo con clics, arrastre y rueda, y espejo de la pantalla de la PC para tocar directamente. En sentido inverso, el mouse de la PC toca el teléfono. |
| **Cámara** | Cámara del móvil como webcam: Camera2 → JPEG → ventana en la PC (o `/dev/video*` con `v4l2loopback`). |
| **Micrófono y altavoces** | Micrófono del móvil hacia la PC y audio de la PC hacia los altavoces del móvil, PCM16 con medidores. |
| **DataTransfer** | Explorador de carpetas de la PC, descarga y subida de archivos con el selector del sistema. |
| **VPN** | `VpnService` con `tun` IPv4 y relay en el agente: entra en la red de la PC. TCP y UDP. |
| **PC-Resources** | CPU, RAM, GPU, discos, red, batería y procesos de la PC en vivo, con sparklines. |
| **A-D Scanner** | Barrido de WiFi, BLE, Bluetooth clásico, celdas, NFC y sensores; opcionalmente amplía a la subred de la PC. |

### Puesta en marcha

```bash
cd pc-agent
python agent.py        # imprime IP, PIN y capacidades detectadas
```

En Windows basta con doble clic en `pc-agent\iniciar-agente.bat`: comprueba
Python 3.9+, crea el entorno virtual, instala las dependencias opcionales y
arranca el agente. Guía paso a paso en [`pc-agent/WINDOWS.md`](pc-agent/WINDOWS.md).

En el teléfono: **PC-Móvil → Buscar en la red local → PIN**. Para el sentido
PC → móvil, activa el servicio en **Ajustes → Accesibilidad → DeviceScope
PC-Móvil**. Si el firewall pregunta, permite **TCP 8787** y **UDP 8788** en redes
privadas.

Arquitectura, protocolo, límites reales de cada herramienta y solución de
problemas: [`docs/PC-MOVIL.md`](docs/PC-MOVIL.md) y
[`pc-agent/README.md`](pc-agent/README.md).

## Privacidad

- Sin telemetría, sin anuncios, sin cuentas, sin servicios de terceros.
- **El diagnóstico no usa la red.** El permiso `INTERNET` existe solo para el
  enlace PC-Móvil con el agente de tu LAN; no hay nube ni relé externo.
- No se leen ICCID, IMSI, número telefónico ni coordenadas. El A-D Scanner usa
  ubicación **solo** en Android ≤ 12 y solo mientras el usuario enciende el
  barrido; en Android 13+ usa `NEARBY_WIFI_DEVICES` con `neverForLocation`.
- El audio se procesa en memoria: ni el diagnóstico ni el módulo PC-Móvil guardan
  audio. Los fotogramas de la cámara tampoco se escriben en disco.
- Los archivos temporales de las pruebas se eliminan al terminar; los informes y
  las transferencias solo se escriben donde tú elijas.
- El enlace PC-Móvil **no va cifrado** (LAN de confianza; ver notas de seguridad).
- `allowBackup=false`.

Permisos opcionales en runtime: `READ_PHONE_STATE`, `RECORD_AUDIO`,
`NEARBY_WIFI_DEVICES`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `CAMERA`,
`ACCESS_FINE_LOCATION` (solo escaneo en Android ≤ 12). Si se deniegan, la sección
afectada muestra «Requiere permiso» en vez de inventar datos.

## Compilar y probar

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest      # incluye MiniJson, PcProtocol e IpPacketCodec
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:lint

cd pc-agent && python -m unittest discover -s tests -v
```

Requiere JDK 17 y Android SDK 35. En Windows usa `.\gradlew.bat`.

## Estructura

- `MainActivity` → `DeviceScopeApp` (`NavHost`: `dashboard`, `fullDiagnostic`,
  `pcMovil` y las ocho herramientas de PC-Móvil).
- `data/`: `DiagnosticModels`, `DiagnosticAudit` (lógica pura), `FullDiagnosticBuilder` (todas las lecturas), `DeviceReaders` y `PowerEstimator` (lectores de sistema reutilizados), `DeviceModule` (catálogo de módulos).
- `pcmovil/data/`: modelos del enlace, estado, equipos emparejados y preferencias.
- `pcmovil/link/`: protocolo, socket multiplexado, descubrimiento, espejo, audio,
  cámara, archivos, VPN y escáner.
- `ui/`: dashboard, diagnóstico y pantallas del módulo (`PcMovilScreen`,
  `Pc*ToolScreen`, `PcUi`), más `ui/components/`.
- `pc-agent/`: agente de PC en Python (stdlib + dependencias opcionales).
- `docs/PC-MOVIL.md`: protocolo y límites.
- Textos de UI en Kotlin; `strings.xml` solo contiene `app_name` y las cadenas
  del servicio de accesibilidad.
