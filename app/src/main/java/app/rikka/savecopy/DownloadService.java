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
import java.io.FileInputStream;
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

    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";

    private static DownloadCallback sCallback;

    private NotificationManager notificationManager;
    private Notification.Builder progressBuilder;
    private long totalSize;
    private long downloadedSize;

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
                .setProgress(100, 0, true);
    }

    private void updateProgressNotification(long current, long total) {
        if (progressBuilder == null || notificationManager == null) return;
        if (total <= 0) {
            progressBuilder.setProgress(0, 0, true);
        } else {
            int percent = (int) ((current * 100) / total);
            progressBuilder.setProgress(100, percent, false);
        }
        progressBuilder.setContentText(getString(R.string.notification_saving_title,
                formatSize(current) + " / " + (total > 0 ? formatSize(total) : "?")));
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

    private void doDownload(String downloadUrl, String suggestedFileName, String callingPackage) throws IOException {
        Log.d(TAG, "Starting download: " + downloadUrl);

        HttpURLConnection connection = null;
        InputStream httpIn = null;
        File tempFile = null;
        try {
            URL url = new URL(downloadUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SaveCopy");

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP error: " + responseCode);
            }

            String contentType = connection.getContentType();
            String contentDisposition = connection.getHeaderField("Content-Disposition");
            long contentLength = connection.getContentLength();

            String fileName = extractFileName(contentDisposition, downloadUrl);
            if (fileName == null || fileName.isEmpty()) {
                fileName = suggestedFileName != null ? suggestedFileName : "download";
            }
            if (!fileName.contains(".")) {
                String extension = getExtensionFromMimeType(contentType);
                if (extension != null) fileName = fileName + extension;
            }

            Log.d(TAG, "Downloading: " + fileName + " (size: " + contentLength + ", type: " + contentType + ")");

            totalSize = contentLength;
            downloadedSize = 0;
            httpIn = connection.getInputStream();

            // Step 1: download to temp file via standard Java I/O
            tempFile = new File(getCacheDir(), "dl_" + System.currentTimeMillis());
            Log.d(TAG, "Temp file: " + tempFile);
            try (OutputStream tempOut = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[BUFFER_SIZE];
                int n;
                while ((n = httpIn.read(buf)) != -1) {
                    tempOut.write(buf, 0, n);
                    downloadedSize += n;
                    updateProgressNotification(downloadedSize, totalSize);
                }
                tempOut.flush();
            }
            Log.d(TAG, "Downloaded " + downloadedSize + " bytes to temp");

            // Close HTTP before SAF
            httpIn.close();
            httpIn = null;
            connection.disconnect();
            connection = null;

            // Step 2: copy temp file to destination
            try (InputStream fileIn = new FileInputStream(tempFile)) {
                String savedResult = saveToDownloads(fileIn, fileName, callingPackage);

                String savedFileName;
                String folderName = null;
                if (savedResult.contains("|")) {
                    String[] parts = savedResult.split("\\|", 2);
                    savedFileName = parts[0];
                    folderName = parts[1];
                } else {
                    savedFileName = savedResult;
                }

                final String fn = savedFileName;
                final String fld = folderName;

                Log.d(TAG, "Download complete, posting success notification");
                // Keep foreground notification, post success with different ID
                if (notificationManager != null) {
                    notificationManager.notify(NOTIFICATION_ID + 1, createSuccessNotification(fn, null));
                }
                // Now remove the progress notification
                stopForeground(true);

                new Handler(Looper.getMainLooper()).post(() -> {
                    String msg;
                    if (fld != null) msg = getString(R.string.toast_saved_custom, fn, fld);
                    else msg = getString(R.string.toast_saved, fn);
                    android.widget.Toast.makeText(DownloadService.this, msg, android.widget.Toast.LENGTH_LONG).show();
                });

                notifyCallback(savedFileName, null);
            }

        } finally {
            if (httpIn != null) try { httpIn.close(); } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
            if (tempFile != null && tempFile.exists()) tempFile.delete();
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

    @SuppressLint("WakelockTimeout")
    private String saveToDownloads(InputStream inputStream, String fileName, String callingPackage) throws IOException {
        Context context = this;
        ContentResolver contentResolver = context.getContentResolver();

        // Check if custom folder is enabled
        boolean useCustomFolder = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
        String customFolderPath = null;
        if (useCustomFolder) {
            customFolderPath = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
        }

        Uri fileUri;
        String finalFileName = fileName;

        if (customFolderPath != null) {
            // Use custom folder via SAF
            Uri treeUri = Uri.parse(customFolderPath);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            // Guess mime type from file extension
            String mimeType = FileUtils.getMimeTypeForFileName(fileName);
            Log.d(TAG, "SAF createDocument: mimeType=" + mimeType + ", fileName=" + fileName);

            // Create file in the custom folder with deduplication
            fileUri = null;
            try {
                fileUri = DocumentsContract.createDocument(contentResolver, docUri, mimeType, fileName);
            } catch (Exception e) {
                Log.d(TAG, "SAF createDocument failed, trying deduplication", e);
            }

            // If file exists, try with (1), (2), ... suffixes
            if (fileUri == null) {
                String[] parts = FileUtils.spiltFileName(fileName);
                String baseName = parts[0];
                String ext = parts[1];
                for (int i = 1; i <= 999 && fileUri == null; i++) {
                    String dedupName = baseName + " (" + i + ")" + ext;
                    try {
                        fileUri = DocumentsContract.createDocument(contentResolver, docUri, mimeType, dedupName);
                        if (fileUri != null) {
                            fileName = dedupName;
                            finalFileName = dedupName;
                            Log.d(TAG, "SAF deduplication succeeded: " + dedupName);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "SAF deduplication attempt " + i + " failed", e);
                    }
                }
            }

            if (fileUri == null) {
                throw new IOException("Failed to create file in custom folder");
            }

            // Write via ParcelFileDescriptor (bypasses SAF pipe limit)
            try (ParcelFileDescriptor pfd = contentResolver.openFileDescriptor(fileUri, "w")) {
                if (pfd == null) throw new IOException("Failed to open SAF fd");
                try (OutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    long w = 0;
                    while ((n = inputStream.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        out.flush();
                        w += n;
                    }
                    Log.d(TAG, "SAF wrote " + w + " bytes");
                }
            }

            // Return the folder name for display
            String folderName = treeUri.getLastPathSegment();
            if (folderName != null) {
                folderName = folderName.replace("tree:", "").replace("primary:", "");
            }
            return fileName + "|" + (folderName != null ? folderName : "custom folder");
        } else {
            // Use default MediaStore approach
            String downloadDir = Environment.DIRECTORY_DOWNLOADS;
            if (callingPackage != null && getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
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
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);

            Uri tableUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tableUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                tableUri = MediaStore.Files.getContentUri("external");
            }

            fileUri = contentResolver.insert(tableUri, values);
            if (fileUri == null) {
                throw new IOException("Failed to create MediaStore entry");
            }

            OutputStream outputStream = contentResolver.openOutputStream(fileUri, "w");
            if (outputStream == null) {
                contentResolver.delete(fileUri, null, null);
                throw new IOException("Failed to open output stream");
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            try {
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                    downloadedSize += bytesRead;
                    updateProgressNotification(downloadedSize, totalSize);
                }
            } finally {
                outputStream.close();
            }

            Log.d(TAG, "Downloaded " + downloadedSize + " bytes");

            // Mark as not pending on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, false);
                contentResolver.update(fileUri, values, null, null);
            }

            // Get final filename (may have been modified by MediaStore)
            try (Cursor cursor = contentResolver.query(fileUri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (index != -1) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) {
                            finalFileName = name;
                        }
                    }
                }
            }

            return finalFileName;
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
