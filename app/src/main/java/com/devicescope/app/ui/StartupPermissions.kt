package com.devicescope.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.devicescope.app.data.FullDiagnosticBuilder

/** Se pide una vez; no bloquea las herramientas si se deniega algún permiso. */
@Composable
fun StartupPermissions(requestVersion: Int = 0) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("startup_permissions", Context.MODE_PRIVATE) }
    var visible by rememberSaveable { mutableStateOf(!prefs.getBoolean("reviewed_v1", false)) }
    var lastRequest by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(requestVersion) {
        if (requestVersion > lastRequest) {
            visible = true
            lastRequest = requestVersion
        }
    }
    var scanner by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        status = if (result.values.all { it }) "Permisos concedidos." else
            "Hay permisos denegados. Puedes reintentarlo o revisarlos en Ajustes."
    }
    val special = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun open(intent: Intent) {
        runCatching { special.launch(intent) }.onFailure { status = "No se pudo abrir este ajuste." }
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Permisos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Cámara, micrófono, teléfono y dispositivos cercanos para diagnóstico y PC-Móvil. Puedes continuar aunque deniegues permisos.")
                    Checkbox(checked = scanner, onCheckedChange = { scanner = it })
                    Text("Habilitar permisos del escáner (ubicación en Android 12 o anterior; no se leen coordenadas).")
                    Button(onClick = {
                        val permissions = buildList {
                            addAll(FullDiagnosticBuilder.requestedPermissions())
                            add(Manifest.permission.CAMERA)
                            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
                            if (scanner && Build.VERSION.SDK_INT <= 32) {
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }.distinct().filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (permissions.isEmpty()) status = "Permisos concedidos."
                        else runtime.launch(permissions.toTypedArray())
                    }) { Text("Solicitar permisos") }
                    Text("Entrada PC → móvil: habilita DeviceScope en Accesibilidad. Solo funciona en campos editables compatibles.")
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                        Text("Accesibilidad")
                    }
                    Text("VPN: autorización de Android; el túnel solo se inicia desde la herramienta tras elegir las redes.")
                    OutlinedButton(onClick = {
                        val intent = VpnService.prepare(context)
                        if (intent == null) status = "VPN autorizada." else open(intent)
                    }) { Text("Autorizar VPN") }
                    Text("Los archivos se autorizan con el selector del sistema al enviar o guardar; no se necesita acceso a todo el almacenamiento.")
                    TextButton(onClick = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                        Text("Ajustes de la app")
                    }
                    if (status.isNotEmpty()) Text(status)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putBoolean("reviewed_v1", true).apply()
                    visible = false
                }) { Text("Continuar") }
            },
        )
    }
}
