package com.drdisagree.teledrive.core.logging

import com.drdisagree.teledrive.core.common.LogSink
import com.drdisagree.teledrive.core.files.AppStoragePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugLogRecorder(private val storagePaths: AppStoragePaths) : LogSink {

    private val lock = Any()
    private val timestamps = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val directory get() = File(storagePaths.filesDir, LOG_DIR)
    private val current get() = File(directory, CURRENT_FILE)
    private val previous get() = File(directory, PREVIOUS_FILE)

    override fun write(level: Char, tag: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            runCatching {
                val line = buildString {
                    append(timestamps.format(Date())).append(' ')
                    append(level).append('/').append(tag).append(": ").append(message).append('\n')
                    throwable?.let { append(it.stackTraceToString()).append('\n') }
                }
                directory.mkdirs()
                if (current.length() > MAX_FILE_BYTES) {
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(line)
            }
        }
    }

    fun export(): File? = synchronized(lock) {
        val sources = listOf(previous, current).filter { it.isFile && it.length() > 0 }
        if (sources.isEmpty()) return null
        val target = File(storagePaths.cacheDir, EXPORT_FILE)
        target.outputStream().use { output ->
            sources.forEach { source -> source.inputStream().use { it.copyTo(output) } }
        }
        target
    }

    fun clear() = synchronized(lock) {
        directory.deleteRecursively()
        File(storagePaths.cacheDir, EXPORT_FILE).delete()
    }

    private companion object {
        const val LOG_DIR = "logs"
        const val CURRENT_FILE = "teledrive.log"
        const val PREVIOUS_FILE = "teledrive.1.log"
        const val EXPORT_FILE = "teledrive-log.txt"
        const val MAX_FILE_BYTES = 1024L * 1024
    }
}
