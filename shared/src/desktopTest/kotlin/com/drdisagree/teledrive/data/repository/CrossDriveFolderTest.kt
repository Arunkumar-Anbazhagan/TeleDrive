package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.crypto.SecureFileDeleter
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderEntry
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossDriveFolderTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val folders = database.folderDao()
    private val files = database.fileDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `switching drives mid sync keeps new folders in the drive being synced`() = runBlocking {
        telegram.documents = listOf(
            document(
                20,
                harness.caption(manifest("f1", "msgstore.db", folderPath = "WhatsApp/Databases"))
            )
        )
        telegram.onFetch = {
            harness.settings.update { it.copy(storageChatId = OTHER_CHAT) }
        }

        harness.newSync().fullResync()

        val folderId = files.byId("f1")!!.folderId
        assertNotNull(folderId)
        assertEquals(TEST_CHAT, folders.byId(folderId!!)!!.chatId)
        assertTrue(folders.allFolders(OTHER_CHAT).isEmpty())
    }

    @Test
    fun `a folder id from another drive is not reused for this drive`() = runBlocking {
        folders.upsert(folder("dbs", "Databases", OTHER_CHAT))
        telegram.documents = listOf(
            document(
                20,
                harness.caption(
                    manifest("f1", "msgstore.db", folderPath = "Databases", folderId = "dbs")
                )
            )
        )

        harness.newSync().fullResync()

        assertEquals(OTHER_CHAT, folders.byId("dbs")!!.chatId)
        val folderId = files.byId("f1")!!.folderId!!
        assertNotEquals("dbs", folderId)
        assertEquals(TEST_CHAT, folders.byId(folderId)!!.chatId)
    }

    @Test
    fun `pulling folder state never takes a folder from another drive`() = runBlocking {
        folders.upsert(folder("dbs", "Databases", OTHER_CHAT))
        telegram.publishFolderState(
            RemoteFolderState(
                folders = listOf(
                    RemoteFolderEntry(
                        id = "dbs",
                        name = "Databases",
                        createdAt = 500,
                        modifiedAt = 9_000,
                        changedAt = 9_000
                    )
                )
            )
        )

        harness.newSync().fullResync()

        assertEquals(OTHER_CHAT, folders.byId("dbs")!!.chatId)
    }

    @Test
    fun `stray files move back into their own drive and the empty copy is removed`() = runBlocking {
        folders.upsert(folder("own", "Databases", TEST_CHAT))
        folders.upsert(folder("stray", "Databases", OTHER_CHAT))
        files.upsert(file("f1", folderId = "stray", chatId = TEST_CHAT))

        repair().refileStrayFiles()

        assertEquals("own", files.byId("f1")!!.folderId)
        assertTrue(files.byId("f1")!!.pendingPublish)
        assertNull(folders.byId("stray"))
        assertNotNull(database.folderTombstoneDao().idIfDeleted("stray"))
    }

    @Test
    fun `deleting a folder in one drive leaves another drive's files alone`() = runBlocking {
        harness.settings.update { it.copy(storageChatId = OTHER_CHAT) }
        folders.upsert(folder("stray", "Databases", OTHER_CHAT).copy(trashedAt = 5_000))
        files.upsert(
            file("f1", folderId = null, chatId = TEST_CHAT)
                .copy(trashedAt = 5_000, preTrashFolderId = "stray")
        )

        trash().deleteFolderPermanently("stray")

        assertNotNull(files.byId("f1"))
        assertTrue(telegram.deletedMessages.isEmpty())
    }

    @Test
    fun `emptying the trash only empties the open drive`() = runBlocking {
        harness.settings.update { it.copy(storageChatId = OTHER_CHAT) }
        files.upsert(file("f1", folderId = null, chatId = TEST_CHAT).copy(trashedAt = 5_000))

        trash().emptyTrash()

        assertNotNull(files.byId("f1"))
        assertTrue(telegram.deletedMessages.isEmpty())
    }

    private fun repair() = FolderOwnershipRepair(
        fileDao = files,
        folderDao = folders,
        channelDao = database.storageChannelDao(),
        settingsRepository = harness.settings,
        folderPathResolver = harness.folderPaths,
        tombstoneDao = database.folderTombstoneDao(),
        publishScheduler = harness.scheduler
    )

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

    private fun folder(id: String, name: String, chatId: Long) = FolderEntity(
        id = id,
        chatId = chatId,
        parentId = null,
        name = name,
        createdAt = 1_000,
        modifiedAt = 1_000,
        changedAt = 1_000
    )

    private fun file(id: String, folderId: String?, chatId: Long) = FileEntity(
        id = id,
        folderId = folderId,
        name = "$id.db",
        sizeBytes = 10,
        mimeType = "application/octet-stream",
        category = FileCategory.OTHER,
        localPath = null,
        contentHash = null,
        chatId = chatId,
        messageId = 40,
        remoteFileId = "remote-40",
        remoteUniqueId = "unique-40",
        backupState = BackupState.BACKED_UP,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )

    private companion object {
        const val OTHER_CHAT = 88L
    }
}
