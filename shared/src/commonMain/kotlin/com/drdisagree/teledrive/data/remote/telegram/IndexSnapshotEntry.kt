package com.drdisagree.teledrive.data.remote.telegram

import com.drdisagree.teledrive.core.telegram.RemoteDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IndexSnapshotEntry(
    @SerialName("m") val messageId: Long,
    @SerialName("r") val remoteFileId: String,
    @SerialName("u") val uniqueFileId: String,
    @SerialName("n") val fileName: String,
    @SerialName("t") val mimeType: String,
    @SerialName("s") val sizeBytes: Long,
    @SerialName("c") val caption: String,
    @SerialName("d") val dateSeconds: Int
) {

    fun toDocument(chatId: Long) = RemoteDocument(
        chatId = chatId,
        messageId = messageId,
        remoteFileId = remoteFileId,
        uniqueFileId = uniqueFileId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        caption = caption,
        dateSeconds = dateSeconds
    )

    companion object {
        fun of(document: RemoteDocument) = IndexSnapshotEntry(
            messageId = document.messageId,
            remoteFileId = document.remoteFileId,
            uniqueFileId = document.uniqueFileId,
            fileName = document.fileName,
            mimeType = document.mimeType,
            sizeBytes = document.sizeBytes,
            caption = document.caption,
            dateSeconds = document.dateSeconds
        )
    }
}
