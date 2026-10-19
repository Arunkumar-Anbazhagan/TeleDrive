package com.drdisagree.teledrive.core.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.dao.PendingDeleteDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.repository.FileManifestPublisher
import com.drdisagree.teledrive.data.repository.FolderStateSynchronizer
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Rows keep their flag until the caption is accepted, so a failed edit is retried rather than
 * reverted by the next sync.
 */
class PublishOutboxDrainer(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val pendingDeleteDao: PendingDeleteDao,
    private val telegramClient: TelegramClient,
    private val manifestPublisher: FileManifestPublisher,
    private val folderStateSynchronizer: FolderStateSynchronizer,
    private val tombstoneDao: FolderTombstoneDao,
    private val database: TeleDriveDatabase
) {

    suspend fun drain(isStopped: () -> Boolean): Boolean {
        if (!replayDeletes(isStopped)) return false

        while (true) {
            if (isStopped()) return false
            val batch = fileDao.pendingPublish(BATCH_SIZE)
            if (batch.isEmpty()) break

            var index = 0
            while (index < batch.size) {
                val entity = batch[index++]
                if (isStopped()) return false
                when (val result = manifestPublisher.publish(entity)) {
                    is AppResult.Success -> clearIfUnchanged(entity)
                    is AppResult.Failure -> {
                        val error = result.error
                        if (error is AppError.RateLimited) {
                            if (error.retryAfterSeconds > INLINE_WAIT_LIMIT) return false
                            delay(((error.retryAfterSeconds + 1) * 1000L).milliseconds)
                            index--
                            continue
                        }
                        if (!isPermanent(error)) return false
                        SafeLog.w(TAG, "Dropping unpublishable manifest: $error")
                        clearIfUnchanged(entity)
                    }
                }
            }
        }

        repeat(MAX_FOLDER_PUSHES) {
            if (folderDao.pendingPublishCount() == 0) return true
            val before = folderSnapshot()
            val pushed = runCatching { folderStateSynchronizer.push() }
                .onFailure { SafeLog.w(TAG, "Folder state push failed", it) }
                .isSuccess
            if (!pushed) return false
            database.inImmediateTransaction {
                if (folderSnapshot() == before) folderDao.clearPendingPublish()
            }
        }
        return folderDao.pendingPublishCount() == 0
    }

    /** An edit made while its caption was being written keeps the row pending for another pass. */
    private suspend fun clearIfUnchanged(published: FileEntity) {
        database.inImmediateTransaction {
            val current = fileDao.byId(published.id) ?: return@inImmediateTransaction
            if (current.copy(pendingPublish = published.pendingPublish) == published) {
                fileDao.clearPendingPublish(published.id)
            }
        }
    }

    private suspend fun folderSnapshot() = Pair(
        folderDao.allFoldersAnyOwner().map { it.copy(pendingPublish = false) }.sortedBy { it.id },
        tombstoneDao.all().sortedBy { it.id }
    )

    /**
     * Deleting a message that is already gone is accepted, so replaying after a crash costs
     * nothing.
     */
    private suspend fun replayDeletes(isStopped: () -> Boolean): Boolean {
        while (true) {
            if (isStopped()) return false
            val batch = pendingDeleteDao.oldest(BATCH_SIZE)
            if (batch.isEmpty()) return true

            for ((chatId, pending) in batch.groupBy { it.chatId }) {
                val messageIds = pending.map { it.messageId }
                try {
                    telegramClient.deleteMessages(chatId, messageIds)
                } catch (e: TelegramException) {
                    if (e.code !in PERMANENT_CODES) {
                        SafeLog.w(TAG, "Replaying ${messageIds.size} deletes failed", e)
                        return false
                    }
                    SafeLog.w(TAG, "Dropping unrepeatable delete: ${e.message}")
                }
                fileDao.deleteByIds(pending.map { it.fileId })
                pendingDeleteDao.clear(chatId, messageIds)
            }
        }
    }

    /** A rejected edit never succeeds on retry, unlike a rate limit or an outage. */
    private fun isPermanent(error: AppError): Boolean =
        error is AppError.TelegramError && error.code in PERMANENT_CODES

    private companion object {
        const val TAG = "PublishOutbox"
        const val BATCH_SIZE = 100
        const val INLINE_WAIT_LIMIT = 60
        const val MAX_FOLDER_PUSHES = 3
        val PERMANENT_CODES = 400..499
    }
}
