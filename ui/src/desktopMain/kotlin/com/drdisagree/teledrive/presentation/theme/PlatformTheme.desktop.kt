package com.drdisagree.teledrive.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
actual fun systemDarkTheme(): Boolean {
    val fallback = isSystemInDarkTheme()
    val linuxDark by LinuxColorScheme.prefersDark.collectAsState()
    return linuxDark ?: fallback
}

@Composable
internal actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? =
    null

@Composable
internal actual fun PlatformThemeSideEffects(darkTheme: Boolean) {
}
