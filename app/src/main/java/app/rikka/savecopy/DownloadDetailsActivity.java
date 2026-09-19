package app.rikka.savecopy;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.ComponentActivity;

import java.util.Locale;

import app.rikka.savecopy.databinding.DownloadDetailsActivityBinding;

/** A dialog-style, read-only view of the foreground download service. */
public class DownloadDetailsActivity extends ComponentActivity {

    private static final long REFRESH_INTERVAL_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DownloadDetailsActivityBinding binding;
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            DownloadService.DownloadStatus status = DownloadService.getDownloadStatus();
            renderStatus(status);
            if (status != null && status.state == DownloadService.DownloadState.DOWNLOADING) {
                handler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DownloadDetailsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setTitle(R.string.download_details_title);
        binding.downloadDetailsClose.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void renderStatus(DownloadService.DownloadStatus status) {
        if (status == null) {
            binding.downloadDetailsStatus.setText(R.string.download_details_status_unavailable);
            binding.downloadDetailsFileName.setText(getString(R.string.download_details_file_name, "—"));
            binding.downloadDetailsDownloaded.setText(getString(R.string.download_details_downloaded, "—"));
            binding.downloadDetailsTotal.setText(getString(R.string.download_details_total, "—"));
            binding.downloadDetailsAverageSpeed.setText(getString(R.string.download_details_average_speed, "—"));
            binding.downloadDetailsElapsed.setText(getString(R.string.download_details_elapsed, "—"));
            return;
        }

        String fileName = status.fileName != null && !status.fileName.isEmpty()
                ? status.fileName : getString(R.string.download_details_file_unknown);
        binding.downloadDetailsFileName.setText(getString(R.string.download_details_file_name, fileName));
        binding.downloadDetailsDownloaded.setText(getString(
                R.string.download_details_downloaded, formatSize(status.downloadedBytes)));
        binding.downloadDetailsTotal.setText(getString(
                R.string.download_details_total,
                status.totalBytes > 0 ? formatSize(status.totalBytes) : getString(R.string.download_details_total_unknown)));

        long elapsedMillis = Math.max(0, System.currentTimeMillis() - status.startedAtMillis);
        double averageSpeed = elapsedMillis > 0
                ? (double) status.downloadedBytes * 1000 / elapsedMillis : 0;
        binding.downloadDetailsAverageSpeed.setText(getString(
                R.string.download_details_average_speed, formatSpeed(averageSpeed)));
        binding.downloadDetailsElapsed.setText(getString(
                R.string.download_details_elapsed, formatElapsed(elapsedMillis)));
        binding.downloadDetailsStatus.setText(formatState(status));
    }

    private String formatState(DownloadService.DownloadStatus status) {
        switch (status.state) {
            case COMPLETED:
                return getString(R.string.download_details_status_completed);
            case FAILED:
                return status.message == null || status.message.isEmpty()
                        ? getString(R.string.download_details_status_failed)
                        : getString(R.string.download_details_status_failed_with_reason, status.message);
            case CANCELLED:
                return getString(R.string.download_details_status_cancelled);
            case DOWNLOADING:
            default:
                return getString(R.string.download_details_status_downloading);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond < 1024) return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
        if (bytesPerSecond < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
    }

    private static String formatElapsed(long millis) {
        long totalSeconds = millis / 1000;
        if (totalSeconds < 60) return totalSeconds + "s";
        if (totalSeconds < 3600) return String.format(Locale.US, "%dm%02ds",
                totalSeconds / 60, totalSeconds % 60);
        return String.format(Locale.US, "%dh%02dm", totalSeconds / 3600,
                (totalSeconds % 3600) / 60);
    }
}
