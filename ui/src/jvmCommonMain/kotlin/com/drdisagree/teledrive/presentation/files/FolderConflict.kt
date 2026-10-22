package com.drdisagree.teledrive.presentation.files

import com.drdisagree.teledrive.core.files.ImportSource

data class FolderConflict(
    val names: List<String>,
    val renamed: Map<String, String>,
    val sources: List<ImportSource>,
    val target: String?
)
