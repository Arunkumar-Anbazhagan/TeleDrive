package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotEntry
import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotFile
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json

class IndexSnapshot(
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val folderStateSynchronizer: FolderStateSynchronizer
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun isSnapshot(document: RemoteDocument): Boolean =
        document.fileName == IndexSnapshotFile.FILE_NAME &&
                document.caption.startsWith(IndexSnapshotFile.MARKER)

    suspend fun load(chatId: Long): List<RemoteDocument> {
        val messageId = folderStateSynchronizer.indexMessageId ?: return emptyList()
        val document = telegramClient.getDocument(chatId, messageId)
            ?.takeIf(::isSnapshot)
            ?: return emptyList()
        var localPath: String? = null
        telegramClient.downloadDocument(document.remoteFileId).collect { event ->
            if (event is TelegramDownloadEvent.Completed) localPath = event.localPath
        }
        val file = localPath?.let(::File)?.takeIf { it.exists() } ?: return emptyList()
        val snapshot = runCatching {
            GZIPInputStream(file.inputStream()).use { input ->
                json.decodeFromString(
                    IndexSnapshotFile.serializer(),
                    input.readBytes().toString(Charsets.UTF_8)
                )
            }
        }.getOrNull() ?: return emptyList()
        if (snapshot.version != IndexSnapshotFile.VERSION || snapshot.chatId != chatId) {
            return emptyList()
        }
        return snapshot.entries.map { it.toDocument(chatId) }
    }

    suspend fun refresh(
        chatId: Long,
        documents: List<RemoteDocument>,
        snapshots: List<RemoteDocument>
    ) {
        if (documents.size < MIN_ENTRIES) return
        val newest = snapshots.maxByOrNull { it.messageId }
        val ageSeconds = newest?.let { System.currentTimeMillis() / 1000 - it.dateSeconds }
        if (ageSeconds != null && ageSeconds < REFRESH_SECONDS) return
        val fingerprint = fingerprintOf(documents)
        if (newest != null && newest.caption.endsWith(fingerprint) &&
            folderStateSynchronizer.indexMessageId == newest.messageId
        ) return

        val staging = File(storagePaths.cacheDir, IndexSnapshotFile.FILE_NAME)
        try {
            val payload = json.encodeToString(
                IndexSnapshotFile.serializer(),
                IndexSnapshotFile(
                    chatId = chatId,
                    entries = documents.sortedBy { it.messageId }.map(IndexSnapshotEntry::of)
                )
            )
            GZIPOutputStream(staging.outputStream()).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            var uploaded: RemoteDocument? = null
            telegramClient.uploadDocument(
                chatId = chatId,
                localPath = staging.absolutePath,
                fileName = IndexSnapshotFile.FILE_NAME,
                mimeType = "application/gzip",
                caption = "${IndexSnapshotFile.MARKER} $fingerprint"
            ).collect { event ->
                if (event is TelegramUploadEvent.Completed) uploaded = event.document
            }
            val written = uploaded ?: return
            folderStateSynchronizer.pointIndexAt(written.messageId)
            if (snapshots.isNotEmpty()) {
                telegramClient.deleteMessages(chatId, snapshots.map { it.messageId })
            }
        } finally {
            staging.delete()
        }
    }

    private fun fingerprintOf(documents: List<RemoteDocument>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        documents.sortedBy { it.messageId }.forEach { document ->
            digest.update(
                "${document.messageId}|${document.uniqueFileId}|${document.caption}\n"
                    .toByteArray(Charsets.UTF_8)
            )
        }
        return digest.digest().take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MIN_ENTRIES = 100
        const val REFRESH_SECONDS = 24L * 60 * 60
        const val FINGERPRINT_BYTES = 8
    }
}
