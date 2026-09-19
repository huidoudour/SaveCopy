package app.rikka.savecopy;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String CHANNEL_ID = "download_channel";
    private static final int MAX_RETRIES = 3;
    // Four refreshes per second keeps the notification responsive without flooding
    // SystemUI with one update for every network buffer.
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final String INSTALLER_PACKAGE = "io.github.huidoudour.Installer";

    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";
    public static final String ACTION_CANCEL = "app.rikka.savecopy.DOWNLOAD_CANCEL";

    private static DownloadCallback sCallback;
    private static volatile DownloadStatus sDownloadStatus;

    private NotificationManager notificationManager;
    private Notification.Builder progressBuilder;
    private volatile boolean cancelled;
    private volatile Thread downloadThread;
    private volatile HttpURLConnection currentConnection;

    // 断点续传状态：目标文件只在第一次 attempt 创建，重试时复用并追加下载
    private Uri currentDestUri;
    private boolean currentIsSaf;
    private String currentFileName;

    private long totalSize;
    private long downloadedSize;
    private long lastUpdateTime;
    private long lastUpdateBytes;
    private String activeDownloadUrl;
    private String activeDownloadFileName;
    private long downloadSessionStartedAt;

    public enum DownloadState {
        DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }

    /** Immutable snapshot consumed by the download-details dialog. */
    public static final class DownloadStatus {
        public final String url;
        public final String fileName;
        public final long downloadedBytes;
        public final long totalBytes;
        public final long startedAtMillis;
        public final long updatedAtMillis;
        public final DownloadState state;
        public final String message;

        private DownloadStatus(String url, String fileName, long downloadedBytes,
                               long totalBytes, long startedAtMillis,
                               long updatedAtMillis, DownloadState state, String message) {
            this.url = url;
            this.fileName = fileName;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.startedAtMillis = startedAtMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.state = state;
            this.message = message;
        }
    }

    public interface DownloadCallback {
        void onDownloadComplete(String fileName, String error);
    }

    public static void setCallback(DownloadCallback callback) {
        sCallback = callback;
    }

    public static DownloadStatus getDownloadStatus() {
        return sDownloadStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Handle cancel action from notification
        if (ACTION_CANCEL.equals(intent.getAction())) {
            Log.d(TAG, "Download cancelled by user");
            cancelled = true;
            publishDownloadStatus(DownloadState.CANCELLED, "cancelled");
            // 中断阻塞中的网络读写，让下载线程尽快退出并清理半成品
            if (downloadThread != null) downloadThread.interrupt();
            if (currentConnection != null) currentConnection.disconnect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        cancelled = false;

        String downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        String callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE);

        if (downloadUrl == null || downloadUrl.isEmpty()) {
            notifyCallback(null, getString(R.string.toast_invalid_uri));
            stopSelf();
            return START_NOT_STICKY;
        }

        activeDownloadUrl = downloadUrl;
        activeDownloadFileName = fileName;
        downloadedSize = 0;
        totalSize = -1;
        downloadSessionStartedAt = System.currentTimeMillis();
        publishDownloadStatus(DownloadState.DOWNLOADING, null);

        // Build initial progress notification
        progressBuilder = createProgressBuilder(downloadUrl);
        Log.d(TAG, "Starting foreground with notification");
        startForeground(NOTIFICATION_ID, progressBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        // Execute download in background thread
        downloadThread = new Thread(() -> {
            try {
                doDownload(downloadUrl, fileName, callingPackage);
            } catch (Exception e) {
                if (cancelled) {
                    // 用户主动取消：不弹错误通知
                    Log.d(TAG, "Download cancelled: " + e.getMessage());
                    publishDownloadStatus(DownloadState.CANCELLED, "cancelled");
                    notifyCallback(null, "cancelled");
                } else {
                    Log.e(TAG, "Download failed", e);
                    publishDownloadStatus(DownloadState.FAILED, e.getMessage());
                    notifyCallback(null, e.getMessage());
                    showErrorNotification(e.getMessage());
                }
            }
            stopSelf();
        });
        downloadThread.start();

        return START_NOT_STICKY;
    }

    @SuppressLint("NewApi")
    private Notification.Builder createProgressBuilder(String url) {
        Intent detailsIntent = new Intent(this, DownloadDetailsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent detailsPendingIntent = PendingIntent.getActivity(
                this, 0, detailsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Cancel action
        Intent cancelIntent = new Intent(this, DownloadService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 1, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder
                .setContentTitle(getString(R.string.notification_working_title))
                .setContentText(getString(R.string.toast_start_download, truncateUrl(url)))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(detailsPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(android.R.string.cancel), cancelPendingIntent)
                .setProgress(100, 0, true);

        // Android 12+ may defer a foreground-service notification unless this is
        // explicitly marked as immediate. A download must be visible right away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder;
    }

    private void updateProgressNotification(long current, long total) {
        updateProgressNotification(current, total, false);
    }

    private void updateProgressNotification(long current, long total, boolean force) {
        if (progressBuilder == null || notificationManager == null) return;

        long now = System.currentTimeMillis();
        // Refresh from the actual byte count, but avoid sending dozens of
        // NotificationManager transactions per second for very fast streams.
        if (!force && now - lastUpdateTime < PROGRESS_INTERVAL_MS
                && (total <= 0 || current < total)) return;

        // Calculate speed from the same bytes used to render the progress bar.
        long bytesSinceLast = current - lastUpdateBytes;
        long timeSinceLast = now - lastUpdateTime;
        if (timeSinceLast <= 0) timeSinceLast = 1;
        double speedBps = (double) bytesSinceLast * 1000 / timeSinceLast;

        lastUpdateTime = now;
        lastUpdateBytes = current;

        StringBuilder text = new StringBuilder();
        text.append("↓ ").append(formatSpeed(speedBps));
        text.append("  ").append(formatSize(current));
        if (total > 0) {
            text.append("/").append(formatSize(total));
            int percent = (int) Math.min(100, (current * 100) / total);
            progressBuilder.setProgress(100, percent, false);
            // ETA
            long remaining = total - current;
            if (speedBps > 0 && remaining > 0) {
                long etaSec = (long) (remaining / speedBps);
                text.append("  ").append(formatEta(etaSec));
            }
        } else {
            progressBuilder.setProgress(0, 0, true);
        }

        progressBuilder.setContentText(text.toString());
        notificationManager.notify(NOTIFICATION_ID, progressBuilder.build());
    }

    @SuppressLint("NewApi")
    private Notification createSuccessNotification(String fileName, Uri fileUri) {
        Intent notificationIntent;
        if (fileUri != null) {
            notificationIntent = new Intent(Intent.ACTION_VIEW);
            String mimeType = FileUtils.getMimeTypeForFileName(fileName);
            notificationIntent.setDataAndType(fileUri, mimeType != null ? mimeType : "*/*");
            notificationIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // 兜底：打开 app 主界面
            notificationIntent = new Intent(this, InfoActivity.class);
        }

        PendingIntent pendingIntent;
        try {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        } catch (Exception e) {
            pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle(getString(R.string.notification_saved_title, "download"))
                .setContentText(getString(R.string.toast_saved, fileName))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
    }

    private void showErrorNotification(String error) {
        if (notificationManager == null) return;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent intent = new Intent(this, InfoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = builder
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(error != null ? error : getString(R.string.notification_error_text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024) return String.format(Locale.US, "%.0f B/s", bytesPerSec);
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    private static String formatEta(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format(Locale.US, "%dm%ds", seconds / 60, seconds % 60);
        return String.format(Locale.US, "%dh%dm", seconds / 3600, (seconds % 3600) / 60);
    }

    private void doDownload(String downloadUrl, String suggestedFileName, String callingPackage) throws IOException {
        Log.d(TAG, "Starting download: " + downloadUrl);

        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (cancelled) {
                cleanupPartialFile();
                throw new IOException("Download cancelled");
            }
            try {
                attemptDownload(downloadUrl, suggestedFileName, callingPackage);
                return;
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "Attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                // 取消、重试耗尽或不可重试错误：清理半成品后放弃
                if (cancelled || attempt >= MAX_RETRIES || !isRetryable(e)) {
                    cleanupPartialFile();
                    throw e;
                }
                // 可重试错误：保留已下载部分，下次 attempt 用 Range 断点续传
                long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                Log.d(TAG, "Retrying in " + waitMs + "ms...");
                try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    cleanupPartialFile();
                    throw e;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Download failed");
    }

    @SuppressLint("WakelockTimeout")
    private void attemptDownload(String downloadUrl, String suggestedFileName, String callingPackage) throws IOException {
        HttpURLConnection connection = null;
        InputStream httpIn = null;
        ContentResolver cr = getContentResolver();

        Uri destUri = currentDestUri;
        FileOutputStream destOut = null;
        ParcelFileDescriptor safPfd = null;
        boolean isSaf = currentIsSaf;
        String savedFileName = null;
        String folderName = null;
        boolean truncate = false;
        boolean skipStream = false;
        long startByte = 0;

        try {
            // --- 1. Open HTTP connection (Range header enables resume) ---
            if (destUri != null) {
                startByte = queryExistingSize(destUri);
                Log.d(TAG, "Resuming download at byte " + startByte);
            }

            URL url = new URL(downloadUrl);
            connection = openHttpConnection(url, startByte);
            currentConnection = connection;
            int responseCode = connection.getResponseCode();

            // 416: server file changed; clear partial data and restart once
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && startByte > 0) {
                long rangeTotal = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
                if (rangeTotal > 0 && startByte >= rangeTotal) {
                    // 上次已下载完整但 finalize 失败，直接完成
                    Log.d(TAG, "File already complete (" + startByte + "/" + rangeTotal + " bytes)");
                    totalSize = rangeTotal;
                    downloadedSize = startByte;
                    skipStream = true;
                } else {
                    Log.d(TAG, "Server file changed (416), restarting from scratch");
                    startByte = 0;
                    truncate = true;
                    connection.disconnect();
                    connection = openHttpConnection(url, 0);
                    currentConnection = connection;
                    responseCode = connection.getResponseCode();
                }
            }

            String contentType = connection.getContentType();
            String contentDisposition = connection.getHeaderField("Content-Disposition");
            // getContentLength() is an int and produces incorrect totals above
            // 2 GB. Use the long variant so the displayed percentage is exact.
            long contentLength = connection.getContentLengthLong();

            if (!skipStream) {
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    Log.d(TAG, "Server supports range, resuming from byte " + startByte);
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    if (startByte > 0) {
                        Log.d(TAG, "Server ignored Range, restarting from scratch");
                        truncate = true;
                        startByte = 0;
                    }
                } else {
                    throw new IOException("HTTP " + responseCode);
                }

                totalSize = contentLength;
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    long rangeTotal = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
                    totalSize = rangeTotal > 0 ? rangeTotal
                            : (contentLength > 0 ? startByte + contentLength : -1);
                }
            }
            downloadedSize = startByte;
            // Establish a real timestamp before the first update. Previously the
            // first speed calculation used epoch time and always appeared as 0.
            lastUpdateTime = System.currentTimeMillis();
            lastUpdateBytes = startByte;
            publishDownloadStatus(DownloadState.DOWNLOADING, null);

            // Switch from the indeterminate "connecting" state to an exact
            // percentage as soon as HTTP has provided a total size. This also
            // makes resumed downloads show their true starting position.
            updateProgressNotification(downloadedSize, totalSize, true);

            // --- 2. Determine file name ---
            String fileName;
            if (destUri != null) {
                // 重试时复用已创建的文件，避免 MediaStore/SAF 产生重复条目
                fileName = currentFileName;
            } else {
                fileName = extractFileName(contentDisposition, downloadUrl);
                if (fileName == null || fileName.isEmpty()) {
                    fileName = suggestedFileName != null ? suggestedFileName : "download";
                }
                if (!fileName.contains(".")) {
                    String ext = getExtensionFromMimeType(contentType);
                    if (ext != null) fileName = fileName + ext;
                }
            }

            Log.d(TAG, "Downloading: " + fileName + " (size: " + totalSize + ", type: " + contentType + ")");
            activeDownloadFileName = fileName;
            publishDownloadStatus(DownloadState.DOWNLOADING, null);

            // --- 3. Create destination file once (reused across retries) ---
            if (destUri == null) {
                boolean useCustomFolder = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                        .getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
                String customFolderPath = null;
                if (useCustomFolder) {
                    customFolderPath = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                            .getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
                }

                if (customFolderPath != null) {
                    // SAF path
                    isSaf = true;
                    Uri treeUri = Uri.parse(customFolderPath);
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri));
                    String mimeType = FileUtils.getMimeTypeForFileName(fileName);
                    Log.d(TAG, "SAF createDocument: mimeType=" + mimeType + ", fileName=" + fileName);

                    destUri = null;
                    try {
                        destUri = DocumentsContract.createDocument(cr, docUri, mimeType, fileName);
                    } catch (Exception e) {
                        Log.d(TAG, "SAF createDocument failed, trying dedup", e);
                    }

                    if (destUri == null) {
                        String[] parts = FileUtils.spiltFileName(fileName);
                        for (int i = 1; i <= 999 && destUri == null; i++) {
                            String dedupName = parts[0] + " (" + i + ")" + parts[1];
                            try {
                                destUri = DocumentsContract.createDocument(cr, docUri, mimeType, dedupName);
                                if (destUri != null) {
                                    fileName = dedupName;
                                    Log.d(TAG, "SAF dedup succeeded: " + dedupName);
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (destUri == null) throw new IOException("Failed to create file in custom folder");

                    folderName = treeUri.getLastPathSegment();
                    if (folderName != null) {
                        folderName = folderName.replace("tree:", "").replace("primary:", "");
                    }
                } else {
                    // MediaStore path
                    String downloadDir = Environment.DIRECTORY_DOWNLOADS;
                    if (callingPackage != null && getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                            .getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
                        String label = loadLabelForPackage(callingPackage);
                        downloadDir += (label != null ? "/" + label : "");
                    }

                    ContentValues values = new ContentValues();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, downloadDir);
                        values.put(MediaStore.MediaColumns.IS_PENDING, true);
                    } else {
                        java.io.File parent = new java.io.File(Environment.getExternalStorageDirectory(), downloadDir);
                        values.put(MediaStore.MediaColumns.DATA, new java.io.File(parent, fileName).getPath());
                        parent.mkdirs();
                    }
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);

                    Uri tableUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

                    destUri = cr.insert(tableUri, values);
                    if (destUri == null) throw new IOException("Failed to create MediaStore entry");
                }

                // 记住目标文件，重试时复用以便断点续传
                currentDestUri = destUri;
                currentIsSaf = isSaf;
                currentFileName = fileName;
                activeDownloadFileName = fileName;
                publishDownloadStatus(DownloadState.DOWNLOADING, null);
            }

            // --- 4. Stream HTTP directly to destination (resume if possible) ---
            if (!skipStream) {
                String mode = truncate ? "rwt" : "rw";
                safPfd = cr.openFileDescriptor(destUri, mode);
                if (safPfd == null) {
                    cleanupPartialFile();
                    throw new IOException("Failed to open output stream");
                }
                destOut = new FileOutputStream(safPfd.getFileDescriptor());
                if (!truncate && startByte > 0) {
                    // ParcelFileDescriptor 无 seekTo，用 FileChannel 定位到续传位置
                    destOut.getChannel().position(startByte);
                }

                httpIn = connection.getInputStream();
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = httpIn.read(buf)) != -1) {
                    if (cancelled) throw new IOException("Download cancelled");
                    destOut.write(buf, 0, n);
                    downloadedSize += n;
                    publishDownloadStatus(DownloadState.DOWNLOADING, null);
                    updateProgressNotification(downloadedSize, totalSize);
                }
                destOut.flush();
                Log.d(TAG, "Streamed " + downloadedSize + " bytes directly to destination");
            }

            // --- 5. Finalize ---
            if (!isSaf && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, false);
                cr.update(destUri, values, null, null);
            }

            savedFileName = fileName;
            if (!isSaf) {
                try (Cursor cursor = cr.query(destUri,
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                        if (idx != -1) {
                            String name = cursor.getString(idx);
                            if (name != null && !name.isEmpty()) savedFileName = name;
                        }
                    }
                }
            }

            currentDestUri = null; // 下载完成，无需清理

            // --- 6. Success ---
            final String fn = savedFileName;
            final String fld = folderName;
            final Uri finalUri = destUri;

            Log.d(TAG, "Download complete: " + fn);
            publishDownloadStatus(DownloadState.COMPLETED, null);
            launchInstallerForApk(fn, finalUri);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID + 1, createSuccessNotification(fn, finalUri));
            }
            stopForeground(true);

            new Handler(Looper.getMainLooper()).post(() -> {
                String msg;
                if (fld != null) msg = getString(R.string.toast_saved_custom, fn, fld);
                else msg = getString(R.string.toast_saved, fn);
                android.widget.Toast.makeText(DownloadService.this, msg, android.widget.Toast.LENGTH_LONG).show();
            });

            notifyCallback(fn, null);

        } finally {
            if (httpIn != null) try { httpIn.close(); } catch (IOException ignored) {}
            if (destOut != null) try { destOut.close(); } catch (IOException ignored) {}
            if (safPfd != null) try { safPfd.close(); } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
            currentConnection = null;
            // 半成品清理统一由 doDownload 处理：重试之间保留（断点续传），取消/最终失败时删除
        }
    }

    private HttpURLConnection openHttpConnection(URL url, long startByte) throws IOException {
        Proxy systemProxy = getSystemHttpProxy();
        HttpURLConnection connection;
        if (systemProxy != null) {
            Log.d(TAG, "Using system HTTP proxy: " + systemProxy.address());
            connection = (HttpURLConnection) url.openConnection(systemProxy);
        } else {
            connection = (HttpURLConnection) url.openConnection();
        }
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SaveCopy");
        if (startByte > 0) {
            connection.setRequestProperty("Range", "bytes=" + startByte + "-");
        }
        return connection;
    }

    private void publishDownloadStatus(DownloadState state, String message) {
        sDownloadStatus = new DownloadStatus(
                activeDownloadUrl,
                activeDownloadFileName,
                downloadedSize,
                totalSize,
                downloadSessionStartedAt,
                System.currentTimeMillis(),
                state,
                message
        );
    }

    /** Opens only the user's preferred installer; no fallback installer is used. */
    private void launchInstallerForApk(String fileName, Uri fileUri) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".apk")
                || fileUri == null) {
            return;
        }

        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(fileUri, "application/vnd.android.package-archive")
                .setPackage(INSTALLER_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            // Check both that the package exists and that it can handle APK VIEW intents.
            getPackageManager().getPackageInfo(INSTALLER_PACKAGE, 0);
            if (installIntent.resolveActivity(getPackageManager()) == null) {
                Log.d(TAG, "Preferred installer cannot handle APK VIEW intents");
                return;
            }
            startActivity(installIntent);
        } catch (PackageManager.NameNotFoundException | ActivityNotFoundException | SecurityException e) {
            // The requested installer is optional. Do not fall back to another app.
            Log.d(TAG, "Preferred installer is unavailable", e);
        }
    }

    /**
     * Returns the current default-network HTTP proxy. PAC proxies are deliberately
     * left to Android's default URL connection handling, which can evaluate the
     * PAC rules for the requested host instead of treating the PAC URL as a proxy.
     */
    private Proxy getSystemHttpProxy() {
        try {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            ProxyInfo proxyInfo = connectivityManager != null
                    ? connectivityManager.getDefaultProxy() : null;
            if (proxyInfo == null || proxyInfo.getHost() == null || proxyInfo.getPort() <= 0) {
                return null;
            }
            if (proxyInfo.getPacFileUrl() != null
                    && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl())) {
                Log.d(TAG, "System PAC proxy detected; using Android proxy selection");
                return null;
            }
            return new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(proxyInfo.getHost(), proxyInfo.getPort()));
        } catch (SecurityException e) {
            // A missing network-state permission must not prevent downloading.
            Log.w(TAG, "Unable to read system proxy", e);
            return null;
        }
    }

    private long queryExistingSize(Uri uri) {
        try {
            // MediaStore may not update its SIZE column for a pending file until
            // it is published. The descriptor reports the bytes actually on disk
            // for both MediaStore and SAF destinations.
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) return 0;
                long size = pfd.getStatSize();
                return size > 0 ? size : 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query existing size", e);
        }
        return 0;
    }

    private long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            long total = Long.parseLong(contentRange.substring(slash + 1).trim());
            return total > 0 ? total : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isRetryable(IOException e) {
        // 网络类瞬时错误可重试
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException || e instanceof UnknownHostException) return true;
        String msg = e.getMessage();
        if (msg != null && msg.startsWith("HTTP ")) {
            // 5xx/408/429 服务端瞬时错误可重试，4xx 客户端错误重试无意义
            return msg.startsWith("HTTP 5")
                    || msg.startsWith("HTTP 408")
                    || msg.startsWith("HTTP 429");
        }
        return true;
    }

    private void cleanupPartialFile() {
        Uri uri = currentDestUri;
        if (uri == null) return;
        Log.d(TAG, "Cleaning up partial file: " + uri);
        try {
            if (currentIsSaf) {
                DocumentsContract.deleteDocument(getContentResolver(), uri);
            } else {
                getContentResolver().delete(uri, null, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean up partial file", e);
            // MediaStore 条目删除失败时解除隐藏标记，避免留下不可见残留
            if (!currentIsSaf) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, false);
                    getContentResolver().update(uri, values, null, null);
                } catch (Exception ignored) {}
            }
        }
        currentDestUri = null;
    }

    private String extractFileName(String contentDisposition, String url) {
        // Try Content-Disposition header first
        if (contentDisposition != null) {
            Pattern pattern = Pattern.compile("filename[^;=\\n]*=((['\"]).*?\\2|[^;\\n]*)");
            Matcher matcher = pattern.matcher(contentDisposition);
            if (matcher.find()) {
                String fileName = matcher.group(1).replaceAll("['\"]", "").trim();
                if (!fileName.isEmpty()) {
                    return sanitizeFileName(fileName);
                }
            }

            // Try filename* for UTF-8 encoded names
            pattern = Pattern.compile("filename\\*[^;]*=([^;]*)", Pattern.CASE_INSENSITIVE);
            matcher = pattern.matcher(contentDisposition);
            if (matcher.find()) {
                String encodedName = matcher.group(1).trim();
                try {
                    // Extract from RFC 5987 encoding: 'UTF-8\'filename'
                    if (encodedName.contains("'")) {
                        encodedName = encodedName.substring(encodedName.lastIndexOf("'") + 1);
                    }
                    return sanitizeFileName(URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name()));
                } catch (Exception e) {
                    // Fall through
                }
            }
        }

        // Try to extract from URL
        try {
            URL urlObj = new URL(url);
            String path = urlObj.getPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    return sanitizeFileName(name);
                }
            }
        } catch (Exception e) {
            // Fall through
        }

        return null;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "download";
        // Remove path separators and invalid characters
        fileName = fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
        // Limit length
        if (fileName.length() > 200) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = fileName.substring(dotIndex);
                fileName = fileName.substring(0, 200 - ext.length()) + ext;
            } else {
                fileName = fileName.substring(0, 200);
            }
        }
        return fileName;
    }

    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        mimeType = mimeType.toLowerCase(Locale.ROOT);
        switch (mimeType) {
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "video/mp4": return ".mp4";
            case "video/webm": return ".webm";
            case "audio/mpeg": return ".mp3";
            case "audio/wav": return ".wav";
            case "application/pdf": return ".pdf";
            case "application/zip": return ".zip";
            case "application/x-apk": return ".apk";
            case "application/vnd.android.package-archive": return ".apk";
            default:
                // Try to extract from mime type
                if (mimeType.contains("/")) {
                    String subtype = mimeType.split("/")[1];
                    if (subtype.equals("octet-stream")) return ".bin";
                    return "." + subtype.replaceAll("[^a-z0-9]", "");
                }
                return null;
        }
    }

    private String loadLabelForPackage(String packageName) {
        Resources resources;
        ApplicationInfo info;
        try {
            Configuration configuration = new Configuration();
            configuration.locale = Locale.ENGLISH;
            resources = getPackageManager().getResourcesForApplication(packageName);
            resources.updateConfiguration(configuration, getResources().getDisplayMetrics());

            info = getPackageManager().getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        String label;
        try {
            if (info.labelRes != 0) label = resources.getString(info.labelRes);
            else label = info.nonLocalizedLabel.toString();
        } catch (Resources.NotFoundException | NullPointerException e) {
            label = info.packageName;
        }
        return label;
    }

    private void notifyCallback(String fileName, String error) {
        DownloadCallback callback = sCallback;
        if (callback != null) {
            callback.onDownloadComplete(fileName, error);
        }
        sCallback = null; // Clear callback after use
    }

    private String truncateUrl(String url) {
        if (url == null) return "";
        if (url.length() <= 50) return url;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                return host + "/...";
            }
        } catch (Exception e) {
            // Fall through
        }
        return url.substring(0, 47) + "...";
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
