package app.rikka.savecopy

import java.util.Locale

/**
 * Single source of truth for the human readable numbers shown in the download
 * notification, the toast messages and the details dialog.
 */
object FormatUtils {

    private const val KB = 1024.0
    private const val MB = KB * 1024.0
    private const val GB = MB * 1024.0

    /** Formats a byte count, e.g. {@code 1.4 MB}. */
    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < KB) return "$bytes B"
        if (bytes < MB) return String.format(Locale.US, "%.1f KB", bytes / KB)
        if (bytes < GB) return String.format(Locale.US, "%.1f MB", bytes / MB)
        return String.format(Locale.US, "%.2f GB", bytes / GB)
    }

    /** Formats a transfer rate, e.g. {@code 820 KB/s}. */
    fun speed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0) return "0 B/s"
        if (bytesPerSecond < KB) {
            return String.format(Locale.US, "%.0f B/s", bytesPerSecond)
        }
        if (bytesPerSecond < MB) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / KB)
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / MB)
    }

    /** Formats an elapsed duration, e.g. {@code 1m05s}. */
    fun elapsed(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0)) / 1000
        if (totalSeconds < 60) return "${totalSeconds}s"
        if (totalSeconds < 3600) {
            return String.format(
                Locale.US, "%dm%02ds", totalSeconds / 60, totalSeconds % 60,
            )
        }
        return String.format(
            Locale.US, "%dh%02dm", totalSeconds / 3600, (totalSeconds % 3600) / 60,
        )
    }

    /** Formats a remaining duration, e.g. {@code 2m10s}. */
    fun eta(seconds: Long): String {
        if (seconds < 0) return ""
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3600) {
            return String.format(Locale.US, "%dm%ds", seconds / 60, seconds % 60)
        }
        return String.format(Locale.US, "%dh%dm", seconds / 3600, (seconds % 3600) / 60)
    }

    /** Clamps a byte based progress ratio into {@code [0, 1]}. */
    fun progress(downloaded: Long, total: Long): Float {
        if (total <= 0) return 0f
        val ratio = (downloaded.toDouble() / total.toDouble()).toFloat()
        return ratio.coerceIn(0f, 1f)
    }
}
