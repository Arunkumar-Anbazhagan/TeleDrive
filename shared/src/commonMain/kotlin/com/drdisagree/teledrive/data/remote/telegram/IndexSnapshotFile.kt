package com.drdisagree.teledrive.data.remote.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IndexSnapshotFile(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("chat") val chatId: Long,
    @SerialName("e") val entries: List<IndexSnapshotEntry>
) {

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "teledrive.index.json.gz"
        const val MARKER = "#teledrive-index"
    }
}
