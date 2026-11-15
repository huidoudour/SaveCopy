package app.rikka.savecopy;

import java.util.Locale;

/**
 * Single source of truth for the human readable numbers shown in the download
 * notification, the toast messages and the details dialog. Keeping the
 * formatting here avoids the three slightly diverging copies that used to live
 * in {@code DownloadService} and {@code DownloadDetailsActivity}.
 */
public final class FormatUtils {

    private static final double KB = 1024d;
    private static final double MB = KB * 1024d;
    private static final double GB = MB * 1024d;

    private FormatUtils() {
    }

    /** Formats a byte count, e.g. {@code 1.4 MB}. */
    public static String size(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < KB) return bytes + " B";
        if (bytes < MB) return String.format(Locale.US, "%.1f KB", bytes / KB);
        if (bytes < GB) return String.format(Locale.US, "%.1f MB", bytes / MB);
        return String.format(Locale.US, "%.2f GB", bytes / GB);
    }

    /** Formats a transfer rate, e.g. {@code 820 KB/s}. */
    public static String speed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0 B/s";
        if (bytesPerSecond < KB) return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < MB) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / KB);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / MB);
    }

    /** Formats an elapsed duration, e.g. {@code 1m05s}. */
    public static String elapsed(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        if (totalSeconds < 60) return totalSeconds + "s";
        if (totalSeconds < 3600) {
            return String.format(Locale.US, "%dm%02ds", totalSeconds / 60, totalSeconds % 60);
        }
        return String.format(Locale.US, "%dh%02dm", totalSeconds / 3600, (totalSeconds % 3600) / 60);
    }

    /** Formats a remaining duration, e.g. {@code 2m10s}. */
    public static String eta(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format(Locale.US, "%dm%ds", seconds / 60, seconds % 60);
        return String.format(Locale.US, "%dh%dm", seconds / 3600, (seconds % 3600) / 60);
    }

    /** Clamps a byte based progress ratio into {@code [0, 1]}. */
    public static float progress(long downloaded, long total) {
        if (total <= 0) return 0f;
        float ratio = (float) ((double) downloaded / (double) total);
        return Math.max(0f, Math.min(1f, ratio));
    }
}
