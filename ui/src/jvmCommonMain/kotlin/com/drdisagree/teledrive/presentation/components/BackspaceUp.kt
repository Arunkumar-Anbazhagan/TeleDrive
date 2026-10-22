package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

fun Modifier.backspaceUp(onUp: (() -> Unit)?): Modifier = onPreviewKeyEvent { event ->
    val up = event.key == Key.Backspace ||
            event.isAltPressed && event.key == Key.DirectionLeft
    if (onUp == null || !up || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    KeyboardHandoff.armed = true
    onUp()
    true
}
