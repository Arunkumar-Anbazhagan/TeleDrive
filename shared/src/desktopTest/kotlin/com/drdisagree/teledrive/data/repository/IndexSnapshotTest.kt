package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexSnapshotTest {

    private val harness = SyncHarness()
    private val telegram = harness.telegram
    private val files = harness.database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a full sync of a large drive writes a snapshot and points the folder state at it`() =
        runBlocking {
            telegram.documents = drive(120)

            harness.newSync().fullResync()

            assertEquals(1, snapshotUploads())
            assertNotNull(telegram.uploadedFolderState().indexMessageId)
        }

    @Test
    fun `a small drive gets no snapshot`() = runBlocking {
        telegram.documents = drive(20)

        harness.newSync().fullResync()

        assertEquals(0, snapshotUploads())
    }

    @Test
    fun `a fresh index is filled from the snapshot before the scan starts`() = runBlocking {
        telegram.documents = drive(120)
        harness.newSync().fullResync()
        wipeIndex()

        val countAtEachFirstPage = mutableListOf<Int>()
        telegram.onFetch = { from -> if (from == 0L) countAtEachFirstPage += files.fileCount(TEST_CHAT) }
        harness.newSync().fullResync()

        assertEquals(120, countAtEachFirstPage.last())
        assertEquals(120, files.fileCount(TEST_CHAT))
    }

    @Test
    fun `files deleted since the snapshot are dropped by the scan`() = runBlocking {
        telegram.documents = drive(120)
        harness.newSync().fullResync()
        wipeIndex()
        telegram.documents = drive(120).drop(1)

        harness.newSync().fullResync()

        assertNull(files.byId("f1"))
        assertNotNull(files.byId("f2"))
    }

    @Test
    fun `a snapshot under a day old is not replaced`() = runBlocking {
        telegram.uploadDateSeconds = (System.currentTimeMillis() / 1000).toInt()
        telegram.documents = drive(120)
        harness.newSync().fullResync()

        telegram.documents = drive(121)
        harness.newSync().fullResync()

        assertEquals(1, snapshotUploads())
    }

    @Test
    fun `an outdated snapshot is replaced and the old one removed`() = runBlocking {
        telegram.documents = drive(120)
        harness.newSync().fullResync()
        val first = harness.folderState.indexMessageId!!

        telegram.documents = drive(121)
        harness.newSync().fullResync()

        assertEquals(2, snapshotUploads())
        assertTrue(first in telegram.deletedMessages)
        assertTrue(first != harness.folderState.indexMessageId)
    }

    @Test
    fun `an unchanged drive does not rewrite its snapshot`() = runBlocking {
        telegram.documents = drive(120)
        harness.newSync().fullResync()

        harness.newSync().fullResync()

        assertEquals(1, snapshotUploads())
    }

    private suspend fun wipeIndex() {
        files.deleteByIds((1..200).map { "f$it" })
    }

    private fun snapshotUploads() = telegram.uploads.count { it.first == IndexSnapshotFile.FILE_NAME }

    private fun drive(count: Int) = (1..count).map {
        document(100L + it, harness.caption(manifest("f$it", "file$it.jpg")))
    }
}
