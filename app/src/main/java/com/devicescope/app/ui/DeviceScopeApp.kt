package com.devicescope.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devicescope.app.data.DeviceDiagnosticSnapshot
import com.devicescope.app.data.ModuleIds
import com.devicescope.app.data.calculateHealthScore
import com.devicescope.app.data.deviceModules
import com.devicescope.app.ui.screens.DashboardScreen
import com.devicescope.app.ui.screens.GeneralDiagnosticsScreen
import com.devicescope.app.ui.screens.ModuleDetailScreen
import com.devicescope.app.ui.theme.DeviceScopeTheme

private object Routes {
    const val DASHBOARD = "dashboard"
    const val DIAGNOSTICS = "diagnostics"
    const val MODULE = "module/{moduleId}"
    const val MODULE_ID_ARGUMENT = "moduleId"
}

@Composable
fun DeviceScopeApp() {
    val systemDarkTheme = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(systemDarkTheme) }

    DeviceScopeTheme(darkTheme = darkTheme) {
        DeviceScopeContent(
            darkTheme = darkTheme,
            onToggleTheme = { darkTheme = !darkTheme },
        )
    }
}

@Composable
private fun DeviceScopeContent(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    var diagnosticSnapshot by remember { mutableStateOf<DeviceDiagnosticSnapshot?>(null) }
    val navController = rememberNavController()
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
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
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                    healthScore = diagnosticSnapshot?.let(::calculateHealthScore) ?: 92,
                    lastCheckedAtMillis = diagnosticSnapshot?.capturedAtMillis,
                    onOpenModule = { module ->
                        if (module.id == ModuleIds.GENERAL) {
                            navController.navigate(Routes.DIAGNOSTICS) {
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate("module/${module.id}") {
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                GeneralDiagnosticsScreen(
                    snapshot = diagnosticSnapshot,
                    onSnapshotReady = { diagnosticSnapshot = it },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.MODULE,
                arguments = listOf(navArgument(Routes.MODULE_ID_ARGUMENT) { type = NavType.StringType }),
            ) { backStackEntry ->
                val moduleId = backStackEntry.arguments?.getString(Routes.MODULE_ID_ARGUMENT)
                val module = deviceModules.firstOrNull { it.id == moduleId }
                if (module != null) {
                    ModuleDetailScreen(
                        module = module,
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    UnknownModuleScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun UnknownModuleScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No se encontró este módulo", color = MaterialTheme.colorScheme.onBackground)
        TextButton(onClick = onBack) {
            Text("Volver")
        }
    }
}
