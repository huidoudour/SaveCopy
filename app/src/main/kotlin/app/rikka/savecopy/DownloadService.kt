package app.rikka.savecopy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground download service.
 *
 * <p>Every request runs in its own [DownloadTask] carrying a session id, so
 * starting a new download (or cancelling) invalidates the previous transfer
 * instead of letting two tasks write into the same destination.</p>
 */
class DownloadService : Service() {

    enum class DownloadState {
        DOWNLOADING, COMPLETED, FAILED, CANCELLED,
    }

    /** Immutable snapshot consumed by the download-details dialog. */
    data class DownloadStatus(
        val url: String,
        val fileName: String?,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val startedAtMillis: Long,
        val updatedAtMillis: Long,
        val state: DownloadState,
        val message: String?,
        val fileUri: Uri?,
        val folderName: String?,
    )

    private var notificationManager: NotificationManager? = null
    private var progressBuilder: Notification.Builder? = null

    private val sessionCounter = AtomicInteger()

    /** Session id allowed to keep running; anything else must stop. */
    @Volatile
    private var activeSession = -1

    @Volatile
    private var activeTask: DownloadTask? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (ACTION_CANCEL == intent.action) {
            Log.d(TAG, "Download cancelled by user")
            cancelActiveSession()
            return START_NOT_STICKY
        }

        val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL)
        if (downloadUrl.isNullOrEmpty()) {
            Log.w(TAG, "Download started without a URL")
            stopSelf()
            return START_NOT_STICKY
        }

        // A new request always wins: the previous transfer loses its session id
        // and cleans up after itself.
        abortActiveSession()

        val session = sessionCounter.incrementAndGet()
        activeSession = session

        val task = DownloadTask(
            session = session,
            startId = startId,
            url = downloadUrl,
            suggestedFileName = intent.getStringExtra(EXTRA_FILE_NAME),
            callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE),
        )
        activeTask = task
        publishStatus(task, DownloadState.DOWNLOADING, null, null)

        val builder = createProgressBuilder(downloadUrl)
        progressBuilder = builder
        try {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) {
            // Background start restrictions can reject the foreground promotion;
            // without a notification the transfer must not continue silently.
            Log.e(TAG, "Unable to start foreground service", e)
            activeSession = -1
            showErrorNotification(e.message)
            stopSelf()
            return START_NOT_STICKY
        }

        val worker = Thread(task, "download-$session")
        task.attachThread(worker)
        worker.start()
        return START_NOT_STICKY
    }

    /** Invalidates the running session so its task stops at the next check. */
    private fun abortActiveSession() {
        val task = activeTask
        activeSession = -1
        task?.cancel()
        activeTask = null
    }

    private fun cancelActiveSession() {
        val task = activeTask
        activeSession = -1
        if (task != null) {
            task.cancel()
            publishStatus(task, DownloadState.CANCELLED, "cancelled", null)
        }
        activeTask = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager?.cancel(NOTIFICATION_ID_PROGRESS)
        stopSelf()
    }

    private fun publishStatus(
        task: DownloadTask,
        state: DownloadState,
        message: String?,
        fileUri: Uri?,
    ) {
        downloadStatus = DownloadStatus(
            url = task.url,
            fileName = task.fileName,
            downloadedBytes = task.downloadedSize,
            totalBytes = task.totalSize,
            startedAtMillis = task.startedAtMillis,
            updatedAtMillis = System.currentTimeMillis(),
            state = state,
            message = message,
            fileUri = fileUri,
            folderName = task.folderName,
        )
    }

    // ---------------------------------------------------------------- notifications

    private fun ensureNotificationChannel() {
        val manager = notificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_progress),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = getString(R.string.notification_channel_result)
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }

    private fun newReplyBuilder(): Notification.Builder {
        val builder = Notification.Builder(this, CHANNEL_ID)
        // Android 12+ may defer a foreground-service notification unless it is
        // explicitly marked as immediate; a download must be visible right away.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder
    }

    private fun createDetailsPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadDetailsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_DETAILS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createProgressBuilder(url: String): Notification.Builder {
        val cancelIntent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIcon = Icon.createWithResource(
            this,
            android.R.drawable.ic_menu_close_clear_cancel,
        )

        // Tapping the download notification opens the details dialog; cancelling
        // stays an explicit action. The failure notification is the only one
        // whose body does not open the app.
        return newReplyBuilder()
            .setContentTitle(getString(R.string.notification_working_title))
            .setContentText(getString(R.string.toast_start_download, truncateUrl(url)))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(createDetailsPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    cancelIcon,
                    getString(android.R.string.cancel),
                    cancelPendingIntent,
                ).build(),
            )
            .setProgress(100, 0, true)
    }

    /**
     * Tapping the finished notification opens the details dialog; the "Open
     * file" action is an explicit way to hand the file to another app.
     */
    private fun showResultNotification(task: DownloadTask, fileUri: Uri?) {
        val manager = notificationManager ?: return

        val openIntent = Intent(this, OpenFileActivity::class.java)
            .setAction(OpenFileActivity.ACTION_OPEN_DOWNLOAD)
            .setData(fileUri)
            .putExtra(OpenFileActivity.EXTRA_FILE_NAME, task.fileName ?: "")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIcon = Icon.createWithResource(this, R.drawable.ic_notification_open_24)

        val container = task.folderName ?: getString(R.string.app_name)
        val notification = newReplyBuilder()
            .setContentTitle(getString(R.string.notification_saved_title, container))
            .setContentText(
                getString(R.string.notification_saved_text, task.fileName ?: ""),
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(createDetailsPendingIntent())
            .addAction(
                Notification.Action.Builder(
                    openIcon,
                    getString(R.string.notification_action_open),
                    openPendingIntent,
                ).build(),
            )
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun showErrorNotification(error: String?) {
        val manager = notificationManager ?: return
        val notification = newReplyBuilder()
            .setContentTitle(getString(R.string.notification_error_title))
            .setContentText(error ?: getString(R.string.notification_error_text))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID_ERROR, notification)
    }

    private fun updateProgressNotification(
        task: DownloadTask,
        current: Long,
        total: Long,
        force: Boolean,
    ) {
        val builder = progressBuilder ?: return
        val manager = notificationManager ?: return

        val now = System.currentTimeMillis()
        if (!force && now - task.lastUpdateTime < PROGRESS_INTERVAL_MS &&
            (total <= 0 || current < total)
        ) {
            return
        }

        val bytesSinceLast = current - task.lastUpdateBytes
        val timeSinceLast = (now - task.lastUpdateTime).coerceAtLeast(1)
        val speedBps = bytesSinceLast.toDouble() * 1000 / timeSinceLast

        task.lastUpdateTime = now
        task.lastUpdateBytes = current

        val text = StringBuilder()
        text.append("↓ ").append(FormatUtils.speed(speedBps))
        text.append("  ").append(FormatUtils.size(current))
        if (total > 0) {
            text.append("/").append(FormatUtils.size(total))
            val percent = ((current * 100) / total).coerceAtMost(100L).toInt()
            builder.setProgress(100, percent, false)
            val remaining = total - current
            if (speedBps > 0 && remaining > 0) {
                text.append("  ").append(FormatUtils.eta((remaining / speedBps).toLong()))
            }
        } else {
            builder.setProgress(0, 0, true)
        }

        builder.setContentText(text.toString())
        manager.notify(NOTIFICATION_ID_PROGRESS, builder.build())
    }

    // ---------------------------------------------------------------- downloading

    /**
     * One download attempt chain. Instances are never reused, so retries keep
     * their own destination file and the session id guards against a newer
     * request taking over.
     */
    private inner class DownloadTask(
        val session: Int,
        /** Identifies this start request so a stale task cannot stop a newer one. */
        val startId: Int,
        val url: String,
        val suggestedFileName: String?,
        val callingPackage: String?,
    ) : Runnable {

        val startedAtMillis: Long = System.currentTimeMillis()

        @Volatile
        private var cancelled = false

        @Volatile
        private var worker: Thread? = null

        @Volatile
        private var connection: HttpURLConnection? = null

        // Destination is created once and reused across retries (resume).
        var destUri: Uri? = null
        var destIsSaf = false
        var fileName: String? = null
        var folderName: String? = null

        var totalSize = -1L
        var downloadedSize = 0L
        var lastUpdateTime = 0L
        var lastUpdateBytes = 0L

        /** Set when the direct attempt failed and the system proxy is used. */
        private var usedProxy = false

        fun attachThread(thread: Thread) {
            worker = thread
        }

        fun isActive(): Boolean = !cancelled && activeSession == session

        fun cancel() {
            cancelled = true
            connection?.let {
                try {
                    it.disconnect()
                } catch (ignored: Exception) {
                }
            }
            worker?.interrupt()
        }

        override fun run() {
            try {
                download()
            } catch (e: Exception) {
                if (cancelled || !isActive()) {
                    // The cancel action (or a newer request) already published the
                    // status for this session; do not overwrite it.
                    Log.d(TAG, "Download stopped: ${e.message}")
                } else {
                    Log.e(TAG, "Download failed", e)
                    publishStatus(this, DownloadState.FAILED, e.message, null)
                    showErrorNotification(e.message)
                }
            } finally {
                connection = null
                if (this === activeTask) {
                    activeTask = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                // stopSelf(startId) keeps the service alive when a newer
                // download already owns it.
                stopSelf(startId)
            }
        }

        private fun download() {
            var lastError: IOException? = null
            for (attempt in 1..MAX_RETRIES) {
                ensureActive()
                try {
                    attemptDownload()
                    return
                } catch (e: IOException) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Attempt $attempt/$MAX_RETRIES failed: ${e.message}",
                    )
                    if (!isActive() || attempt >= MAX_RETRIES || !isRetryable(e)) {
                        cleanupPartialFile()
                        throw e
                    }
                    // Keep the partial file: the next attempt resumes with Range.
                    val waitMs = (1L shl (attempt - 1)) * 1000
                    try {
                        Thread.sleep(waitMs)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        cleanupPartialFile()
                        throw IOException("Download cancelled")
                    }
                }
            }
            throw lastError ?: IOException("Download failed")
        }

        /**
         * Tries a direct connection first. If it fails, or a local proxy
         * returns a proxy rejection code, retry through the system proxy.
         * Direct-first matches how the app behaved before the proxy helper and
         * avoids local proxies that reject downloads with a non-standard 618.
         */
        private fun openInitialConnection(
            url: URL,
            startByte: Long,
        ): Pair<HttpURLConnection, Int> {
            var directError: IOException? = null
            var directCode = -1

            try {
                val direct = openHttpConnection(url, startByte, null, forceDirect = true)
                connection = direct
                directCode = direct.responseCode
                if (!shouldFallbackToProxy(directCode)) {
                    return direct to directCode
                }
                Log.w(TAG, "Direct connection returned HTTP $directCode; retrying via proxy")
                direct.disconnect()
            } catch (e: IOException) {
                directError = e
                Log.w(TAG, "Direct connection failed; retrying via system proxy", e)
            }

            val proxy = getSystemHttpProxy()
            if (proxy == null) {
                throw directError ?: IOException("HTTP $directCode")
            }
            usedProxy = true
            val http = openHttpConnection(url, startByte, proxy)
            connection = http
            return http to http.responseCode
        }

        private fun shouldFallbackToProxy(responseCode: Int): Boolean =
            responseCode == HTTP_PROXY_AUTHENTICATION_REQUIRED ||
                responseCode == HTTP_PROXY_REJECTED ||
                responseCode >= 500

        private fun attemptDownload() {
            val resolver = contentResolver
            var output: FileOutputStream? = null
            var safDescriptor: ParcelFileDescriptor? = null
            var httpInput: InputStream? = null
            var isSaf = destIsSaf
            var truncate = false
            var skipStream = false
            var startByte = 0L

            try {
                // --- 1. Open HTTP connection (Range header enables resume) ---
                destUri?.let { startByte = queryExistingSize(it) }

                val target = URL(url)
                val initial = openInitialConnection(target, startByte)
                var http = initial.first
                var responseCode = initial.second

                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE && startByte > 0) {
                    val rangeTotal = parseContentRangeTotal(http.getHeaderField("Content-Range"))
                    if (rangeTotal > 0 && startByte >= rangeTotal) {
                        // Already complete; a previous run failed while finalizing.
                        Log.d(TAG, "File already complete ($startByte/$rangeTotal)")
                        totalSize = rangeTotal
                        downloadedSize = startByte
                        skipStream = true
                    } else {
                        Log.d(TAG, "Server file changed (416), restarting")
                        startByte = 0
                        truncate = true
                        http.disconnect()
                        http = openHttpConnection(
                            target,
                            0,
                            if (usedProxy) getSystemHttpProxy() else null,
                            forceDirect = !usedProxy,
                        )
                        connection = http
                        responseCode = http.responseCode
                    }
                }

                val contentType = http.contentType
                val contentDisposition = http.getHeaderField("Content-Disposition")
                val contentLength = http.contentLengthLong

                if (!skipStream) {
                    when (responseCode) {
                        HttpURLConnection.HTTP_PARTIAL ->
                            Log.d(TAG, "Server supports range, resuming at $startByte")
                        HttpURLConnection.HTTP_OK -> if (startByte > 0) {
                            Log.d(TAG, "Server ignored Range, restarting")
                            truncate = true
                            startByte = 0
                        }
                        else -> throw IOException(
                            if (responseCode == HTTP_PROXY_REJECTED) {
                                "HTTP 618: " + getString(R.string.download_proxy_rejected)
                            } else {
                                "HTTP $responseCode"
                            },
                        )
                    }

                    totalSize = contentLength
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        val rangeTotal = parseContentRangeTotal(
                            http.getHeaderField("Content-Range"),
                        )
                        totalSize = if (rangeTotal > 0) {
                            rangeTotal
                        } else if (contentLength > 0) {
                            startByte + contentLength
                        } else {
                            -1
                        }
                    }
                }

                downloadedSize = startByte
                lastUpdateTime = System.currentTimeMillis()
                lastUpdateBytes = startByte
                publishStatus(this, DownloadState.DOWNLOADING, null, null)
                updateProgressNotification(this, downloadedSize, totalSize, true)

                // --- 2. Resolve the file name ---
                if (destUri == null) {
                    fileName = resolveFileName(contentDisposition, contentType)
                }
                publishStatus(this, DownloadState.DOWNLOADING, null, null)

                // --- 3. Create the destination once, reuse it on retries ---
                if (destUri == null) {
                    createDestination()
                    isSaf = destIsSaf
                }
                val destination = destUri ?: throw IOException("Failed to create destination")

                // --- 4. Stream straight into the destination ---
                if (!skipStream) {
                    val mode = if (truncate) "rwt" else "rw"
                    val descriptor = resolver.openFileDescriptor(destination, mode)
                        ?: throw IOException("Failed to open output stream")
                    safDescriptor = descriptor
                    val fileOutput = FileOutputStream(descriptor.fileDescriptor)
                    output = fileOutput
                    if (!truncate && startByte > 0) {
                        // ParcelFileDescriptor has no seekTo(); position the channel.
                        fileOutput.channel.position(startByte)
                    }

                    val input = http.inputStream
                    httpInput = input
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        ensureActive()
                        fileOutput.write(buffer, 0, read)
                        downloadedSize += read
                        publishStatus(this, DownloadState.DOWNLOADING, null, null)
                        updateProgressNotification(this, downloadedSize, totalSize, false)
                    }
                    fileOutput.flush()
                    Log.d(TAG, "Streamed $downloadedSize bytes to destination")
                }

                // --- 5. Finalize ---
                if (!isSaf) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, false)
                    }
                    resolver.update(destination, values, null, null)
                }

                val savedFileName = queryDisplayName(destination, fileName ?: "")
                val completedUri = destination
                destUri = null // success: nothing left to clean up
                fileName = savedFileName

                // --- 6. Report success ---
                Log.d(TAG, "Download complete: $savedFileName")
                publishStatus(this, DownloadState.COMPLETED, null, completedUri)
                showResultNotification(this, completedUri)
                toastSaved(savedFileName, folderName)
            } finally {
                closeQuietly(httpInput)
                closeQuietly(output)
                closeQuietly(safDescriptor)
                connection?.disconnect()
                connection = null
            }
        }

        private fun resolveFileName(
            contentDisposition: String?,
            contentType: String?,
        ): String {
            var name = extractFileName(contentDisposition, url)
            if (name.isNullOrEmpty()) {
                name = if (!suggestedFileName.isNullOrEmpty()) suggestedFileName else "download"
            }
            if (!name.contains(".")) {
                getExtensionFromMimeType(contentType)?.let { name += it }
            }
            // A CDN may answer a ".apk" link with a generic download name; the
            // installer path depends on the extension, so restore it.
            return FileUtils.ensureInstallerExtension(name, contentType)
        }

        private fun createDestination() {
            val resolver = contentResolver
            val preferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
            val useCustomFolder = preferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false)
            val customFolderPath = if (useCustomFolder) {
                preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null)
            } else {
                null
            }

            if (customFolderPath != null) {
                createSafDestination(resolver, customFolderPath)
            } else {
                createMediaStoreDestination(resolver)
            }

            destIsSaf = folderName != null
            publishStatus(this, DownloadState.DOWNLOADING, null, null)
        }

        private fun createSafDestination(resolver: ContentResolver, treePath: String) {
            val treeUri = Uri.parse(treePath)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val name = fileName ?: "download"
            val mimeType = FileUtils.getMimeTypeForFileName(name)
            Log.d(TAG, "SAF createDocument: mimeType=$mimeType, name=$name")

            var created = tryCreateDocument(resolver, documentUri, mimeType, name)

            if (created == null) {
                val parts = FileUtils.splitFileName(name)
                var index = 1
                while (index <= 999 && created == null) {
                    val candidate = "${parts[0]} ($index)${parts[1]}"
                    created = tryCreateDocument(resolver, documentUri, mimeType, candidate)
                    if (created != null) fileName = candidate
                    index++
                }
            }

            if (created == null) throw IOException("Failed to create file in custom folder")

            destUri = created
            folderName = treeUri.lastPathSegment
                ?.replace("tree:", "")
                ?.replace("primary:", "")
        }

        private fun tryCreateDocument(
            resolver: ContentResolver,
            parent: Uri,
            mimeType: String,
            name: String,
        ): Uri? = try {
            DocumentsContract.createDocument(resolver, parent, mimeType, name)
        } catch (e: Exception) {
            Log.d(TAG, "SAF createDocument failed for $name", e)
            null
        }

        private fun createMediaStoreDestination(resolver: ContentResolver) {
            var downloadDir = Environment.DIRECTORY_DOWNLOADS
            if (callingPackage != null &&
                getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                    .getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)
            ) {
                loadLabelForPackage(callingPackage)?.let { downloadDir += "/$it" }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, downloadDir)
                put(MediaStore.MediaColumns.IS_PENDING, true)
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName ?: "download")
            }

            destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to create MediaStore entry")
        }

        private fun queryDisplayName(uri: Uri, fallback: String): String {
            try {
                contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        if (index != -1) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrEmpty()) return name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read the saved file name", e)
            }
            return fallback
        }

        private fun ensureActive() {
            if (!isActive()) throw IOException("Download cancelled")
        }

        private fun cleanupPartialFile() {
            val uri = destUri ?: return
            Log.d(TAG, "Cleaning up partial file: $uri")
            try {
                if (destIsSaf) {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete the partial file", e)
                // Never leave an invisible pending MediaStore entry behind.
                if (!destIsSaf) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, false)
                        }
                        contentResolver.update(uri, values, null, null)
                    } catch (ignored: Exception) {
                    }
                }
            }
            destUri = null
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun toastSaved(fileName: String, folderName: String?) {
        val message = if (folderName != null) {
            getString(R.string.toast_saved_custom, fileName, folderName)
        } else {
            getString(R.string.toast_saved, fileName)
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@DownloadService, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (ignored: IOException) {
        }
    }

    private fun openHttpConnection(
        url: URL,
        startByte: Long,
        proxy: Proxy?,
        forceDirect: Boolean = false,
    ): HttpURLConnection {
        val connection: HttpURLConnection = when {
            proxy != null -> {
                Log.d(TAG, "Using system HTTP proxy: ${proxy.address()}")
                url.openConnection(proxy) as HttpURLConnection
            }
            forceDirect -> {
                // Proxy.NO_PROXY is required: plain openConnection() would still
                // use the platform's default proxy selector, defeating fallback.
                url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            }
            else -> {
                // No proxy (or a PAC file): let the platform pick the route.
                url.openConnection() as HttpURLConnection
            }
        }
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SaveCopy")
        if (startByte > 0) {
            connection.setRequestProperty("Range", "bytes=$startByte-")
        }
        return connection
    }

    /**
     * Returns the current default-network HTTP proxy. PAC proxies are left to
     * Android's own URL handling, which can evaluate the PAC rules per host.
     */
    private fun getSystemHttpProxy(): Proxy? {
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            val proxyInfo: ProxyInfo = connectivityManager?.defaultProxy ?: return null
            val host = proxyInfo.host
            if (host == null || proxyInfo.port <= 0) return null
            val pacFileUrl = proxyInfo.pacFileUrl
            if (pacFileUrl != null && Uri.EMPTY != pacFileUrl) {
                Log.d(TAG, "System PAC proxy detected; using Android proxy selection")
                return null
            }
            return Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(host, proxyInfo.port),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to read the system proxy", e)
            return null
        }
    }

    private fun queryExistingSize(uri: Uri): Long {
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                return descriptor.statSize.coerceAtLeast(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query the existing size", e)
        }
        return 0
    }

    private fun parseContentRangeTotal(contentRange: String?): Long {
        if (contentRange == null) return -1
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0) return -1
        return try {
            val total = contentRange.substring(slash + 1).trim().toLong()
            if (total > 0) total else -1
        } catch (e: NumberFormatException) {
            -1
        }
    }

    private fun isRetryable(e: IOException): Boolean {
        if (e is SocketTimeoutException) return true
        if (e is ConnectException || e is UnknownHostException) return true
        val message = e.message
        if (message != null && message.startsWith("HTTP ")) {
            // 5xx/408/429 are transient; other 4xx will not improve on retry.
            return message.startsWith("HTTP 5") ||
                message.startsWith("HTTP 408") ||
                message.startsWith("HTTP 429")
        }
        return true
    }

    private fun extractFileName(contentDisposition: String?, url: String): String? {
        if (contentDisposition != null) {
            CONTENT_DISPOSITION_FILE_NAME.find(contentDisposition)?.let { match ->
                val name = match.groupValues[1].replace(Regex("['\"]"), "").trim()
                if (name.isNotEmpty()) return sanitizeFileName(name)
            }

            CONTENT_DISPOSITION_FILE_NAME_STAR.find(contentDisposition)?.let { match ->
                var encoded = match.groupValues[1].trim()
                try {
                    if (encoded.contains("'")) {
                        encoded = encoded.substring(encoded.lastIndexOf("'") + 1)
                    }
                    return sanitizeFileName(URLDecoder.decode(encoded, "UTF-8"))
                } catch (ignored: Exception) {
                }
            }
        }

        try {
            val path = URL(url).path
            if (path.contains("/")) {
                var name = path.substring(path.lastIndexOf('/') + 1)
                if (name.isNotEmpty()) {
                    // Percent-encoded names show up as %E4%B8%AD... in the path.
                    try {
                        name = URLDecoder.decode(name, "UTF-8")
                    } catch (ignored: Exception) {
                    }
                    return sanitizeFileName(name)
                }
            }
        } catch (ignored: Exception) {
        }
        return null
    }

    private fun sanitizeFileName(fileName: String): String {
        var sanitized = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (sanitized.length > 200) {
            val dot = sanitized.lastIndexOf('.')
            sanitized = if (dot > 0) {
                val extension = sanitized.substring(dot)
                sanitized.substring(0, 200 - extension.length) + extension
            } else {
                sanitized.substring(0, 200)
            }
        }
        return sanitized
    }

    private fun getExtensionFromMimeType(mimeType: String?): String? {
        if (mimeType == null) return null
        return when (mimeType.lowercase(Locale.ROOT)) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "audio/mpeg" -> ".mp3"
            "audio/wav" -> ".wav"
            "application/pdf" -> ".pdf"
            "application/zip" -> ".zip"
            "application/x-apk", FileUtils.MIME_APK -> ".apk"
            else -> {
                if (mimeType.contains("/")) {
                    val subtype = mimeType.split("/")[1]
                    if (subtype == "octet-stream") {
                        ".bin"
                    } else {
                        "." + subtype.replace(Regex("[^a-z0-9]"), "")
                    }
                } else {
                    null
                }
            }
        }
    }

    private fun loadLabelForPackage(packageName: String): String? {
        try {
            // Force English so the folder name does not change with the UI locale.
            val packageContext = createPackageContext(packageName, 0)
            val configuration = Configuration(packageContext.resources.configuration)
            configuration.setLocale(Locale.ENGLISH)
            val resources: Resources =
                packageContext.createConfigurationContext(configuration).resources

            val info: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            if (info.labelRes != 0) return resources.getString(info.labelRes)
            return info.nonLocalizedLabel?.toString() ?: info.packageName
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } catch (e: Resources.NotFoundException) {
            return null
        } catch (e: NullPointerException) {
            return null
        }
    }

    private fun truncateUrl(url: String?): String {
        if (url == null) return ""
        if (url.length <= 50) return url
        try {
            val host = Uri.parse(url).host
            if (host != null) return "$host/..."
        } catch (ignored: Exception) {
        }
        return url.substring(0, 47) + "..."
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DownloadService"

        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_RESULT = 1002
        private const val NOTIFICATION_ID_ERROR = 1003

        private const val REQUEST_DETAILS = 100
        private const val REQUEST_CANCEL = 101
        private const val REQUEST_OPEN = 102

        private const val BUFFER_SIZE = 64 * 1024
        private const val CHANNEL_ID = "download_channel"
        private const val MAX_RETRIES = 3

        /** Four refreshes per second keeps the notification responsive. */
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val CONNECT_TIMEOUT = 20_000
        private const val READ_TIMEOUT = 60_000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_PROXY_AUTHENTICATION_REQUIRED = 407

        /** Non-standard rejection code returned by some local HTTP proxies. */
        private const val HTTP_PROXY_REJECTED = 618

        private val CONTENT_DISPOSITION_FILE_NAME =
            Regex("filename[^;=\\n]*=((['\"]).*?\\2|[^;\\n]*)")
        private val CONTENT_DISPOSITION_FILE_NAME_STAR =
            Regex("filename\\*[^;]*=([^;]*)", RegexOption.IGNORE_CASE)

        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_CALLING_PACKAGE = "calling_package"
        const val ACTION_CANCEL = "app.rikka.savecopy.DOWNLOAD_CANCEL"

        @Volatile
        private var downloadStatus: DownloadStatus? = null

        fun getDownloadStatus(): DownloadStatus? = downloadStatus
    }
}
