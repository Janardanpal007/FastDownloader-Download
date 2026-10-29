package com.fastdownloader.app.engine

import java.util.Locale

/** Sizes, speeds and times as the desktop app shows them. */
object Format {
    fun size(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long) = "${size(bytesPerSecond)}/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> ""
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
    }
}
