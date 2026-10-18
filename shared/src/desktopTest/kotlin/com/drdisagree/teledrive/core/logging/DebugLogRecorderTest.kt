package com.drdisagree.teledrive.core.logging

import com.drdisagree.teledrive.core.files.AppStoragePaths
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogRecorderTest {

    private val root = Files.createTempDirectory("teledrive-log").toFile()
    private val recorder = DebugLogRecorder(object : AppStoragePaths {
        override val filesDir = File(root, "files")
        override val cacheDir = File(root, "cache").apply { mkdirs() }
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    })

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `exported log holds each line with its level, tag and stack trace`() {
        recorder.write('D', "Sync", "Sync start chat=1", null)
        recorder.write('W', "Publish", "Caption edit failed", IllegalStateException("boom"))

        val text = recorder.export()!!.readText()

        assertTrue(text.contains("D/Sync: Sync start chat=1"))
        assertTrue(text.contains("W/Publish: Caption edit failed"))
        assertTrue(text.contains("IllegalStateException: boom"))
    }

    @Test
    fun `a long log rolls over and keeps the newest lines`() {
        val line = "x".repeat(1_000)
        repeat(2_500) { recorder.write('D', "Tag", "$it $line", null) }

        val exported = recorder.export()!!

        assertTrue(exported.length() < 2_200_000)
        assertTrue(exported.readText().contains("2499 $line"))
    }

    @Test
    fun `nothing to export before any line or after clearing`() {
        assertNull(recorder.export())

        recorder.write('D', "Tag", "line", null)
        assertNotNull(recorder.export())

        recorder.clear()
        assertNull(recorder.export())
        assertEquals(false, File(root, "files/logs").exists())
    }
}
