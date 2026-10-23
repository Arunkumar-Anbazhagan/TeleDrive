package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.FolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderStateRaceTest {

    private val harness = SyncHarness()
    private val telegram = harness.telegram
    private val folders = harness.database.folderDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `two devices saving at once both keep their folders`() = runBlocking {
        telegram.publishRawFolderState(state("a", "Trips"), messageId = 1_000_000)
        telegram.addOlderFolderState(state("b", "Work"), messageId = 999_999, dateSeconds = 95)

        harness.folderState.pull()

        assertNotNull(folders.byId("a"))
        assertNotNull(folders.byId("b"))
    }

    @Test
    fun `saving merges every recent copy and removes them`() = runBlocking {
        telegram.publishRawFolderState(state("a", "Trips"), messageId = 1_000_000)
        telegram.addOlderFolderState(state("b", "Work"), messageId = 999_999, dateSeconds = 95)
        folders.upsert(folder("c", "New"))

        harness.folderState.push()

        val uploaded = telegram.uploadedFolderState().folders.map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c"), uploaded)
        assertTrue(telegram.deletedMessages.containsAll(listOf(1_000_000L, 999_999L)))
    }

    @Test
    fun `a copy far older than the newest is left alone`() = runBlocking {
        telegram.publishRawFolderState(state("a", "Trips"), messageId = 1_000_000)
        telegram.addOlderFolderState(state("old", "Gone"), messageId = 10, dateSeconds = -1_000)

        harness.folderState.pull()
        harness.folderState.push()

        assertNull(folders.byId("old"))
        assertFalse(telegram.deletedMessages.contains(10L))
    }

    private fun state(id: String, name: String) =
        """{"f":[{"id":"$id","n":"$name","ct":1,"mt":1,"ca":1}]}"""

    private fun folder(id: String, name: String) = FolderEntity(
        id = id,
        chatId = TEST_CHAT,
        parentId = null,
        name = name,
        createdAt = 1,
        modifiedAt = 1,
        changedAt = 2
    )
}
