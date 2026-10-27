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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devicescope.app.data.FullDiagnosticReport
import com.devicescope.app.ui.screens.DashboardScreen
import com.devicescope.app.ui.screens.FullDiagnosticScreen
import com.devicescope.app.ui.theme.DeviceScopeTheme

private object Routes {
    const val DASHBOARD = "dashboard"
    const val FULL_DIAGNOSTIC = "fullDiagnostic"
}

@Composable
fun DeviceScopeApp() {
    var darkTheme by rememberSaveable { mutableStateOf(isSystemInDarkTheme()) }
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
                    darkTheme = darkTheme,
                    onToggleTheme = onToggleTheme,
                    lastReport = lastReport,
                    onOpenDiagnostic = { navController.navigate(Routes.FULL_DIAGNOSTIC) },
                )
            }
            composable(Routes.FULL_DIAGNOSTIC) {
                FullDiagnosticScreen(
                    onBack = { navController.popBackStack() },
                    onReportReady = { lastReport = it },
                )
            }
        }
    }
}
