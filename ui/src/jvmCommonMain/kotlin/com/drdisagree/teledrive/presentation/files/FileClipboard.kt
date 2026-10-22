package com.drdisagree.teledrive.presentation.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class FileClipboard {

    private val content = MutableStateFlow<CutItems?>(null)

    fun cut(items: CutItems) {
        content.value = items
    }

    fun take(): CutItems? = content.getAndUpdate { null }
}
