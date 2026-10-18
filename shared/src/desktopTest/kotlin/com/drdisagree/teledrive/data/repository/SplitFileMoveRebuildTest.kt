package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.crypto.SecureFileDeleter
import com.drdisagree.teledrive.core.transfer.FileParts
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.local.entity.FolderTombstoneEntity
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitFileMoveRebuildTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val telegram = harness.telegram
    private val folders = database.folderDao()
    private val files = database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `rebuild after publishing the move keeps the deleted folder gone`() = runBlocking {
        uploadedUnderOldSub()
        moveToNewAndDeleteOld()
        publishMove()

        harness.newSync().fullResync()

        assertEquals(listOf("New"), liveFolderNames())
    }

    @Test
    fun `rebuild before the move is published keeps the deleted folder gone`() = runBlocking {
        uploadedUnderOldSub()
        moveToNewAndDeleteOld()

        harness.newSync().fullResync()

        assertEquals(listOf("New"), liveFolderNames())
    }

    @Test
    fun `rebuild after trashing and emptying the old folder through the app keeps it gone`() =
        runBlocking {
            uploadedUnderOldSub(withFolderId = true)
            val now = 50_000L
            folders.upsert(
                FolderEntity(
                    id = NEW_ID, chatId = TEST_CHAT, parentId = null, name = "New",
                    createdAt = now, modifiedAt = now, changedAt = now
                )
            )
            files.move(listOf(SPLIT_ID), NEW_ID, now)
            files.markPendingPublish(listOf(SPLIT_ID))
            publishMove()
            val trash = trash()
            val old = folders.allFolders(TEST_CHAT).first { it.name == "Old" }
            trash.moveFolderToTrash(old.id)
            trash.emptyTrash()
            harness.folderState.push()
            folders.clearPendingPublish()

            harness.newSync().fullResync()

            assertEquals(listOf("New"), liveFolderNames())
            assertEquals(NEW_ID, files.byId(SPLIT_ID)!!.folderId)
        }

    @Test
    fun `rebuild after only trashing the old folder keeps it out of the file list`() =
        runBlocking {
            uploadedUnderOldSub(withFolderId = true)
            val now = 50_000L
            folders.upsert(
                FolderEntity(
                    id = NEW_ID, chatId = TEST_CHAT, parentId = null, name = "New",
                    createdAt = now, modifiedAt = now, changedAt = now
                )
            )
            files.move(listOf(SPLIT_ID), NEW_ID, now)
            files.markPendingPublish(listOf(SPLIT_ID))
            publishMove()
            trash().moveFolderToTrash(folders.allFolders(TEST_CHAT).first { it.name == "Old" }.id)
            harness.folderState.push()
            folders.clearPendingPublish()

            harness.newSync().fullResync()

            assertEquals(listOf("New"), liveFolderNames())
        }

    private fun trash() = TrashRepositoryImpl(
        storagePaths = harness.storagePaths,
        fileDao = files,
        folderDao = folders,
        backupDao = database.backupDao(),
        thumbnailDao = database.thumbnailDao(),
        telegramClient = telegram,
        secureFileDeleter = SecureFileDeleter(),
        publishScheduler = harness.scheduler,
        pendingDeleteDao = database.pendingDeleteDao(),
        filePartDao = database.filePartDao(),
        activeChannel = harness.activeChannel,
        transferRepository = unused<TransferRepository>(),
        tombstoneDao = database.folderTombstoneDao()
    )

    private suspend fun uploadedUnderOldSub(withFolderId: Boolean = false) {
        val stale = manifest(SPLIT_ID, "movie.mkv", SPLIT_SIZE, "Old/Sub", null)
        telegram.documents = parts(stale)
        harness.newSync().fullResync()
        assertEquals(listOf("Old", "Sub"), liveFolderNames())
        if (withFolderId) {
            val sub = folders.allFolders(TEST_CHAT).first { it.name == "Sub" }
            telegram.documents = parts(stale.copy(folderId = sub.id))
        }
    }

    private fun parts(manifest: RemoteFileManifest) = listOf(
        document(12, harness.caption(partOf(manifest, 2)), FileParts.PART_SIZE),
        document(11, harness.caption(partOf(manifest, 1)), FileParts.PART_SIZE),
        document(10, harness.caption(partOf(manifest, 0)), FileParts.PART_SIZE)
    )

    private suspend fun moveToNewAndDeleteOld() {
        val now = 50_000L
        folders.upsert(
            FolderEntity(
                id = NEW_ID, chatId = TEST_CHAT, parentId = null, name = "New",
                createdAt = now, modifiedAt = now, changedAt = now
            )
        )
        files.move(listOf(SPLIT_ID), NEW_ID, now)
        files.markPendingPublish(listOf(SPLIT_ID))
        val old = folders.allFolders(TEST_CHAT).filter { it.name == "Old" || it.name == "Sub" }
        database.folderTombstoneDao().upsert(old.map { FolderTombstoneEntity(it.id, TEST_CHAT, now) })
        old.sortedByDescending { it.parentId != null }.forEach { folders.delete(it.id) }
    }

    private suspend fun publishMove() {
        val publisher = FileManifestPublisher(
            telegram, harness.codec, harness.folderPaths, database.filePartDao()
        )
        publisher.publish(files.byId(SPLIT_ID)!!)
        files.clearPendingPublish(SPLIT_ID)
        val edited = telegram.editedCaptions.getValue(10L)
        telegram.documents = telegram.documents.map {
            if (it.messageId == 10L) it.copy(caption = edited) else it
        }
    }

    private suspend fun liveFolderNames() =
        folders.allFolders(TEST_CHAT).filter { it.trashedAt == null }.map { it.name }.sorted()

    private fun partOf(manifest: RemoteFileManifest, index: Int) = manifest.copy(
        version = RemoteFileManifest.PART_VERSION,
        partCount = 3,
        partIndex = index,
        partOffset = FileParts.offsetOf(index),
        partSize = FileParts.sizeOf(index, SPLIT_SIZE)
    )

    private companion object {
        const val SPLIT_ID = "split"
        const val NEW_ID = "new-folder"
        val SPLIT_SIZE = FileParts.PART_SIZE * 2 + 1_000
    }
}
