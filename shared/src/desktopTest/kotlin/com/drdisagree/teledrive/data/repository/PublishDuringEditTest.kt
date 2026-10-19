package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PublishDuringEditTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val folders = database.folderDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a rename made while the caption is being written is published too`() = runBlocking {
        telegram.documents = listOf(document(20, harness.caption(manifest("f1", "a.jpg"))))
        harness.newSync().fullResync()
        files.rename("f1", "b.jpg", 5_000)
        files.markPendingPublish(listOf("f1"))
        telegram.onEditCaption = {
            telegram.onEditCaption = {}
            files.rename("f1", "c.jpg", 6_000)
        }

        drainer().drain { false }

        assertFalse(files.byId("f1")!!.pendingPublish)
        assertEquals("c.jpg", harness.codec.decode(telegram.editedCaptions.getValue(20L))!!.name)
    }

    @Test
    fun `a folder renamed during a push is pushed again`() = runBlocking {
        folders.upsert(
            FolderEntity(
                id = "trips", chatId = TEST_CHAT, parentId = null, name = "Trips",
                createdAt = 1_000, modifiedAt = 1_000, changedAt = 1_000
            )
        )
        folders.markPendingPublish()
        telegram.onFetch = {
            telegram.onFetch = {}
            folders.rename("trips", "Holidays", 9_000)
        }

        drainer().drain { false }

        assertEquals(0, folders.pendingPublishCount())
        assertEquals(
            listOf("Holidays"),
            telegram.uploadedFolderState().folders.map { it.name }
        )
    }

    private fun drainer() = PublishOutboxDrainer(
        fileDao = files,
        folderDao = folders,
        pendingDeleteDao = database.pendingDeleteDao(),
        telegramClient = telegram,
        manifestPublisher = FileManifestPublisher(
            telegram, harness.codec, harness.folderPaths, database.filePartDao()
        ),
        folderStateSynchronizer = harness.folderState,
        tombstoneDao = database.folderTombstoneDao(),
        database = database
    )
}
