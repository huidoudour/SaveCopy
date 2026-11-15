package app.rikka.savecopy;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
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
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground download service.
 *
 * <p>Every request runs in its own {@link DownloadTask} carrying a session id,
 * so starting a new download (or cancelling) invalidates the previous transfer
 * instead of letting two tasks write into the same destination.</p>
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";

    private static final int NOTIFICATION_ID_PROGRESS = 1001;
    private static final int NOTIFICATION_ID_RESULT = 1002;
    private static final int NOTIFICATION_ID_ERROR = 1003;

    private static final int REQUEST_DETAILS = 100;
    private static final int REQUEST_CANCEL = 101;
    private static final int REQUEST_OPEN = 102;

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String CHANNEL_ID = "download_channel";
    private static final int MAX_RETRIES = 3;
    /** Four refreshes per second keeps the notification responsive. */
    private static final long PROGRESS_INTERVAL_MS = 250;
    private static final int CONNECT_TIMEOUT = 20_000;
    private static final int READ_TIMEOUT = 60_000;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;

    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";
    public static final String ACTION_CANCEL = "app.rikka.savecopy.DOWNLOAD_CANCEL";

    private static volatile DownloadStatus sDownloadStatus;

    private NotificationManager notificationManager;
    private Notification.Builder progressBuilder;

    private final AtomicInteger sessionCounter = new AtomicInteger();
    /** Session id allowed to keep running; anything else must stop. */
    private volatile int activeSession = -1;
    private volatile DownloadTask activeTask;

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
        @Nullable
        public final Uri fileUri;
        @Nullable
        public final String folderName;

        private DownloadStatus(String url, String fileName, long downloadedBytes, long totalBytes,
                               long startedAtMillis, long updatedAtMillis, DownloadState state,
                               String message, @Nullable Uri fileUri, @Nullable String folderName) {
            this.url = url;
            this.fileName = fileName;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.startedAtMillis = startedAtMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.state = state;
            this.message = message;
            this.fileUri = fileUri;
            this.folderName = folderName;
        }
    }

    public static DownloadStatus getDownloadStatus() {
        return sDownloadStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ensureNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(intent.getAction())) {
            Log.d(TAG, "Download cancelled by user");
            cancelActiveSession();
            return START_NOT_STICKY;
        }

        String downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            Log.w(TAG, "Download started without a URL");
            stopSelf();
            return START_NOT_STICKY;
        }

        // A new request always wins: the previous transfer loses its session id
        // and cleans up after itself.
        abortActiveSession();

        int session = sessionCounter.incrementAndGet();
        activeSession = session;

        DownloadTask task = new DownloadTask(
                session,
                startId,
                downloadUrl,
                intent.getStringExtra(EXTRA_FILE_NAME),
                intent.getStringExtra(EXTRA_CALLING_PACKAGE));
        activeTask = task;
        publishStatus(task, DownloadState.DOWNLOADING, null, null);

        progressBuilder = createProgressBuilder(downloadUrl);
        try {
            startForeground(NOTIFICATION_ID_PROGRESS, progressBuilder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {
            // Background start restrictions can reject the foreground promotion;
            // without a notification the transfer must not continue silently.
            Log.e(TAG, "Unable to start foreground service", e);
            activeSession = -1;
            showErrorNotification(e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        Thread worker = new Thread(task, "download-" + session);
        task.attachThread(worker);
        worker.start();
        return START_NOT_STICKY;
    }

    /** Invalidates the running session so its task stops at the next check. */
    private void abortActiveSession() {
        DownloadTask task = activeTask;
        activeSession = -1;
        if (task != null) task.cancel();
        activeTask = null;
    }

    private void cancelActiveSession() {
        DownloadTask task = activeTask;
        activeSession = -1;
        if (task != null) {
            task.cancel();
            publishStatus(task, DownloadState.CANCELLED, "cancelled", null);
        }
        activeTask = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID_PROGRESS);
        }
        stopSelf();
    }

    private void publishStatus(DownloadTask task, DownloadState state, String message,
                               @Nullable Uri fileUri) {
        sDownloadStatus = new DownloadStatus(
                task.url,
                task.fileName,
                task.downloadedSize,
                task.totalSize,
                task.startedAtMillis,
                System.currentTimeMillis(),
                state,
                message,
                fileUri,
                task.folderName);
    }

    // ---------------------------------------------------------------- notifications

    private void ensureNotificationChannel() {
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_progress),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(getString(R.string.notification_channel_result));
        channel.setShowBadge(true);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification.Builder newReplyBuilder() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        // Android 12+ may defer a foreground-service notification unless it is
        // explicitly marked as immediate; a download must be visible right away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder;
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private Notification.Builder createProgressBuilder(String url) {
        Intent detailsIntent = new Intent(this, DownloadDetailsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent detailsPendingIntent = PendingIntent.getActivity(
                this, REQUEST_DETAILS, detailsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent cancelIntent = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, REQUEST_CANCEL, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return newReplyBuilder()
                .setContentTitle(getString(R.string.notification_working_title))
                .setContentText(getString(R.string.toast_start_download, truncateUrl(url)))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(detailsPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(android.R.string.cancel), cancelPendingIntent)
                .setProgress(100, 0, true);
    }

    /**
     * The result notification opens {@link OpenFileActivity} instead of an
     * external installer directly: the tap then always lands somewhere, and
     * resolution failures surface as a message rather than a dead tap.
     */
    private void showResultNotification(DownloadTask task, @Nullable Uri fileUri) {
        if (notificationManager == null) return;

        Intent openIntent = new Intent(this, OpenFileActivity.class)
                .setAction(OpenFileActivity.ACTION_OPEN_DOWNLOAD)
                .setData(fileUri)
                .putExtra(OpenFileActivity.EXTRA_FILE_NAME, task.fileName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, REQUEST_OPEN, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String container = task.folderName != null ? task.folderName : getString(R.string.app_name);
        Notification notification = newReplyBuilder()
                .setContentTitle(getString(R.string.notification_saved_title, container))
                .setContentText(getString(R.string.notification_saved_open_hint, task.fileName))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .build();

        notificationManager.notify(NOTIFICATION_ID_RESULT, notification);
    }

    private void showErrorNotification(@Nullable String error) {
        if (notificationManager == null) return;
        Intent intent = new Intent(this, InfoActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, REQUEST_DETAILS, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = newReplyBuilder()
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(error != null ? error : getString(R.string.notification_error_text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
        notificationManager.notify(NOTIFICATION_ID_ERROR, notification);
    }

    private void updateProgressNotification(DownloadTask task, long current, long total, boolean force) {
        if (progressBuilder == null || notificationManager == null) return;

        long now = System.currentTimeMillis();
        if (!force && now - task.lastUpdateTime < PROGRESS_INTERVAL_MS
                && (total <= 0 || current < total)) {
            return;
        }

        long bytesSinceLast = current - task.lastUpdateBytes;
        long timeSinceLast = Math.max(1, now - task.lastUpdateTime);
        double speedBps = (double) bytesSinceLast * 1000 / timeSinceLast;

        task.lastUpdateTime = now;
        task.lastUpdateBytes = current;

        StringBuilder text = new StringBuilder();
        text.append("↓ ").append(FormatUtils.speed(speedBps));
        text.append("  ").append(FormatUtils.size(current));
        if (total > 0) {
            text.append("/").append(FormatUtils.size(total));
            int percent = (int) Math.min(100, (current * 100) / total);
            progressBuilder.setProgress(100, percent, false);
            long remaining = total - current;
            if (speedBps > 0 && remaining > 0) {
                text.append("  ").append(FormatUtils.eta((long) (remaining / speedBps)));
            }
        } else {
            progressBuilder.setProgress(0, 0, true);
        }

        progressBuilder.setContentText(text.toString());
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, progressBuilder.build());
    }

    // ---------------------------------------------------------------- downloading

    /**
     * One download attempt chain. Instances are never reused, so retries keep
     * their own destination file and the session id guards against a newer
     * request taking over.
     */
    private final class DownloadTask implements Runnable {

        final int session;
        /** Identifies this start request so a stale task cannot stop a newer one. */
        final int startId;
        final String url;
        final String suggestedFileName;
        final String callingPackage;
        final long startedAtMillis = System.currentTimeMillis();

        private volatile boolean cancelled;
        private volatile Thread worker;
        private volatile HttpURLConnection connection;

        // Destination is created once and reused across retries (resume).
        private Uri destUri;
        private boolean destIsSaf;
        private String fileName;
        private String folderName;

        private long totalSize = -1;
        private long downloadedSize;
        private long lastUpdateTime;
        private long lastUpdateBytes;

        DownloadTask(int session, int startId, String url, @Nullable String suggestedFileName,
                     @Nullable String callingPackage) {
            this.session = session;
            this.startId = startId;
            this.url = url;
            this.suggestedFileName = suggestedFileName;
            this.callingPackage = callingPackage;
        }

        void attachThread(Thread thread) {
            this.worker = thread;
        }

        boolean isActive() {
            return !cancelled && activeSession == session;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Exception ignored) {
                }
            }
            Thread thread = worker;
            if (thread != null) thread.interrupt();
        }

        @Override
        public void run() {
            try {
                download();
            } catch (Exception e) {
                if (cancelled || !isActive()) {
                    // The cancel action (or a newer request) already published the
                    // status for this session; do not overwrite it.
                    Log.d(TAG, "Download stopped: " + e.getMessage());
                } else {
                    Log.e(TAG, "Download failed", e);
                    publishStatus(this, DownloadState.FAILED, e.getMessage(), null);
                    showErrorNotification(e.getMessage());
                }
            } finally {
                connection = null;
                if (this == activeTask) {
                    activeTask = null;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                }
                // stopSelf(startId) keeps the service alive when a newer
                // download already owns it.
                stopSelf(startId);
            }
        }

        private void download() throws IOException {
            IOException lastError = null;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                ensureActive();
                try {
                    attemptDownload();
                    return;
                } catch (IOException e) {
                    lastError = e;
                    Log.w(TAG, "Attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                    if (!isActive() || attempt >= MAX_RETRIES || !isRetryable(e)) {
                        cleanupPartialFile();
                        throw e;
                    }
                    // Keep the partial file: the next attempt resumes with Range.
                    long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cleanupPartialFile();
                        throw new IOException("Download cancelled");
                    }
                }
            }
            throw lastError != null ? lastError : new IOException("Download failed");
        }

        private void attemptDownload() throws IOException {
            ContentResolver cr = getContentResolver();
            FileOutputStream destOut = null;
            ParcelFileDescriptor safPfd = null;
            InputStream httpIn = null;

            boolean isSaf = destIsSaf;
            boolean truncate = false;
            boolean skipStream = false;
            long startByte = 0;

            try {
                // --- 1. Open HTTP connection (Range header enables resume) ---
                if (destUri != null) {
                    startByte = queryExistingSize(destUri);
                    Log.d(TAG, "Resuming download at byte " + startByte);
                }

                URL target = new URL(url);
                HttpURLConnection http = openHttpConnection(target, startByte);
                connection = http;
                int responseCode = http.getResponseCode();

                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && startByte > 0) {
                    long rangeTotal = parseContentRangeTotal(http.getHeaderField("Content-Range"));
                    if (rangeTotal > 0 && startByte >= rangeTotal) {
                        // Already complete; a previous run failed while finalizing.
                        Log.d(TAG, "File already complete (" + startByte + "/" + rangeTotal + ")");
                        totalSize = rangeTotal;
                        downloadedSize = startByte;
                        skipStream = true;
                    } else {
                        Log.d(TAG, "Server file changed (416), restarting");
                        startByte = 0;
                        truncate = true;
                        http.disconnect();
                        http = openHttpConnection(target, 0);
                        connection = http;
                        responseCode = http.getResponseCode();
                    }
                }

                String contentType = http.getContentType();
                String contentDisposition = http.getHeaderField("Content-Disposition");
                long contentLength = http.getContentLengthLong();

                if (!skipStream) {
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        Log.d(TAG, "Server supports range, resuming at " + startByte);
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        if (startByte > 0) {
                            Log.d(TAG, "Server ignored Range, restarting");
                            truncate = true;
                            startByte = 0;
                        }
                    } else {
                        throw new IOException("HTTP " + responseCode);
                    }

                    totalSize = contentLength;
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        long rangeTotal = parseContentRangeTotal(
                                http.getHeaderField("Content-Range"));
                        totalSize = rangeTotal > 0 ? rangeTotal
                                : (contentLength > 0 ? startByte + contentLength : -1);
                    }
                }

                downloadedSize = startByte;
                lastUpdateTime = System.currentTimeMillis();
                lastUpdateBytes = startByte;
                publishStatus(this, DownloadState.DOWNLOADING, null, null);
                updateProgressNotification(this, downloadedSize, totalSize, true);

                // --- 2. Resolve the file name ---
                if (destUri == null) {
                    fileName = resolveFileName(contentDisposition, contentType);
                }
                publishStatus(this, DownloadState.DOWNLOADING, null, null);

                // --- 3. Create the destination once, reuse it on retries ---
                if (destUri == null) {
                    createDestination(contentType);
                    isSaf = destIsSaf;
                }

                // --- 4. Stream straight into the destination ---
                if (!skipStream) {
                    String mode = truncate ? "rwt" : "rw";
                    safPfd = cr.openFileDescriptor(destUri, mode);
                    if (safPfd == null) throw new IOException("Failed to open output stream");

                    destOut = new FileOutputStream(safPfd.getFileDescriptor());
                    if (!truncate && startByte > 0) {
                        // ParcelFileDescriptor has no seekTo(); position the channel.
                        destOut.getChannel().position(startByte);
                    }

                    httpIn = http.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = httpIn.read(buffer)) != -1) {
                        ensureActive();
                        destOut.write(buffer, 0, read);
                        downloadedSize += read;
                        publishStatus(this, DownloadState.DOWNLOADING, null, null);
                        updateProgressNotification(this, downloadedSize, totalSize, false);
                    }
                    destOut.flush();
                    Log.d(TAG, "Streamed " + downloadedSize + " bytes to destination");
                }

                // --- 5. Finalize ---
                if (!isSaf) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.IS_PENDING, false);
                    cr.update(destUri, values, null, null);
                }

                String savedFileName = queryDisplayName(destUri, fileName);
                Uri completedUri = destUri;
                destUri = null; // success: nothing left to clean up
                fileName = savedFileName;

                // --- 6. Report success ---
                Log.d(TAG, "Download complete: " + savedFileName);
                publishStatus(this, DownloadState.COMPLETED, null, completedUri);
                showResultNotification(this, completedUri);
                toastSaved(savedFileName, folderName);
            } finally {
                closeQuietly(httpIn);
                closeQuietly(destOut);
                closeQuietly(safPfd);
                HttpURLConnection current = connection;
                if (current != null) current.disconnect();
                connection = null;
            }
        }

        private String resolveFileName(@Nullable String contentDisposition,
                                       @Nullable String contentType) {
            String name = extractFileName(contentDisposition, url);
            if (name == null || name.isEmpty()) {
                name = suggestedFileName != null && !suggestedFileName.isEmpty()
                        ? suggestedFileName : "download";
            }
            if (!name.contains(".")) {
                String extension = getExtensionFromMimeType(contentType);
                if (extension != null) name = name + extension;
            }
            // A CDN may answer a ".apk" link with a generic download name; the
            // installer path depends on the extension, so restore it.
            return FileUtils.ensureInstallerExtension(name, contentType);
        }

        private void createDestination(@Nullable String contentType) throws IOException {
            ContentResolver cr = getContentResolver();
            boolean useCustomFolder = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                    .getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
            String customFolderPath = useCustomFolder
                    ? getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                    .getString(Settings.KEY_CUSTOM_FOLDER_PATH, null)
                    : null;

            if (customFolderPath != null) {
                createSafDestination(cr, customFolderPath, contentType);
            } else {
                createMediaStoreDestination(cr);
            }

            destIsSaf = folderName != null;
            publishStatus(this, DownloadState.DOWNLOADING, null, null);
        }

        private void createSafDestination(ContentResolver cr, String treePath,
                                          @Nullable String contentType) throws IOException {
            Uri treeUri = Uri.parse(treePath);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String mimeType = FileUtils.getMimeTypeForFileName(fileName);
            Log.d(TAG, "SAF createDocument: mimeType=" + mimeType + ", name=" + fileName);

            Uri created = tryCreateDocument(cr, docUri, mimeType, fileName);

            if (created == null) {
                String[] parts = FileUtils.spiltFileName(fileName);
                for (int i = 1; i <= 999 && created == null; i++) {
                    String candidate = parts[0] + " (" + i + ")" + parts[1];
                    created = tryCreateDocument(cr, docUri, mimeType, candidate);
                    if (created != null) fileName = candidate;
                }
            }

            if (created == null) throw new IOException("Failed to create file in custom folder");

            destUri = created;
            folderName = treeUri.getLastPathSegment();
            if (folderName != null) {
                folderName = folderName.replace("tree:", "").replace("primary:", "");
            }
        }

        @Nullable
        private Uri tryCreateDocument(ContentResolver cr, Uri parent, String mimeType, String name) {
            try {
                return DocumentsContract.createDocument(cr, parent, mimeType, name);
            } catch (Exception e) {
                Log.d(TAG, "SAF createDocument failed for " + name, e);
                return null;
            }
        }

        private void createMediaStoreDestination(ContentResolver cr) throws IOException {
            String downloadDir = Environment.DIRECTORY_DOWNLOADS;
            if (callingPackage != null
                    && getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                    .getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
                String label = loadLabelForPackage(callingPackage);
                if (label != null) downloadDir += "/" + label;
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, downloadDir);
            values.put(MediaStore.MediaColumns.IS_PENDING, true);
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);

            destUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destUri == null) throw new IOException("Failed to create MediaStore entry");
        }

        private String queryDisplayName(Uri uri, String fallback) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (index != -1) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isEmpty()) return name;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Unable to read the saved file name", e);
            }
            return fallback;
        }

        private void ensureActive() throws IOException {
            if (!isActive()) throw new IOException("Download cancelled");
        }

        private void cleanupPartialFile() {
            Uri uri = destUri;
            if (uri == null) return;
            Log.d(TAG, "Cleaning up partial file: " + uri);
            try {
                if (destIsSaf) {
                    DocumentsContract.deleteDocument(getContentResolver(), uri);
                } else {
                    getContentResolver().delete(uri, null, null);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete the partial file", e);
                // Never leave an invisible pending MediaStore entry behind.
                if (!destIsSaf) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.IS_PENDING, false);
                        getContentResolver().update(uri, values, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
            destUri = null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void toastSaved(String fileName, @Nullable String folderName) {
        String message = folderName != null
                ? getString(R.string.toast_saved_custom, fileName, folderName)
                : getString(R.string.toast_saved, fileName);
        new Handler(Looper.getMainLooper()).post(
                () -> android.widget.Toast.makeText(
                        DownloadService.this, message, android.widget.Toast.LENGTH_LONG).show());
    }

    private void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
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

    /**
     * Returns the current default-network HTTP proxy. PAC proxies are left to
     * Android's own URL handling, which can evaluate the PAC rules per host.
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
            if (proxyInfo.getPacFileUrl() != null && !Uri.EMPTY.equals(proxyInfo.getPacFileUrl())) {
                Log.d(TAG, "System PAC proxy detected; using Android proxy selection");
                return null;
            }
            return new Proxy(Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(proxyInfo.getHost(), proxyInfo.getPort()));
        } catch (SecurityException e) {
            Log.w(TAG, "Unable to read the system proxy", e);
            return null;
        }
    }

    private long queryExistingSize(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return 0;
            long size = pfd.getStatSize();
            return Math.max(size, 0);
        } catch (Exception e) {
            Log.w(TAG, "Failed to query the existing size", e);
            return 0;
        }
    }

    private long parseContentRangeTotal(@Nullable String contentRange) {
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
        if (e instanceof SocketTimeoutException) return true;
        if (e instanceof ConnectException || e instanceof UnknownHostException) return true;
        String message = e.getMessage();
        if (message != null && message.startsWith("HTTP ")) {
            // 5xx/408/429 are transient; other 4xx will not improve on retry.
            return message.startsWith("HTTP 5")
                    || message.startsWith("HTTP 408")
                    || message.startsWith("HTTP 429");
        }
        return true;
    }

    @Nullable
    private String extractFileName(@Nullable String contentDisposition, String url) {
        if (contentDisposition != null) {
            Matcher matcher = Pattern
                    .compile("filename[^;=\\n]*=((['\"]).*?\\2|[^;\\n]*)")
                    .matcher(contentDisposition);
            if (matcher.find()) {
                String name = matcher.group(1).replaceAll("['\"]", "").trim();
                if (!name.isEmpty()) return sanitizeFileName(name);
            }

            matcher = Pattern.compile("filename\\*[^;]*=([^;]*)", Pattern.CASE_INSENSITIVE)
                    .matcher(contentDisposition);
            if (matcher.find()) {
                String encoded = matcher.group(1).trim();
                try {
                    if (encoded.contains("'")) {
                        encoded = encoded.substring(encoded.lastIndexOf("'") + 1);
                    }
                    return sanitizeFileName(
                            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()));
                } catch (Exception ignored) {
                }
            }
        }

        try {
            URL urlObject = new URL(url);
            String path = urlObject.getPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    // Percent-encoded names show up as %E4%B8%AD... in the path.
                    try {
                        name = URLDecoder.decode(name, StandardCharsets.UTF_8.name());
                    } catch (Exception ignored) {
                    }
                    return sanitizeFileName(name);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String sanitizeFileName(String fileName) {
        String sanitized = fileName.replaceAll("[/\\\\:*?\"<>|]", "_");
        if (sanitized.length() > 200) {
            int dot = sanitized.lastIndexOf('.');
            if (dot > 0) {
                String extension = sanitized.substring(dot);
                sanitized = sanitized.substring(0, 200 - extension.length()) + extension;
            } else {
                sanitized = sanitized.substring(0, 200);
            }
        }
        return sanitized;
    }

    @Nullable
    private String getExtensionFromMimeType(@Nullable String mimeType) {
        if (mimeType == null) return null;
        switch (mimeType.toLowerCase(Locale.ROOT)) {
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
            case "application/x-apk":
            case FileUtils.MIME_APK: return ".apk";
            default:
                if (mimeType.contains("/")) {
                    String subtype = mimeType.split("/")[1];
                    if (subtype.equals("octet-stream")) return ".bin";
                    return "." + subtype.replaceAll("[^a-z0-9]", "");
                }
                return null;
        }
    }

    @Nullable
    private String loadLabelForPackage(String packageName) {
        try {
            Configuration configuration = new Configuration();
            configuration.locale = Locale.ENGLISH;
            Resources resources = getPackageManager().getResourcesForApplication(packageName);
            resources.updateConfiguration(configuration, getResources().getDisplayMetrics());

            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            if (info.labelRes != 0) return resources.getString(info.labelRes);
            return info.nonLocalizedLabel != null ? info.nonLocalizedLabel.toString()
                    : info.packageName;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException
                 | NullPointerException e) {
            return null;
        }
    }

    private String truncateUrl(String url) {
        if (url == null) return "";
        if (url.length() <= 50) return url;
        try {
            String host = Uri.parse(url).getHost();
            if (host != null) return host + "/...";
        } catch (Exception ignored) {
        }
        return url.substring(0, 47) + "...";
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
