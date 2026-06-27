package app.rikka.savecopy;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final int NOTIFICATION_ID = 1001;
    private static final int BUFFER_SIZE = 8192;
    private static final String CHANNEL_ID = "download_channel";
    private static final int MAX_RETRIES = 3;
    private static final long PROGRESS_INTERVAL_MS = 500;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";
    public static final String ACTION_CANCEL = "app.rikka.savecopy.DOWNLOAD_CANCEL";

    private static DownloadCallback sCallback;

    private NotificationManager notificationManager;
    private Notification.Builder progressBuilder;
    private volatile boolean cancelled;
    private long totalSize;
    private long downloadedSize;
    private long lastUpdateTime;
    private long lastUpdateBytes;
    private long downloadStartTime;

    public interface DownloadCallback {
        void onDownloadComplete(String fileName, String error);
    }

    public static void setCallback(DownloadCallback callback) {
        sCallback = callback;
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

        // Build initial progress notification
        progressBuilder = createProgressBuilder(downloadUrl);
        Log.d(TAG, "Starting foreground with notification");
        startForeground(NOTIFICATION_ID, progressBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        // Execute download in background thread
        new Thread(() -> {
            try {
                doDownload(downloadUrl, fileName, callingPackage);
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                notifyCallback(null, e.getMessage());
                showErrorNotification(e.getMessage());
            }
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    @SuppressLint("NewApi")
    private Notification.Builder createProgressBuilder(String url) {
        Intent notificationIntent = new Intent(this, InfoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
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

        return builder
                .setContentTitle(getString(R.string.notification_working_title))
                .setContentText(getString(R.string.toast_start_download, truncateUrl(url)))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(android.R.string.cancel), cancelPendingIntent)
                .setProgress(100, 0, true);
    }

    private void updateProgressNotification(long current, long total) {
        if (progressBuilder == null || notificationManager == null) return;

        long now = System.currentTimeMillis();
        // Throttle updates to avoid UI jank
        if (now - lastUpdateTime < PROGRESS_INTERVAL_MS && current < total) return;

        long elapsed = now - downloadStartTime;
        if (elapsed <= 0) elapsed = 1;

        // Calculate speed
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
            int percent = (int) ((current * 100) / total);
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
            notificationIntent.setDataAndType(fileUri, "*/*");
            notificationIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            notificationIntent = new Intent();
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
        Notification notification = builder
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(error != null ? error : getString(R.string.notification_error_text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
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
                throw new IOException("Download cancelled");
            }
            try {
                attemptDownload(downloadUrl, suggestedFileName, callingPackage);
                return;
            } catch (IOException e) {
                lastError = e;
                Log.w(TAG, "Attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                if (cancelled || attempt >= MAX_RETRIES) throw e;
                long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                Log.d(TAG, "Retrying in " + waitMs + "ms...");
                try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw lastError != null ? lastError : new IOException("Download failed");
    }

    @SuppressLint("WakelockTimeout")
    private void attemptDownload(String downloadUrl, String suggestedFileName, String callingPackage) throws IOException {
        HttpURLConnection connection = null;
        InputStream httpIn = null;
        ContentResolver cr = getContentResolver();

        Uri destUri = null;
        OutputStream destOut = null;
        ParcelFileDescriptor safPfd = null;
        boolean isSaf = false;
        String savedFileName = null;
        String folderName = null;

        try {
            // --- 1. Open HTTP connection ---
            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SaveCopy");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + responseCode);
            }

            String contentType = connection.getContentType();
            String contentDisposition = connection.getHeaderField("Content-Disposition");
            long contentLength = connection.getContentLength();

            String fileName = extractFileName(contentDisposition, downloadUrl);
            if (fileName == null || fileName.isEmpty()) {
                fileName = suggestedFileName != null ? suggestedFileName : "download";
            }
            if (!fileName.contains(".")) {
                String ext = getExtensionFromMimeType(contentType);
                if (ext != null) fileName = fileName + ext;
            }

            Log.d(TAG, "Downloading: " + fileName + " (size: " + contentLength + ", type: " + contentType + ")");

            totalSize = contentLength;
            downloadedSize = 0;
            lastUpdateTime = 0;
            lastUpdateBytes = 0;
            downloadStartTime = System.currentTimeMillis();

            // --- 2. Create destination file BEFORE downloading ---
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

                safPfd = cr.openFileDescriptor(destUri, "w");
                if (safPfd == null) throw new IOException("Failed to open SAF fd");
                destOut = new FileOutputStream(safPfd.getFileDescriptor());

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

                Uri tableUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        : MediaStore.Files.getContentUri("external");

                destUri = cr.insert(tableUri, values);
                if (destUri == null) throw new IOException("Failed to create MediaStore entry");

                destOut = cr.openOutputStream(destUri, "w");
                if (destOut == null) {
                    cr.delete(destUri, null, null);
                    throw new IOException("Failed to open output stream");
                }
            }

            // --- 3. Stream HTTP directly to destination (no temp file) ---
            httpIn = connection.getInputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = httpIn.read(buf)) != -1) {
                if (cancelled) throw new IOException("Download cancelled");
                destOut.write(buf, 0, n);
                downloadedSize += n;
                updateProgressNotification(downloadedSize, totalSize);
            }
            destOut.flush();
            Log.d(TAG, "Streamed " + downloadedSize + " bytes directly to destination");

            // --- 4. Finalize ---
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

            // --- 5. Success ---
            final String fn = savedFileName;
            final String fld = folderName;

            Log.d(TAG, "Download complete: " + fn);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID + 1, createSuccessNotification(fn, null));
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

            // Clean up partial file on failure
            if (savedFileName == null && destUri != null) {
                try { cr.delete(destUri, null, null); } catch (Exception ignored) {}
            }
        }
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
