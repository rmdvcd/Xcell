package com.devicescope.app.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devicescope.app.MainActivity
import org.junit.Rule
import org.junit.Test

class SmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun rendersText() {
        composeRule.setContent {
            Text("Hola")
        }
        composeRule.onNodeWithText("Hola").assertIsDisplayed()
    }
}
