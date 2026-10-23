package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldMergeTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val telegram = harness.telegram
    private val files = database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a waiting favorite survives a rename made on another device`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg"))
        files.upsert(files.byId("f1")!!.copy(isFavorite = true, pendingPublish = true))

        syncRemote(manifest("f1", "b.jpg", modifiedAt = 3_000))

        val row = files.byId("f1")!!
        assertEquals("b.jpg", row.name)
        assertTrue(row.isFavorite)
        assertTrue(row.pendingPublish)
    }

    @Test
    fun `a waiting rename survives a favorite set on another device`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg"))
        files.rename("f1", "mine.jpg", 2_500)
        files.markPendingPublish(listOf("f1"))

        syncRemote(manifest("f1", "a.jpg", modifiedAt = 3_000).copy(favorite = true))

        val row = files.byId("f1")!!
        assertEquals("mine.jpg", row.name)
        assertTrue(row.isFavorite)
    }

    @Test
    fun `when both sides rename the newer edit wins`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg"))
        files.rename("f1", "mine.jpg", 5_000)
        files.markPendingPublish(listOf("f1"))
        syncRemote(manifest("f1", "theirs.jpg", modifiedAt = 3_000))
        assertEquals("mine.jpg", files.byId("f1")!!.name)

        files.rename("f1", "mine2.jpg", 5_500)
        syncRemote(manifest("f1", "theirs2.jpg", modifiedAt = 9_000))
        assertEquals("theirs2.jpg", files.byId("f1")!!.name)
    }

    @Test
    fun `a restore made on another device reaches this one`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg").copy(trashedAt = 500))
        assertEquals(500L, files.byId("f1")!!.trashedAt)

        syncRemote(manifest("f1", "a.jpg", modifiedAt = 3_000))

        assertNull(files.byId("f1")!!.trashedAt)
    }

    @Test
    fun `a row from before this change keeps the old whole-row rule`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg"))
        files.upsert(
            files.byId("f1")!!.copy(
                name = "mine.jpg",
                modifiedAt = 5_000,
                pendingPublish = true,
                remoteBase = null
            )
        )

        syncRemote(manifest("f1", "a.jpg", modifiedAt = 3_000).copy(favorite = true))

        val row = files.byId("f1")!!
        assertEquals("mine.jpg", row.name)
        assertFalse(row.isFavorite)
    }

    @Test
    fun `a published edit becomes the base for the next merge`() = runBlocking {
        syncRemote(manifest("f1", "a.jpg"))
        files.rename("f1", "mine.jpg", 2_500)
        files.markPendingPublish(listOf("f1"))

        assertTrue(drainer().drain(isStopped = { false }))
        assertFalse(files.byId("f1")!!.pendingPublish)
        assertEquals("mine.jpg", FileBase.fromText(files.byId("f1")!!.remoteBase)!!.name)

        files.upsert(files.byId("f1")!!.copy(isHidden = true, pendingPublish = true))
        syncRemote(manifest("f1", "mine.jpg", modifiedAt = 4_000).copy(favorite = true))

        val row = files.byId("f1")!!
        assertEquals("mine.jpg", row.name)
        assertTrue(row.isHidden)
        assertTrue(row.isFavorite)
    }

    private suspend fun syncRemote(remote: RemoteFileManifest) {
        telegram.documents = listOf(document(20, harness.caption(remote)))
        harness.newSync().fullResync()
    }

    private fun drainer() = PublishOutboxDrainer(
        files,
        database.folderDao(),
        database.pendingDeleteDao(),
        telegram,
        FileManifestPublisher(telegram, harness.codec, harness.folderPaths, database.filePartDao()),
        harness.folderState,
        database.folderTombstoneDao(),
        database
    )
}
