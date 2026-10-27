# DeviceScope

Aplicación Android nativa (Kotlin + Jetpack Compose) con **un único módulo: diagnóstico exhaustivo del celular**. Funciona por completo sin conexión.

`minSdk 26` · `compileSdk/targetSdk 35` · `com.devicescope.app`

## Qué analiza

El módulo genera 14 secciones, cada una con métricas reales, hallazgos y notas de interpretación:

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

## Cómo detecta falsificaciones

No existe una API que "desenmascare" un teléfono con root. Lo que hace la app es comparar **fuentes independientes** que los clones suelen parchear por separado: `Build.MODEL` frente a `ro.product.model`, `ActivityManager.totalMem` frente a `/proc/meminfo`, `DisplayMetrics` frente a `wm size`, `/proc/cpuinfo` frente a `Runtime.availableProcessors()`, particiones frente a `StatFs`, capacidad de batería de sysfs frente a `BATTERY_PROPERTY_CAPACITY`, etc. Cada contradicción se marca como hallazgo con severidad.

Más detalle y límites en [`DIAGNOSTIC_NOTES.md`](DIAGNOSTIC_NOTES.md).

## Privacidad

- **Sin permiso `INTERNET`**, sin telemetría, sin anuncios, sin cuentas.
- No se leen ICCID, IMSI, número telefónico ni coordenadas. No se escanean redes WiFi/Bluetooth.
- El SSID solo se muestra en Android 13+ con `NEARBY_WIFI_DEVICES` (`neverForLocation`).
- El audio de la prueba de micrófono se procesa en memoria y se descarta.
- Los archivos temporales de las pruebas se eliminan al terminar; los informes solo se escriben en la ubicación que elijas.
- `allowBackup=false`.

Permisos opcionales en runtime: `READ_PHONE_STATE`, `RECORD_AUDIO`, `NEARBY_WIFI_DEVICES`, `BLUETOOTH_CONNECT`. Si se deniegan, la sección afectada muestra «Requiere permiso» en vez de inventar datos.

## Compilar

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:lint
```

Requiere JDK 17 y Android SDK 35. En Windows usa `.\gradlew.bat`.

## Estructura

- `MainActivity` → `DeviceScopeApp` (`NavHost`: `dashboard`, `fullDiagnostic`).
- `data/`: `DiagnosticModels`, `DiagnosticAudit` (lógica pura), `FullDiagnosticBuilder` (todas las lecturas), `DeviceReaders` y `PowerEstimator` (lectores de sistema reutilizados), `DeviceModule` (catálogo de un solo módulo).
- `ui/screens/`: `DashboardScreen`, `FullDiagnosticScreen`, `DiagnosticDialogs` (pruebas interactivas).
- `ui/components/`: `ScreenHeader`, `StatusPill`, `DiagnosticUi`.
- Textos de UI en Kotlin; `strings.xml` solo contiene `app_name`.
