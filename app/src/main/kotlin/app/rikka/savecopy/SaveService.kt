package app.rikka.savecopy

import android.app.Service
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Copies the bytes behind a received URI into the user's Download folder (or a
 * custom SAF folder). All URIs of a request are handled in one pass and the
 * result is reported once, so a multi-file share does not fire a toast storm.
 */
class SaveService : Service() {

    fun interface SaveCallback {
        fun onSaveComplete(message: String?, error: String?)
    }

    companion object {
        const val CALLING_PACKAGE = "callingPackage"

        private const val TAG = "SaveService"
        private const val ACTION_SAVE_COMPLETE = "app.rikka.savecopy.SAVE_COMPLETE"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_ERROR = "error"
        private const val BUFFER_SIZE = 64 * 1024

        /** Static callback keeps the activity informed on ROMs that throttle broadcasts. */
        @Volatile
        private var callback: SaveCallback? = null

        fun setCallback(value: SaveCallback?) {
            callback = value
        }
    }

    /** IntentService replacement: requests are handled one after another. */
    private lateinit var executor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "save-thread")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        executor.execute {
            try {
                onSave(intent)
            } finally {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onSave(intent: Intent) {
        val uris = IntentDataUtils.collectUris(intent, true)
        if (uris.isEmpty()) {
            Log.e(TAG, "No URI in the save request, action=${intent.action}")
            notifyError(getString(R.string.notification_error_text))
            return
        }

        val callingPackage = intent.getStringExtra(CALLING_PACKAGE)
        var saved = 0
        var lastFileName: String? = null
        var lastFolder: String? = null

        for (uri in uris) {
            try {
                val outcome = doSave(uri, callingPackage)
                saved++
                lastFileName = outcome.fileName
                lastFolder = outcome.folderName
            } catch (e: Throwable) {
                Log.e(TAG, "Unable to save $uri", e)
                val cause = e.cause ?: e
                notifyError(getString(R.string.notification_error_text) + "\n\n" + cause.message)
                return
            }
        }

        if (saved == 1) {
            val fileName = lastFileName ?: ""
            notifyMessage(
                if (lastFolder != null) {
                    getString(R.string.toast_saved_custom, fileName, lastFolder)
                } else {
                    getString(R.string.toast_saved, fileName)
                },
            )
        } else {
            notifyMessage(getString(R.string.toast_saved_multiple, saved))
        }
    }

    private class SaveOutcome(
        val fileName: String,
        val folderName: String?,
    )

    private fun doSave(data: Uri, callingPackage: String?): SaveOutcome {
        if (IntentDataUtils.isHttpUri(data)) {
            throw SaveException(getString(R.string.error_http_url_not_supported))
        }

        val resolver = contentResolver
        var displayName = "unknown-${System.currentTimeMillis()}"
        resolver.query(data, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }
        }

        val customFolderPath = customFolderPath()
        val fileUri: Uri
        var savedName: String

        if (customFolderPath != null) {
            fileUri = createSafDocument(resolver, customFolderPath, displayName)
            savedName = displayName
        } else {
            fileUri = createMediaStoreEntry(resolver, displayName, callingPackage)
            savedName = displayName
        }

        copyBytes(resolver, data, fileUri, customFolderPath != null)

        if (customFolderPath == null) {
            savedName = publishAndReadName(resolver, fileUri, displayName)
        }

        return SaveOutcome(
            savedName,
            if (customFolderPath != null) folderDisplayName(customFolderPath) else null,
        )
    }

    private fun customFolderPath(): String? {
        val preferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
        if (!preferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false)) return null
        return preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null)
    }

    private fun createSafDocument(
        resolver: ContentResolver,
        treePath: String,
        displayName: String,
    ): Uri {
        val treeUri = Uri.parse(treePath)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val mimeType = FileUtils.getMimeTypeForFileName(displayName)

        tryCreate(resolver, parent, mimeType, displayName)?.let { return it }

        // Name collision: pick the first free "name (n).ext".
        val parts = FileUtils.splitFileName(displayName)
        for (i in 1..999) {
            val candidate = "${parts[0]} ($i)${parts[1]}"
            tryCreate(resolver, parent, mimeType, candidate)?.let { return it }
        }
        throw SaveException("Failed to create file in custom folder")
    }

    private fun tryCreate(
        resolver: ContentResolver,
        parent: Uri,
        mimeType: String,
        name: String,
    ): Uri? = try {
        DocumentsContract.createDocument(resolver, parent, mimeType, name)
    } catch (e: Exception) {
        Log.d(TAG, "createDocument failed for $name", e)
        null
    }

    private fun createMediaStoreEntry(
        resolver: ContentResolver,
        displayName: String,
        callingPackage: String?,
    ): Uri {
        var relativePath = Environment.DIRECTORY_DOWNLOADS
        if (callingPackage != null &&
            getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                .getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)
        ) {
            loadLabelForPackage(callingPackage)?.let { relativePath += "/$it" }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, true)
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        }

        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw SaveException("can't insert")
    }

    private fun copyBytes(
        resolver: ContentResolver,
        source: Uri,
        destination: Uri,
        saf: Boolean,
    ) {
        val input: InputStream = try {
            resolver.openInputStream(source)
        } catch (e: Exception) {
            throw SaveException(getString(R.string.error_cannot_open_file) + ": " + e.message, e)
        } ?: throw SaveException("can't open data")

        var output: OutputStream? = null
        try {
            if (saf) {
                // SAF documents must go through a descriptor, otherwise the pipe
                // buffer overflows for large files.
                val descriptor: ParcelFileDescriptor = resolver.openFileDescriptor(destination, "w")
                    ?: throw SaveException("can't open output")
                output = FileOutputStream(descriptor.fileDescriptor)
            } else {
                output = resolver.openOutputStream(destination, "w")
                    ?: throw SaveException("can't open output")
            }

            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            output.flush()
        } finally {
            closeQuietly(input)
            closeQuietly(output)
        }
    }

    /** Clears IS_PENDING and returns the name MediaStore actually assigned. */
    private fun publishAndReadName(
        resolver: ContentResolver,
        fileUri: Uri,
        fallback: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, false)
        }
        resolver.update(fileUri, values, null, null)

        try {
            resolver.query(
                fileUri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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

    private fun folderDisplayName(treePath: String): String {
        val segment = Uri.parse(treePath).lastPathSegment ?: return "custom folder"
        return segment.replace("tree:", "").replace("primary:", "")
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (ignored: IOException) {
        }
    }

    private fun notifyMessage(message: String?) {
        callback?.onSaveComplete(message, null)

        val broadcast = Intent(ACTION_SAVE_COMPLETE).putExtra(EXTRA_FILE_NAME, message ?: "")
        sendBroadcast(broadcast)
    }

    private fun notifyError(error: String) {
        callback?.onSaveComplete(null, error)

        val broadcast = Intent(ACTION_SAVE_COMPLETE).putExtra(EXTRA_ERROR, error)
        sendBroadcast(broadcast)
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
}
