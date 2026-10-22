package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderEntry
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderTombstone
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DuplicateFolderMergeTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val folders = database.folderDao()
    private val files = database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `same-name folders merge into the older one, subfolders included`() = runBlocking {
        folders.upsert(folder("dcim-a", "DCIM", createdAt = 1_000))
        folders.upsert(folder("dcim-b", "dcim", createdAt = 2_000))
        folders.upsert(folder("cam-a", "Camera", parentId = "dcim-a", createdAt = 1_000))
        folders.upsert(folder("cam-b", "Camera", parentId = "dcim-b", createdAt = 2_000))
        files.upsert(file("a", "dcim-a"))
        files.upsert(file("b", "dcim-b"))
        files.upsert(file("c", "cam-b"))

        val merged = harness.folderMerger.mergeDuplicates(TEST_CHAT)

        assertEquals(2, merged)
        assertNull(folders.byId("dcim-b"))
        assertNull(folders.byId("cam-b"))
        assertEquals("dcim-a", files.byId("b")!!.folderId)
        assertEquals("cam-a", files.byId("c")!!.folderId)
        assertEquals("dcim-a", folders.byId("cam-a")!!.parentId)
        assertNotNull(database.folderTombstoneDao().idIfDeleted("dcim-b"))
        assertNotNull(database.folderTombstoneDao().idIfDeleted("cam-b"))
    }

    @Test
    fun `a duplicate deleted on another device hands its files over instead of dropping them`() =
        runBlocking {
            folders.upsert(folder("local", "DCIM", createdAt = 2_000))
            files.upsert(file("photo", "local"))
            harness.telegram.publishFolderState(
                RemoteFolderState(
                    folders = listOf(
                        RemoteFolderEntry(
                            id = "remote",
                            name = "DCIM",
                            createdAt = 1_000,
                            modifiedAt = 1_000,
                            changedAt = 3_000
                        )
                    ),
                    deleted = listOf(RemoteFolderTombstone("local", 4_000))
                )
            )

            harness.newSync().fullResync()

            assertNull(folders.byId("local"))
            assertEquals("remote", files.byId("photo")!!.folderId)
        }

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        createdAt: Long
    ) = FolderEntity(
        id = id,
        chatId = TEST_CHAT,
        parentId = parentId,
        name = name,
        createdAt = createdAt,
        modifiedAt = createdAt,
        changedAt = createdAt
    )

    private fun file(id: String, folderId: String) = FileEntity(
        id = id,
        folderId = folderId,
        name = "$id.jpg",
        sizeBytes = 10,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = null,
        contentHash = null,
        chatId = TEST_CHAT,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.NONE,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )
}
