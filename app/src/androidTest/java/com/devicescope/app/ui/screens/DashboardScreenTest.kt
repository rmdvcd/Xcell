package com.devicescope.app.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.devicescope.app.ui.theme.DeviceScopeTheme
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboardShowsSixModuleCards() {
        composeRule.setContent {
            DeviceScopeTheme(darkTheme = false) {
                DashboardScreen(
                    darkTheme = false,
                    onToggleTheme = {},
                    healthScore = 90,
                    lastCheckedAtMillis = null,
                    onOpenModule = {},
                )
            }
        }

        composeRule.onNodeWithText("6 módulos").assertIsDisplayed()
        composeRule.onAllNodesWithTag("dashboard-module-card").assertCountEquals(6)
    }
}
