package com.devicescope.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SimCard
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
import com.devicescope.app.data.DeviceReading
import com.devicescope.app.data.ModuleStatus
import com.devicescope.app.data.SimSnapshot
import com.devicescope.app.data.TelephonyDiagnosticReader
import com.devicescope.app.data.valueOrNull
import com.devicescope.app.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ColumnScope.SimDiagnosticContent() {
    val context = LocalContext.current
    val hasTelephonyFeature = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
    var hasPhonePermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var snapshot by remember { mutableStateOf<SimSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPhonePermission = granted
        refreshKey++
    }

    LaunchedEffect(hasPhonePermission, refreshKey) {
        isLoading = true
        loadError = null
        when (val result = withContext(Dispatchers.IO) {
            TelephonyDiagnosticReader.read(context.applicationContext, hasPhonePermission)
        }) {
            is DeviceReading.Available -> {
                snapshot = result.value
                loadError = null
            }
            DeviceReading.NotAvailable -> {
                snapshot = null
                loadError = "No se pudo consultar la telefonía de este dispositivo."
            }
            is DeviceReading.Failed -> {
                snapshot = null
                loadError = if (result.error is SecurityException) {
                    "Android no autorizó la consulta de telefonía."
                } else {
                    "No se pudo consultar la telefonía de este dispositivo."
                }
            }
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
    ) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Estado de la tarjeta SIM",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "Consulta el estado de la línea, el operador, el país y la fuerza de señal del dispositivo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PrivacyNotice()
        Spacer(Modifier.height(15.dp))

        if (hasTelephonyFeature && !hasPhonePermission) {
            PermissionCard(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                },
            )
            Spacer(Modifier.height(14.dp))
        }

        loadError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(14.dp))
        }

        snapshot?.let { data ->
            SimStatusCard(snapshot = data, isLoading = isLoading)
            Spacer(Modifier.height(13.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SimValueRow(
                        icon = Icons.Filled.SimCard,
                        label = "Operador",
                        value = when {
                            !data.supported -> "No disponible"
                            !hasPhonePermission -> "Permiso requerido"
                            else -> simValue(data.operatorName) { it }
                        },
                    )
                    SimValueRow(
                        icon = Icons.Filled.Info,
                        label = "País de la SIM",
                        value = when {
                            !data.supported -> "No disponible"
                            !hasPhonePermission -> "Permiso requerido"
                            else -> simValue(data.countryIso) { countryLabel(it) }
                        },
                    )
                    SimValueRow(
                        icon = Icons.Filled.CellTower,
                        label = "Fuerza de señal",
                        value = when {
                            !data.supported -> "No disponible"
                            !hasPhonePermission -> "Permiso requerido"
                            else -> signalLabel(
                                data.signalLevel.valueOrNull,
                                data.signalDbm.valueOrNull,
                            )
                        },
                    )
                    if (data.permissionError) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Android no autorizó todas las lecturas de operador/señal. El estado de SIM sigue disponible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { refreshKey++ },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Actualizar datos")
            }
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

        Spacer(Modifier.height(13.dp))
        LocalOnlyNote()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrivacyNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = "Privacidad de la SIM",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No se lee ni se almacena el ICCID ni el IMSI. Android restringe esos identificadores por privacidad; solo se consultan estado, operador, país y señal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Permiso opcional para operador y señal",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "Android requiere permiso de estado del teléfono para estos datos. Sin concederlo, solo se muestra el estado básico de la SIM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir operador y señal")
            }
        }
    }
}

@Composable
private fun SimStatusCard(snapshot: SimSnapshot, isLoading: Boolean) {
    val simState = snapshot.simState.valueOrNull
    val status = when {
        !snapshot.supported -> ModuleStatus.WARNING
        simState == TelephonyManager.SIM_STATE_READY -> ModuleStatus.OK
        simState == TelephonyManager.SIM_STATE_ABSENT -> ModuleStatus.WARNING
        simState == TelephonyManager.SIM_STATE_UNKNOWN || simState == null -> ModuleStatus.WARNING
        else -> ModuleStatus.ERROR
    }
    val stateName = when {
        !snapshot.supported -> "Telefonía no disponible"
        snapshot.simState is DeviceReading.Failed -> "Estado de SIM no disponible"
        else -> simStateName(simState ?: TelephonyManager.SIM_STATE_UNKNOWN)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.SimCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(25.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Estado de SIM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                )
                Text(
                    text = if (isLoading) "Actualizando…" else stateName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            StatusPill(status = status)
        }
    }
}

@Composable
private fun SimValueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LocalOnlyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            text = "La consulta se realiza en este dispositivo. No se solicita acceso a identificadores de SIM.",
            modifier = Modifier.padding(15.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun <T> simValue(reading: DeviceReading<T>, format: (T) -> String): String = when (reading) {
    is DeviceReading.Available -> format(reading.value)
    is DeviceReading.Failed -> if (reading.error is SecurityException) "Permiso requerido" else "No disponible"
    DeviceReading.NotAvailable -> "No disponible"
}

private fun simStateName(state: Int): String = when (state) {
    TelephonyManager.SIM_STATE_ABSENT -> "No hay SIM"
    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN requerido"
    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK requerido"
    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Bloqueada por red"
    TelephonyManager.SIM_STATE_READY -> "Lista"
    TelephonyManager.SIM_STATE_NOT_READY -> "No lista"
    TelephonyManager.SIM_STATE_PERM_DISABLED -> "Deshabilitada permanentemente"
    TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "Error de lectura de SIM"
    else -> "Estado desconocido"
}

private fun countryLabel(countryIso: String?): String {
    if (countryIso.isNullOrBlank()) return "No disponible"
    val code = countryIso.uppercase(Locale.ROOT)
    val name = Locale("", code).getDisplayCountry(Locale.getDefault()).ifBlank { code }
    return "$name ($code)"
}

private fun signalLabel(level: Int?, dbm: Int?): String {
    if (level == null && dbm == null) return "No disponible"
    val levelText = level?.let { "$it de 4 barras" }
    val dbmText = dbm?.let { "$it dBm" }
    return listOfNotNull(levelText, dbmText).joinToString(" · ")
}
