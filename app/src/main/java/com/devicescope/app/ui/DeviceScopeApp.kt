package com.devicescope.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devicescope.app.data.FullDiagnosticReport
import com.devicescope.app.data.ModuleIds
import com.devicescope.app.ui.screens.DashboardScreen
import com.devicescope.app.ui.screens.FullDiagnosticScreen
import com.devicescope.app.ui.theme.DeviceScopeTheme

private object Routes {
    const val DASHBOARD = "dashboard"
    const val FULL_DIAGNOSTIC = "fullDiagnostic"
}

@Composable
fun DeviceScopeApp() {
    val systemDarkTheme = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(systemDarkTheme) }
    var permissionRequest by rememberSaveable { mutableStateOf(0) }
    DeviceScopeTheme(darkTheme = darkTheme) {
        StartupPermissions(permissionRequest)
        DeviceScopeContent(
            darkTheme = darkTheme,
            onToggleTheme = { darkTheme = !darkTheme },
            onPermissions = { permissionRequest++ },
        )
    }
}

@Composable
private fun DeviceScopeContent(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onPermissions: () -> Unit,
) {
    var lastReport by remember { mutableStateOf<FullDiagnosticReport?>(null) }
    val navController = rememberNavController()
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onPermissions = onPermissions,
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                    lastReport = lastReport,
                    onOpenModule = { moduleId ->
                        when (moduleId) {
                            ModuleIds.FULL_DIAGNOSTIC -> navController.navigate(Routes.FULL_DIAGNOSTIC)
                            ModuleIds.PC_MOVIL -> navController.navigate(PcRoutes.PANEL)
                        }
                    },
                )
            }
            composable(Routes.FULL_DIAGNOSTIC) {
                FullDiagnosticScreen(
                    onBack = { navController.popBackStack() },
                    onReportReady = { lastReport = it },
                )
            }
            composable(PcRoutes.PANEL) {
                PcMovilScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTool = { route -> navController.navigate(route) },
                )
            }
            composable(PcRoutes.KEYBOARD) {
                PcKeyboardToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.MOUSE) {
                PcMouseToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.CAMERA) {
                PcCameraToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.AUDIO) {
                PcAudioToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.TRANSFER) {
                PcTransferToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.VPN) {
                PcVpnToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.RESOURCES) {
                PcResourcesToolScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPanel = { navController.backToPcPanel() },
                )
            }
            composable(PcRoutes.SCANNER) {
                PcScannerToolScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

/** Vuelve al panel del módulo sin apilar pantallas. */
private fun NavHostController.backToPcPanel() {
    popBackStack(PcRoutes.PANEL, inclusive = false)
}
