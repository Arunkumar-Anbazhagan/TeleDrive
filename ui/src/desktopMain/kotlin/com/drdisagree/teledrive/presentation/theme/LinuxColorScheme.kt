package com.drdisagree.teledrive.presentation.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

internal object LinuxColorScheme {

    private val dark = MutableStateFlow<Boolean?>(null)

    val prefersDark: StateFlow<Boolean?> by lazy {
        if (System.getProperty("os.name").orEmpty().startsWith("Linux")) start()
        dark.asStateFlow()
    }

    private fun start() {
        dark.value = readPortal() ?: readGnomeSetting()
        Thread(::watchPortal, "color-scheme-watch").apply { isDaemon = true }.start()
    }

    private fun readPortal(): Boolean? =
        run(GDBUS, *PORTAL_CALL, "$PORTAL_INTERFACE.ReadOne", NAMESPACE, KEY)
            ?.let(::parseColorScheme)
            ?: run(GDBUS, *PORTAL_CALL, "$PORTAL_INTERFACE.Read", NAMESPACE, KEY)
                ?.let(::parseColorScheme)

    private fun readGnomeSetting(): Boolean? =
        run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
            ?.let { value ->
                when {
                    "prefer-dark" in value -> true
                    "default" in value || "prefer-light" in value -> false
                    else -> null
                }
            }

    private fun watchPortal() {
        runCatching {
            val process = ProcessBuilder(
                GDBUS, "monitor", "--session",
                "--dest", PORTAL_DEST,
                "--object-path", PORTAL_PATH
            ).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { "SettingChanged" in it && NAMESPACE in it && KEY in it }
                    .forEach { line -> parseColorScheme(line)?.let { dark.value = it } }
            }
        }
    }

    internal fun parseColorScheme(output: String): Boolean? =
        COLOR_SCHEME_VALUE.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { value ->
            when (value) {
                PREFER_DARK -> true
                NO_PREFERENCE, PREFER_LIGHT -> false
                else -> null
            }
        }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().readText()
    }.getOrNull()

    private const val GDBUS = "gdbus"
    private const val PORTAL_DEST = "org.freedesktop.portal.Desktop"
    private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    private const val PORTAL_INTERFACE = "org.freedesktop.portal.Settings"
    private val PORTAL_CALL = arrayOf(
        "call", "--session", "--dest", PORTAL_DEST, "--object-path", PORTAL_PATH, "--method"
    )
    private const val NAMESPACE = "org.freedesktop.appearance"
    private const val KEY = "color-scheme"
    private val COLOR_SCHEME_VALUE = Regex("""uint32 (\d+)""")
    private const val NO_PREFERENCE = 0
    private const val PREFER_DARK = 1
    private const val PREFER_LIGHT = 2
    private const val COMMAND_TIMEOUT_SECONDS = 3L
}
