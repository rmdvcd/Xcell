package com.devicescope.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import com.devicescope.app.ui.theme.DeviceScopeTheme
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ApkCompatibilityScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun invalidApkShowsControlledErrorInsteadOfCrashing() {
        val registry = TestActivityResultRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                DeviceScopeTheme(darkTheme = false) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ApkCompatibilityContent(
                            analyzeApk = { _, _ -> throw IOException("APK inválido") },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Seleccionar archivo APK").performClick()
        composeRule.waitForIdle()
        assertTrue(registry.hasLaunched)
        registry.deliver(Uri.parse("content://test/invalid.apk"))

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("No se pudo analizar").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No se pudo analizar").assertIsDisplayed()
        composeRule.onNodeWithText("APK inválido").assertIsDisplayed()
    }

    private class TestActivityResultRegistry : ActivityResultRegistry() {
        private var requestCode = -1

        val hasLaunched: Boolean get() = requestCode != -1

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
        }

        fun deliver(uri: Uri) {
            check(hasLaunched) { "No se lanzó el selector de APK" }
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
    }
}
