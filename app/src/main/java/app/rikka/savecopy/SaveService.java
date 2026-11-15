package app.rikka.savecopy;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copies the bytes behind a received URI into the user's Download folder (or a
 * custom SAF folder). All URIs of a request are handled in one pass and the
 * result is reported once, so a multi-file share does not fire a toast storm.
 */
public class SaveService extends Service {

    static final String CALLING_PACKAGE = "callingPackage";

    private static final String TAG = "SaveService";
    private static final String ACTION_SAVE_COMPLETE = "app.rikka.savecopy.SAVE_COMPLETE";
    private static final String EXTRA_FILE_NAME = "file_name";
    private static final String EXTRA_ERROR = "error";
    private static final int BUFFER_SIZE = 64 * 1024;

    /** Static callback keeps the activity informed on ROMs that throttle broadcasts. */
    private static SaveCallback sCallback;

    /** IntentService replacement: requests are handled one after another. */
    private ExecutorService executor;

    public interface SaveCallback {
        void onSaveComplete(String message, String error);
    }

    public static void setCallback(@Nullable SaveCallback callback) {
        sCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "save-thread"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        executor.execute(() -> {
            try {
                onSave(intent);
            } finally {
                stopSelfResult(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onSave(Intent intent) {
        if (intent == null) return;

        List<Uri> uris = IntentDataUtils.collectUris(intent, true);
        if (uris.isEmpty()) {
            Log.e(TAG, "No URI in the save request, action=" + intent.getAction());
            notifyError(getString(R.string.notification_error_text));
            return;
        }

        String callingPackage = intent.getStringExtra(CALLING_PACKAGE);
        int saved = 0;
        String lastFileName = null;
        String lastFolder = null;

        for (Uri uri : uris) {
            try {
                SaveOutcome outcome = doSave(uri, intent.getType(), callingPackage);
                saved++;
                lastFileName = outcome.fileName;
                lastFolder = outcome.folderName;
            } catch (Throwable e) {
                Log.e(TAG, "Unable to save " + uri, e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                notifyError(getString(R.string.notification_error_text) + "\n\n" + cause.getMessage());
                return;
            }
        }

        if (saved == 1) {
            notifyMessage(lastFolder != null
                    ? getString(R.string.toast_saved_custom, lastFileName, lastFolder)
                    : getString(R.string.toast_saved, lastFileName));
        } else {
            notifyMessage(getString(R.string.toast_saved_multiple, saved));
        }
    }

    private static final class SaveOutcome {
        final String fileName;
        @Nullable
        final String folderName;

        SaveOutcome(String fileName, @Nullable String folderName) {
            this.fileName = fileName;
            this.folderName = folderName;
        }
    }

    private SaveOutcome doSave(Uri data, @Nullable String type, @Nullable String callingPackage)
            throws IOException, SaveException {
        if (data == null) throw new SaveException("data is null");
        if (IntentDataUtils.isHttpUri(data)) {
            throw new SaveException(getString(R.string.error_http_url_not_supported));
        }

        ContentResolver cr = getContentResolver();
        String displayName = "unknown-" + System.currentTimeMillis();
        try (Cursor cursor = cr.query(data, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
            }
        }

        String customFolderPath = customFolderPath();
        Uri fileUri;
        String savedName;

        if (customFolderPath != null) {
            fileUri = createSafDocument(cr, customFolderPath, displayName);
            savedName = displayName;
        } else {
            fileUri = createMediaStoreEntry(cr, displayName, callingPackage);
            savedName = displayName;
        }

        copyBytes(cr, data, fileUri, customFolderPath != null);

        if (customFolderPath == null) {
            savedName = publishAndReadName(cr, fileUri, displayName);
        }

        return new SaveOutcome(savedName, customFolderPath != null
                ? folderDisplayName(customFolderPath) : null);
    }

    @Nullable
    private String customFolderPath() {
        boolean useCustomFolder = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                .getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
        if (!useCustomFolder) return null;
        return getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                .getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
    }

    private Uri createSafDocument(ContentResolver cr, String treePath, String displayName)
            throws SaveException {
        Uri treeUri = Uri.parse(treePath);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        String mimeType = FileUtils.getMimeTypeForFileName(displayName);

        Uri created = tryCreate(cr, parent, mimeType, displayName);
        if (created != null) return created;

        // Name collision: pick the first free "name (n).ext".
        String[] parts = FileUtils.spiltFileName(displayName);
        for (int i = 1; i <= 999; i++) {
            String candidate = parts[0] + " (" + i + ")" + parts[1];
            created = tryCreate(cr, parent, mimeType, candidate);
            if (created != null) return created;
        }
        throw new SaveException("Failed to create file in custom folder");
    }

    @Nullable
    private Uri tryCreate(ContentResolver cr, Uri parent, String mimeType, String name) {
        try {
            return DocumentsContract.createDocument(cr, parent, mimeType, name);
        } catch (Exception e) {
            Log.d(TAG, "createDocument failed for " + name, e);
            return null;
        }
    }

    private Uri createMediaStoreEntry(ContentResolver cr, String displayName,
                                      @Nullable String callingPackage) throws SaveException {
        String relativePath = Environment.DIRECTORY_DOWNLOADS;
        if (callingPackage != null
                && getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)
                .getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
            String label = loadLabelForPackage(callingPackage);
            if (label != null) relativePath += "/" + label;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, true);
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);

        Uri fileUri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (fileUri == null) throw new SaveException("can't insert");
        return fileUri;
    }

    private void copyBytes(ContentResolver cr, Uri source, Uri destination, boolean saf)
            throws IOException, SaveException {
        InputStream in;
        try {
            in = cr.openInputStream(source);
        } catch (Exception e) {
            throw new SaveException(getString(R.string.error_cannot_open_file) + ": " + e.getMessage());
        }
        if (in == null) throw new SaveException("can't open data");

        OutputStream out = null;
        try {
            if (saf) {
                // SAF documents must go through a descriptor, otherwise the pipe
                // buffer overflows for large files.
                ParcelFileDescriptor pfd = cr.openFileDescriptor(destination, "w");
                if (pfd == null) throw new SaveException("can't open output");
                out = new FileOutputStream(pfd.getFileDescriptor());
            } else {
                out = cr.openOutputStream(destination, "w");
            }
            if (out == null) throw new SaveException("can't open output");

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** Clears IS_PENDING and returns the name MediaStore actually assigned. */
    private String publishAndReadName(ContentResolver cr, Uri fileUri, String fallback) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, false);
        cr.update(fileUri, values, null, null);

        try (Cursor cursor = cr.query(fileUri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
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

    private String folderDisplayName(String treePath) {
        String segment = Uri.parse(treePath).getLastPathSegment();
        if (segment == null) return "custom folder";
        return segment.replace("tree:", "").replace("primary:", "");
    }

    private void closeQuietly(@Nullable java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private void notifyMessage(@Nullable String message) {
        SaveCallback callback = sCallback;
        if (callback != null) callback.onSaveComplete(message, null);

        Intent broadcast = new Intent(ACTION_SAVE_COMPLETE).putExtra(EXTRA_FILE_NAME, message);
        sendBroadcast(broadcast);
    }

    private void notifyError(String error) {
        SaveCallback callback = sCallback;
        if (callback != null) callback.onSaveComplete(null, error);

        Intent broadcast = new Intent(ACTION_SAVE_COMPLETE).putExtra(EXTRA_ERROR, error);
        sendBroadcast(broadcast);
    }

    @Nullable
    private String loadLabelForPackage(@NonNull String packageName) {
        try {
            // Force English so the folder name does not change with the UI locale.
            Context packageContext = createPackageContext(packageName, 0);
            Configuration configuration =
                    new Configuration(packageContext.getResources().getConfiguration());
            configuration.setLocale(Locale.ENGLISH);
            Resources resources =
                    packageContext.createConfigurationContext(configuration).getResources();

            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            if (info.labelRes != 0) return resources.getString(info.labelRes);
            return info.nonLocalizedLabel != null ? info.nonLocalizedLabel.toString()
                    : info.packageName;
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException
                 | NullPointerException e) {
            return null;
        }
    }
}
