package com.drdisagree.teledrive.core.common

import kotlin.concurrent.Volatile

/** Messages must never contain phone numbers, API credentials or file contents. */
object SafeLog {

    var verbose: Boolean = false

    @Volatile
    var sink: LogSink? = null

    fun d(tag: String, message: String) {
        if (!verbose) return
        logDebug(tag, message)
        sink?.write('D', tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        logWarn(tag, message, throwable)
        sink?.write('W', tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        logError(tag, message, throwable)
        sink?.write('E', tag, message, throwable)
    }
}
