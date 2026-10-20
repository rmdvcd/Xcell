package com.devicescope.app.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ConnectionSnapshot(
    val activeNetwork: String,
    val wifiEnabled: Boolean?,
    val wifiConnected: Boolean,
    val ssid: String?,
    val wifiRssi: Int?,
    val bluetoothEnabled: Boolean?,
    val bluetoothAvailable: Boolean,
    val nfcAvailable: Boolean,
    val nfcEnabled: Boolean?,
    val gpsProviderEnabled: Boolean?,
)

@Composable
internal fun ColumnScope.ConnectionsDiagnosticContent() {
    val context = LocalContext.current
    val hasWifiFeature = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    }
    val hasBluetoothFeature = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
    }
    val wifiPermission = remember(context) { wifiDetailsPermission() }
    var hasWifiPermission by remember(context) { mutableStateOf(hasPermission(context, wifiPermission)) }
    var hasBluetoothPermission by remember(context) {
        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT))
    }
    var snapshot by remember { mutableStateOf<ConnectionSnapshot?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasWifiPermission = hasPermission(context, wifiPermission)
        hasBluetoothPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        if (result.isNotEmpty()) refreshKey++
    }

    LaunchedEffect(hasWifiPermission, hasBluetoothPermission, refreshKey) {
        isLoading = true
        snapshot = withContext(Dispatchers.IO) {
            readConnectionSnapshot(context.applicationContext, hasWifiPermission, hasBluetoothPermission)
        }
        isLoading = false
    }

    val missingPermissions = buildList {
        if (hasWifiFeature && !hasWifiPermission) add(wifiPermission)
        if (hasBluetoothFeature && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Estado de conexiones",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Consulta el estado actual de las radios y del proveedor GPS, sin activar conexiones ni escanear redes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(15.dp))

        snapshot?.let { data ->
            ConnectionStatusCard(data.activeNetwork)
            Spacer(Modifier.height(11.dp))
            ConnectionDetailCard(
                icon = Icons.Filled.Wifi,
                title = "Wi-Fi",
                status = when {
                    data.wifiConnected -> ModuleStatus.OK
                    data.wifiEnabled == true -> ModuleStatus.WARNING
                    data.wifiEnabled == false -> ModuleStatus.WARNING
                    else -> ModuleStatus.ERROR
                },
                mainValue = when {
                    data.wifiConnected -> "Conectado"
                    data.wifiEnabled == true -> "Encendido · sin conexión"
                    data.wifiEnabled == false -> "Apagado"
                    else -> "No disponible"
                },
                details = buildList {
                    if (data.wifiConnected) {
                        add("SSID: ${data.ssid ?: if (hasWifiPermission) "No disponible" else "Permiso requerido"}")
                        add("Señal: ${data.wifiRssi?.let { "$it dBm · ${wifiBars(it)} de 4 barras" } ?: if (hasWifiPermission) "No disponible" else "Permiso requerido"}")
                    } else if (hasWifiFeature && !hasWifiPermission) {
                        add("Permite el acceso opcional para mostrar SSID y señal.")
                    }
                },
            )
            Spacer(Modifier.height(11.dp))
            ConnectionDetailCard(
                icon = Icons.Filled.Bluetooth,
                title = "Bluetooth",
                status = when {
                    !data.bluetoothAvailable -> ModuleStatus.WARNING
                    data.bluetoothEnabled == true -> ModuleStatus.OK
                    data.bluetoothEnabled == false -> ModuleStatus.WARNING
                    else -> ModuleStatus.ERROR
                },
                mainValue = when {
                    !data.bluetoothAvailable -> "No disponible en este dispositivo"
                    data.bluetoothEnabled == true -> "Encendido"
                    data.bluetoothEnabled == false -> "Apagado"
                    else -> "Permiso requerido"
                },
                details = if (hasBluetoothFeature && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                    listOf("Android requiere el permiso Bluetooth Connect para consultar el estado.")
                } else {
                    emptyList()
                },
            )
            Spacer(Modifier.height(11.dp))
            ConnectionDetailCard(
                icon = Icons.Filled.Nfc,
                title = "NFC",
                status = if (data.nfcAvailable) ModuleStatus.OK else ModuleStatus.WARNING,
                mainValue = when {
                    !data.nfcAvailable -> "No disponible"
                    data.nfcEnabled == true -> "Disponible · encendido"
                    data.nfcEnabled == false -> "Disponible · apagado"
                    else -> "Estado no disponible"
                },
                details = emptyList(),
            )
            Spacer(Modifier.height(11.dp))
            ConnectionDetailCard(
                icon = Icons.Filled.GpsFixed,
                title = "GPS",
                status = when (data.gpsProviderEnabled) {
                    true -> ModuleStatus.OK
                    false -> ModuleStatus.WARNING
                    null -> ModuleStatus.ERROR
                },
                mainValue = when (data.gpsProviderEnabled) {
                    true -> "Proveedor GPS activo"
                    false -> "Proveedor GPS inactivo"
                    null -> "Estado no disponible"
                },
                details = listOf("Solo se consulta si el proveedor está habilitado; no se obtiene ni guarda ubicación."),
            )
        }

        if (missingPermissions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ConnectionPermissionCard(
                wifiPermissionNeeded = hasWifiFeature && !hasWifiPermission,
                bluetoothPermissionNeeded = hasBluetoothFeature && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission,
                onRequest = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
            )
        }

        if (isLoading && snapshot == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        TextButton(
            onClick = { refreshKey++ },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !isLoading,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(5.dp))
            Text("Actualizar estado")
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Text(
                    text = "DeviceScope no activa Wi-Fi, Bluetooth, NFC ni GPS. No busca dispositivos cercanos y no solicita permiso para leer coordenadas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionStatusCard(activeNetwork: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Red activa",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                Text(
                    text = activeNetwork,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            StatusPill(
                status = if (activeNetwork == "Sin red activa") ModuleStatus.WARNING else ModuleStatus.OK,
                label = if (activeNetwork == "Sin red activa") "SIN RED" else "ACTIVA",
            )
        }
    }
}

@Composable
private fun ConnectionDetailCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: ModuleStatus,
    mainValue: String,
    details: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(status = status)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = mainValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                details.forEach { detail ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionPermissionCard(
    wifiPermissionNeeded: Boolean,
    bluetoothPermissionNeeded: Boolean,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Detalles opcionales",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            val needed = buildList {
                if (wifiPermissionNeeded) {
                    add(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Dispositivos cercanos para SSID/señal"
                        } else {
                            "Ubicación precisa para que Android revele SSID/señal (sin leer coordenadas)"
                        },
                    )
                }
                if (bluetoothPermissionNeeded) add("Bluetooth Connect para consultar su estado")
            }.joinToString(" y ")
            Text(
                text = "Permisos opcionales: $needed. No se escanean redes ni dispositivos y no se obtienen coordenadas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(11.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir detalles de conexión")
            }
        }
    }
}

private fun readConnectionSnapshot(
    context: Context,
    hasWifiPermission: Boolean,
    hasBluetoothPermission: Boolean,
): ConnectionSnapshot {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = connectivityManager?.activeNetwork
    val capabilities = if (connectivityManager != null && network != null) {
        connectivityManager.getNetworkCapabilities(network)
    } else {
        null
    }
    val activeNetwork = when {
        capabilities == null -> "Sin red activa"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Conectada"
    }
    val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull()
    val wifiInfo = if (hasWifiPermission && wifiConnected) {
        runCatching { wifiManager?.connectionInfo }.getOrNull()
    } else {
        null
    }
    val ssid = wifiInfo?.ssid
        ?.trim('"')
        ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    val rssi = wifiInfo?.rssi?.takeIf { it in -100..-1 }

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothManager?.adapter
        } else {
            BluetoothAdapter.getDefaultAdapter()
        }
    }.getOrNull()
    val bluetoothAvailable = bluetoothAdapter != null
    val bluetoothEnabled = if (!bluetoothAvailable || !hasBluetoothPermission) {
        null
    } else {
        runCatching { bluetoothAdapter?.isEnabled }.getOrNull()
    }

    val nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    val nfcAvailable = nfcAdapter != null
    val nfcEnabled = if (nfcAdapter != null) runCatching { nfcAdapter.isEnabled }.getOrNull() else null

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val gpsEnabled = runCatching { locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()

    return ConnectionSnapshot(
        activeNetwork = activeNetwork,
        wifiEnabled = wifiEnabled,
        wifiConnected = wifiConnected,
        ssid = ssid,
        wifiRssi = rssi,
        bluetoothEnabled = bluetoothEnabled,
        bluetoothAvailable = bluetoothAvailable,
        nfcAvailable = nfcAvailable,
        nfcEnabled = nfcEnabled,
        gpsProviderEnabled = gpsEnabled,
    )
}

private fun wifiDetailsPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.NEARBY_WIFI_DEVICES
} else {
    Manifest.permission.ACCESS_FINE_LOCATION
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun wifiBars(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -67 -> 3
    rssi >= -75 -> 2
    rssi >= -85 -> 1
    else -> 0
}
