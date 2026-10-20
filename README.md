# DeviceScope

Aplicación Android nativa en Kotlin y Jetpack Compose para consultar el estado básico del dispositivo desde un dashboard claro, local y sin conexión.

## Incluye

- Dashboard con puntuación de salud (0–100), tarjetas de estado y seis módulos navegables.
- Diagnóstico rápido con modelo, versión/API de Android, RAM total y disponible, y almacenamiento interno libre y total.
- Diagnóstico guiado de cable y cargador con una medición de 10 segundos, voltaje/temperatura de batería, corriente instantánea cuando está disponible, potencia estimada y referencias de cargadores comunes.
- Comprobación USB con `UsbManager.getDeviceList()`; un resultado vacío se presenta como no concluyente, con instrucciones para probar MTP en una computadora.
- Pantalla SIM con estado, operador, país y señal. `READ_PHONE_STATE` se solicita solo al usuario para los datos protegidos; ICCID e IMSI nunca se consultan.
- Pantalla de tarjeta SD con volúmenes extraíbles, espacio de `StatFs` y prueba de lectura/escritura temporal de 5 MB en el directorio privado de la app.
- Pantalla de conexiones con ConnectivityManager, SSID/RSSI opcional, Bluetooth, NFC y estado del proveedor GPS. No se escanean redes ni se leen coordenadas.
- Pantalla “¿Puedo ejecutar esta app?”: selecciona un APK mediante el selector del sistema, lee `minSdkVersion`, `targetSdkVersion` y las características obligatorias (`uses-feature`/OpenGL ES), y exporta el informe de compatibilidad a JSON.
- Material Design 3, tema claro/oscuro con selector y adaptación al tema del sistema.
- Sin permiso `INTERNET`. Wi-Fi/Bluetooth pueden pedir permisos runtime opcionales; la prueba SD no requiere permiso general de archivos.

## Abrir y ejecutar

Abre la carpeta del proyecto en Android Studio (JDK 17 y Android SDK 35). El módulo instalable es `app` y el identificador es `com.devicescope.app`.

Desde un entorno con Java 17 y Android SDK configurados puedes compilar con el wrapper incluido:

```bash
./gradlew :app:assembleDebug
```

La primera sincronización puede necesitar descargar las dependencias de compilación. La app instalada no realiza solicitudes de red; los datos de diagnóstico se leen en el dispositivo con `Build`, `ActivityManager.MemoryInfo` y `StatFs`.

## Privacidad y alcance

La puntuación inicial es orientativa y se actualiza al ejecutar el diagnóstico. El cálculo local considera la RAM y el espacio disponible. En cable/cargador, Android expone valores de la batería, no la salida USB real del adaptador; por eso los vatios y su comparación son estimados, no una certificación de potencia. `UsbManager.getDeviceList()` puede no ver el teléfono cuando actúa como dispositivo USB, así que una lista vacía no demuestra que el cable sea solo de carga. En SIM, si se deniega `READ_PHONE_STATE`, el operador y la señal quedan ocultos; el ICCID y el IMSI no se leen. La prueba de SD escribe y elimina un temporal de 5 MB; su lectura puede beneficiarse de la caché del sistema. En Conexiones, el estado del GPS no incluye coordenadas y el SSID puede estar oculto si se deniega el permiso opcional. Android 13+ usa Dispositivos cercanos; versiones anteriores pueden requerir Ubicación precisa para revelar SSID/señal, pero la app no solicita ni consulta coordenadas. El analizador de APK compara el `minSdkVersion` y las características obligatorias del manifiesto con el dispositivo; `targetSdkVersion` se informa, pero no se trata como versión mínima. La alerta de RAM es heurística: solo considera exigencia gráfica alta cuando el APK requiere OpenGL ES 3.2 o Vulkan, y advierte si hay menos de 4 GB. No instala el APK ni garantiza su comportamiento: no evalúa dependencias, APK divididos ni requisitos no declarados.
