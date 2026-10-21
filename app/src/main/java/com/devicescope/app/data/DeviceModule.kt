package com.devicescope.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

enum class ModuleStatus(val label: String) {
    OK("OK"),
    WARNING("Advertencia"),
    ERROR("Error"),
}

object ModuleIds {
    const val GENERAL = "general"
    const val CABLE = "cable"
    const val SIM = "sim"
    const val SD_CARD = "sd-card"
    const val CONNECTIONS = "connections"
    const val APP_COMPATIBILITY = "app-compatibility"
}

data class DeviceModule(
    val id: String,
    val title: String,
    val summary: String,
    val status: ModuleStatus,
    val icon: ImageVector,
    val description: String,
    val steps: List<String>,
    val privacyNote: String,
)

val deviceModules = listOf(
    DeviceModule(
        id = ModuleIds.GENERAL,
        title = "Diagnóstico general",
        summary = "Sistema, RAM y almacenamiento",
        status = ModuleStatus.OK,
        icon = Icons.Filled.FavoriteBorder,
        description = "Ejecuta una comprobación rápida de los recursos principales del teléfono. La lectura se realiza con información del propio sistema y permanece en este dispositivo.",
        steps = listOf(
            "Identifica el modelo y la versión de Android.",
            "Consulta la RAM total y la memoria disponible.",
            "Revisa el espacio libre del almacenamiento interno.",
        ),
        privacyNote = "La comprobación no modifica ajustes, no requiere permisos especiales y no envía datos.",
    ),
    DeviceModule(
        id = ModuleIds.CABLE,
        title = "Cable y cargador",
        summary = "Conecta para medir en 10 s",
        status = ModuleStatus.WARNING,
        icon = Icons.Filled.BatteryChargingFull,
        description = "Toma una lectura orientativa de 10 segundos de la batería. Android no permite conocer directamente la salida USB ni confirmar el estado físico de todos los cables y adaptadores.",
        steps = listOf(
            "Prueba un cable y un adaptador compatibles que sepas que funcionan.",
            "Comprueba si el conector queda firme y si aparece el indicador de carga.",
            "Inspecciona el puerto con buena luz; no introduzcas objetos metálicos.",
        ),
        privacyNote = "La advertencia indica que la revisión es manual; no confirma que exista una avería.",
    ),
    DeviceModule(
        id = ModuleIds.SIM,
        title = "SIM",
        summary = "Estado, operador y señal",
        status = ModuleStatus.WARNING,
        icon = Icons.Filled.SimCard,
        description = "Consulta el estado de la SIM y, con permiso opcional de Android, el operador, país y fuerza de señal. ICCID e IMSI no se consultan.",
        steps = listOf(
            "Abre Ajustes y entra en Red e Internet o Conexiones.",
            "Selecciona SIM y verifica que la línea esté habilitada.",
            "Si no aparece, apaga el teléfono antes de volver a colocar la tarjeta.",
        ),
        privacyNote = "READ_PHONE_STATE solo se usa para operador y señal; el código no lee ICCID, IMSI ni número telefónico.",
    ),
    DeviceModule(
        id = ModuleIds.SD_CARD,
        title = "Tarjeta SD",
        summary = "Espacio y prueba de 5 MB",
        status = ModuleStatus.WARNING,
        icon = Icons.Filled.SdStorage,
        description = "Lista volúmenes extraíbles, consulta su espacio con StatFs y mide escritura/lectura con un archivo temporal de 5 MB.",
        steps = listOf(
            "Abre Ajustes > Almacenamiento y busca la tarjeta extraíble.",
            "Si no aparece, apaga el teléfono antes de extraerla o recolocarla.",
            "No formatees la tarjeta si necesitas conservar los archivos que contiene.",
        ),
        privacyNote = "La prueba usa solo el directorio privado de DeviceScope en la SD y elimina el archivo temporal al terminar.",
    ),
    DeviceModule(
        id = ModuleIds.CONNECTIONS,
        title = "Conexiones",
        summary = "Wi-Fi, Bluetooth, NFC y GPS",
        status = ModuleStatus.OK,
        icon = Icons.Filled.Wifi,
        description = "Consulta el estado de red, las radios disponibles y el proveedor GPS. Los detalles de Wi-Fi/Bluetooth son opcionales y requieren permisos del sistema.",
        steps = listOf(
            "Comprueba que el modo avión esté desactivado.",
            "Revisa Wi-Fi y Bluetooth desde Ajustes rápidos o Configuración.",
            "Si una conexión falla, desactívala y vuelve a activarla desde el sistema.",
        ),
        privacyNote = "No se escanean redes ni se leen coordenadas. El SSID se muestra solo en Android 13+ con el permiso opcional Dispositivos cercanos; la app no declara ni solicita permiso de ubicación. Bluetooth Connect solo se usa para consultar su estado.",
    ),
    DeviceModule(
        id = ModuleIds.APP_COMPATIBILITY,
        title = "¿Puedo ejecutar esta app?",
        summary = "Analiza un APK sin instalarlo",
        status = ModuleStatus.OK,
        icon = Icons.Filled.Apps,
        description = "Selecciona un archivo APK para comparar su Android mínimo y las características obligatorias de su manifiesto con este dispositivo. También aplica una alerta heurística de RAM para requisitos gráficos avanzados.",
        steps = listOf(
            "Elige un APK con el selector de archivos del sistema.",
            "Revisa minSdkVersion, targetSdkVersion, hardware requerido y OpenGL ES.",
            "Consulta el veredicto y, si lo necesitas, exporta el informe a JSON.",
        ),
        privacyNote = "El archivo se lee y analiza localmente, no se instala ni se envía a Internet. Se elimina la copia temporal del APK al terminar; el JSON solo se guarda en el destino que elijas.",
    ),
)
