package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownFieldsTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a caption field this version does not know survives a decode and encode`() {
        val decoded = harness.codec.decode(NEWER_CAPTION)!!

        assertEquals("a.jpg", decoded.name)
        val written = Json.parseToJsonElement(
            harness.codec.encode(decoded, encrypt = false).removePrefix("td1:")
        ).jsonObject
        assertEquals("""{"k":1}""", written.getValue("zz").toString())
        assertEquals("a.jpg", written.getValue("n").toString().trim('"'))
    }

    @Test
    fun `renaming a file written by a newer version keeps its unknown fields`() = runBlocking {
        telegram.documents = listOf(document(20, NEWER_CAPTION))
        harness.newSync().fullResync()
        val dao = database.fileDao()
        dao.rename("f1", "b.jpg", 3_000)

        publisher().publish(dao.byId("f1")!!)

        val written = telegram.editedCaptions.getValue(20)
        assertTrue(written, written.contains(""""zz":{"k":1}"""))
        assertTrue(written, written.contains(""""n":"b.jpg""""))
    }

    @Test
    fun `pushing folder changes keeps fields a newer version wrote`() = runBlocking {
        telegram.publishRawFolderState(
            """{"f":[{"id":"a","n":"Trips","ct":1,"mt":1,"ca":1,"xx":"keep"}],"yy":[1,2]}"""
        )
        harness.newSync().fullResync()
        val folders = database.folderDao()
        folders.update(folders.byId("a")!!.copy(name = "Holidays", changedAt = 5, pendingPublish = true))

        harness.folderState.push()

        val uploaded = Json.parseToJsonElement(
            telegram.uploads.last { it.first == RemoteFolderState.FILE_NAME }.second
        ).jsonObject
        val entry = uploaded.getValue("f").jsonArray.single().jsonObject
        assertEquals("\"keep\"", entry.getValue("xx").toString())
        assertEquals("\"Holidays\"", entry.getValue("n").toString())
        assertEquals("[1,2]", uploaded.getValue("yy").toString())
    }

    private fun publisher() = FileManifestPublisher(
        telegram,
        harness.codec,
        harness.folderPaths,
        database.filePartDao()
    )

    private companion object {
        const val NEWER_CAPTION =
            """td1:{"id":"f1","n":"a.jpg","p":"","m":"image/jpeg","s":10,"ct":1,"mt":2,"zz":{"k":1}}"""
    }
}
