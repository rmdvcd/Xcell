# Notas técnicas del diagnóstico exhaustivo

## 1. Qué significa "traspasar posibles falsificaciones"

Una app de usuario **no puede garantizar** que un teléfono es original. Un dispositivo con root, un framework modificado o un hook en el kernel puede falsear `Build.*`, `getprop`, `/proc` y hasta `sysfs` a la vez. Lo que sí puede hacer es **detectar incoherencias**: un clon barato normalmente parchea una sola capa (por ejemplo `build.prop`) y deja las demás intactas.

Fuentes que se comparan:

| Dato | Fuente 1 | Fuente 2 | Fuente 3 |
|---|---|---|---|
| Modelo / fabricante / marca | `Build.MODEL`, `Build.MANUFACTURER` | `ro.product.model`, `ro.product.manufacturer`, variantes `system/vendor/product/odm` | `/system/build.prop`, `/vendor/build.prop` |
| Versión de Android | `Build.VERSION.RELEASE`, `SDK_INT` | `ro.build.version.release`, `ro.build.version.sdk` | `Build.FINGERPRINT` |
| RAM | `ActivityManager.MemoryInfo.totalMem` | `/proc/meminfo` (`MemTotal`) | `dalvik.vm.heapsize`, `ro.config.low_ram` |
| CPU | `Runtime.availableProcessors()` | `/proc/cpuinfo` (`processor`, `Hardware`) | `ro.board.platform`, `ro.hardware`, ABI |
| Almacenamiento | `StatFs(/data)` | `/proc/partitions` (`userdata`) | `df -k /data` |
| Pantalla | `DisplayMetrics` | `wm size` / `wm density` | `ro.sf.lcd_density` |
| Batería | `BATTERY_PROPERTY_CAPACITY` | `/sys/class/power_supply/battery/capacity` | `charge_full` / `charge_full_design` |
| Sensores / cámaras | `SensorManager` | `CameraManager` | `PackageManager.hasSystemFeature` |

Hallazgos típicos: modelo distinto entre `Build` y `getprop`, RAM inflada, resolución que no coincide con WindowManager, firmware `userdebug`/`test-keys`, `ro.debuggable=1`, identidad `generic`, ABI principal distinta, múltiples modelos declarados, batería con menos del 70 % de su capacidad de diseño o `authentic=0`.

**Interpretación correcta:** "no se detectaron contradicciones" ≠ "es original". El informe lo dice explícitamente.

## 2. Mapa de voltajes internos: alcance real

En Android sin root, SELinux y los permisos de `sysfs` limitan mucho el acceso. La app intenta leer:

- `/sys/class/power_supply/*`: `voltage_now`, `voltage_avg`, `current_now`, `current_avg`, `temp`, `capacity`, `charge_full`, `charge_full_design`, `cycle_count`, `health`, `status`, `technology`, `authentic`, `power_now`.
- `/sys/class/regulator/*`: `name`, `microvolts`, `state`, `min/max_microvolts`.
- `/sys/class/thermal/thermal_zone*`: `type`, `temp`.
- `/sys/class/hwmon/hwmon*/temp*_input`.
- `/sys/devices/system/cpu/cpu*/cpufreq/scaling_*`.
- `BatteryManager`: `EXTRA_VOLTAGE`, `EXTRA_TEMPERATURE`, `BATTERY_PROPERTY_CURRENT_NOW`, `BATTERY_PROPERTY_CURRENT_AVERAGE`, `BATTERY_PROPERTY_CHARGE_COUNTER`, `BATTERY_PROPERTY_ENERGY_COUNTER`, `computeChargeTimeRemaining()`.

Conversiones aplicadas: `voltage_now` µV → V, `current_now` µA → A, `temp` décimas de °C → °C, `charge_full` µAh → mAh, `power_now` µW → W, `temp` de thermal_zones normalmente miligrados → °C.

**Lo que NO se puede leer sin root:** rieles internos del PMIC (VDD_CPU, VDD_GPU, VDD_MEM), voltajes de núcleo del SoC, corrientes por riel de la placa, ADC de la batería a nivel de hardware. Los nodos de `regulator` suelen existir pero devolver `Permission denied`. Cuando eso pasa, el informe muestra "sin acceso (SELinux)" en lugar de valores falsos.

Por tanto, el "mapa de voltajes" es un mapa de **batería, cargador, BMS, reguladores accesibles y sensores térmicos**, no un esquema eléctrico completo del chipset.

## 3. Pruebas interactivas y sus límites

- **Micrófono:** `AudioRecord` 44.1 kHz mono PCM16 durante 2 s. Nivel pico y RMS en dBFS. Pico < -50 dBFS sugiere micrófono tapado/dañado/silenciado; pico > -1 dBFS sugiere saturación. El audio no se escribe en disco.
- **Bocinas:** `AudioTrack` estático estéreo con 440 Hz (izquierda) y 660 Hz (derecha) durante 1.2 s. El veredicto final depende de la respuesta del usuario.
- **Táctil:** cuadrícula 6×10 con `awaitPointerEvent(PointerEventPass.Initial)`. Se cuentan zonas respondidas y el máximo de punteros simultáneos. Cobertura < 80 % se marca como error.
- **Pantalla:** seis colores a pantalla completa; el usuario reporta manchas, líneas o píxeles muertos.
- **Cable/cargador:** 10 muestras de `BatteryDiagnosticReader` (1 Hz), `PowerEstimator` calcula potencia media = V × |I|. Se compara con referencias de 5/10/18 W. Android expone la corriente que entra a la batería, **no** la salida real del adaptador USB; por eso los vatios son orientativos.
- **Almacenamiento:** archivo temporal de 5 MB en `filesDir` con `fsync` para medir escritura y lectura. La caché del sistema puede mejorar artificialmente la lectura.

## 4. Privacidad

- **El diagnóstico no usa la red.** Ninguna clase del diagnóstico abre sockets,
  usa `HttpURLConnection` ni llama a servicios en la nube. El permiso `INTERNET`
  del manifiesto pertenece al módulo PC-Móvil (enlace directo con el agente en la
  LAN); ese módulo tiene su propio documento en [`docs/PC-MOVIL.md`](docs/PC-MOVIL.md).
- El diagnóstico no usa `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` ni llama
  a `WifiManager.getScanResults()` o `TelephonyManager.getAllCellInfo()`. El
  único escaneo de radiofrecuencia está aislado en el A-D Scanner del módulo
  PC-Móvil, se activa a mano y nunca lee coordenadas.
- No se consulta ICCID, IMSI, número de teléfono ni lista de contactos. `Build.getSerial()` solo se lee si `READ_PHONE_STATE` está concedido.
- `getprop`, `build.prop` y `/proc` se vuelcan **dentro del informe local**; el usuario decide si lo guarda. La app no los transmite.
- `allowBackup=false` para que el informe y los permisos no se copien a otro equipo sin control.

## 5. Permisos y degradación

| Permiso | Para qué | Si se deniega |
|---|---|---|
| `READ_PHONE_STATE` | señal celular, tipo de red, operador, roaming, `Build.getSerial()` | La sección muestra «Requiere READ_PHONE_STATE» |
| `RECORD_AUDIO` | medición de 2 s del micrófono | Métrica «Sin permiso RECORD_AUDIO» |
| `NEARBY_WIFI_DEVICES` (Android 13+) | SSID de la red conectada | SSID oculto; RSSI y frecuencia siguen disponibles |
| `BLUETOOTH_CONNECT` (Android 12+) | nombre/estado/emparejados Bluetooth | Estado parcial o «sin permiso» |
| `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `NFC` | lectura de estado | No requieren solicitud runtime |

Ninguna denegación produce datos inventados: se etiqueta el valor como no disponible.

## 6. Verificación

- Lógica pura (`DiagnosticAudit`, modelos) compilable y testeable en JVM.
- `FullDiagnosticBuilder` debe ejecutarse siempre en `Dispatchers.IO`.
- Todas las lecturas de archivos/comandos usan `runCatching` y valores por defecto.
- Comandos usados: `getprop`, `wm size`, `wm density`, `df -k /data`, `dumpsys battery`. Si el comando no existe o SELinux lo bloquea, el campo queda «No disponible».
