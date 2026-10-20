package com.drdisagree.teledrive.presentation.home

data class PendingSync(
    val files: Int = 0,
    val folders: Boolean = false
) {
    val visible: Boolean get() = files > 0 || folders
}
