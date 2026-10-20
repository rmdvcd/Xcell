package com.devicescope.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable

@Composable
fun DeviceScopeTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DeviceScopeDarkColors else DeviceScopeLightColors,
        typography = Typography(),
        content = content,
    )
}
