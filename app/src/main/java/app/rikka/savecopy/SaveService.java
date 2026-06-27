package app.rikka.savecopy;

import android.annotation.TargetApi;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Html;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class SaveService extends IntentService {

    static final String CALLING_PACKAGE = "callingPackage";

    private static final String ACTION_SAVE_COMPLETE = "app.rikka.savecopy.SAVE_COMPLETE";
    private static final String EXTRA_FILE_NAME = "file_name";
    private static final String EXTRA_ERROR = "error";

    // Static callback to notify Activity directly (more reliable than broadcast on MIUI)
    private static SaveCallback sCallback;

    public interface SaveCallback {
        void onSaveComplete(String fileName, String error);
    }

    public static void setCallback(SaveCallback callback) {
        sCallback = callback;
    }

    private static final String TAG = "SaveService";

    public SaveService() {
        super("save-thread");
    }

    public SaveService(String name) {
        super("save-thread");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        onSave(intent);
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

    private void onSave(Intent intent) {
        String callingPackage = intent.getStringExtra(CALLING_PACKAGE);
        int[] id = new int[]{Integer.MIN_VALUE};

        String action = intent.getAction();
        String type = intent.getType();
        Uri data = intent.getData();
        ClipData clipData = intent.getClipData();
        
        Log.d(TAG, "onSave called - action: " + action + ", type: " + type + ", data: " + data);
        Log.d(TAG, "has ClipData: " + (clipData != null));
        if (clipData != null) {
            Log.d(TAG, "ClipData item count: " + clipData.getItemCount());
        }
        Log.d(TAG, "has EXTRA_STREAM: " + intent.hasExtra(Intent.EXTRA_STREAM));
        Log.d(TAG, "has EXTRA_TEXT: " + intent.hasExtra(Intent.EXTRA_TEXT));

        List<Uri> list = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action)) {
            if (data != null) {
                list.add(data);
                Log.d(TAG, "Added data from ACTION_VIEW: " + data);
            }
        } else if (clipData != null && clipData.getItemCount() > 0) {
            // Extract Uris from ClipData
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) {
                    list.add(uri);
                    Log.d(TAG, "Added Uri from ClipData[" + i + "]: " + uri);
                }
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            // First try single Uri (most common case)
            try {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    list.add(uri);
                    Log.d(TAG, "Added single Uri from EXTRA_STREAM: " + uri);
                } else {
                    Log.w(TAG, "getParcelableExtra returned null for EXTRA_STREAM");
                }
            } catch (ClassCastException e) {
                Log.w(TAG, "ClassCastException on getParcelableExtra, trying ArrayList", e);
                // Fallback to ArrayList
                ArrayList<Uri> uriList = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uriList != null && !uriList.isEmpty()) {
                    list.addAll(uriList);
                    Log.d(TAG, "Added " + uriList.size() + " Uris from ArrayList");
                } else {
                    Log.w(TAG, "getParcelableArrayListExtra returned null or empty");
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // For SEND_MULTIPLE, try ArrayList first
            try {
                ArrayList<Uri> extras = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (extras != null) {
                    list.addAll(extras);
                    Log.d(TAG, "Added " + extras.size() + " Uris from SEND_MULTIPLE");
                } else {
                    Log.w(TAG, "SEND_MULTIPLE: getParcelableArrayListExtra returned null");
                }
            } catch (ClassCastException e) {
                Log.w(TAG, "SEND_MULTIPLE: ClassCastException, trying single Uri", e);
                // Fallback to single Uri
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    list.add(uri);
                    Log.d(TAG, "SEND_MULTIPLE: Added single Uri as fallback: " + uri);
                }
            }
        }

        // Handle empty list case - notify via callback
        if (list.isEmpty()) {
            Log.e(TAG, "List is empty! Action: " + action + ", Data: " + data + ", has ClipData: " + (clipData != null) + ", has EXTRA_STREAM: " + intent.hasExtra(Intent.EXTRA_STREAM));
            notifyCallback(null, getString(R.string.notification_error_text));
            stopSelf();
            return;
        }

        Log.d(TAG, "List size: " + list.size() + ", processing " + list);

        for (Uri uri: list) {
            try {
                doSave(uri, type, id, callingPackage);
            } catch (Throwable e) {
                Log.e(TAG, "save " + uri, e);

                Throwable cause = e.getCause() == null ? e : e.getCause();
                String errorMessage = getString(R.string.notification_error_text) + "\n\n" + cause.getMessage();
                notifyCallback(null, errorMessage);
                stopSelf();
            }
        }
    }

    private void doSave(Uri data, String type, int[] _id, String callingPackage) throws IOException, SaveException {
        Context context = this;
        if (data == null) {
            throw new SaveException("data is null");
        }

        // Check if it's an HTTP/HTTPS URL - these cannot be saved directly
        String scheme = data.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            throw new SaveException(getString(R.string.error_http_url_not_supported));
        }

        ContentResolver cr = context.getContentResolver();

        String displayName = "unknown-" + System.currentTimeMillis();
        long totalSize = -1;
        try (Cursor cursor = cr.query(data, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (displayNameIndex != -1) {
                    displayName = cursor.getString(displayNameIndex);
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) {
                    totalSize = cursor.getLong(sizeIndex);
                }
            }
        }

        // Check if custom folder is enabled
        boolean useCustomFolder = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
        String customFolderPath = null;
        if (useCustomFolder) {
            customFolderPath = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
        }

        String download = Environment.DIRECTORY_DOWNLOADS;
        if (customFolderPath != null) {
            // Use custom folder - will be handled separately below
            download = null;
        } else if (getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
            String label = null;
            if (callingPackage != null) label = loadLabelForPackage(callingPackage);

            download += (label != null ? File.separator + label : "");
        }

        InputStream is;
        try {
            is = cr.openInputStream(data);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open input stream for: " + data, e);
            throw new SaveException(getString(R.string.error_cannot_open_file) + ": " + e.getMessage());
        }
        if (is == null) {
            throw new SaveException("can't open data");
        }

        ContentValues values;
        values = new ContentValues();

        Uri fileUri;
        String savedFileName = displayName;

        if (customFolderPath != null) {
            // Use custom folder via SAF
            Uri treeUri = Uri.parse(customFolderPath);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            // Guess mime type from file extension
            String mimeType = FileUtils.getMimeTypeForFileName(displayName);
            Log.d(TAG, "SAF createDocument: mimeType=" + mimeType + ", displayName=" + displayName);

            // Create file in the custom folder with deduplication
            fileUri = null;
            try {
                fileUri = DocumentsContract.createDocument(cr, docUri, mimeType, displayName);
            } catch (Exception e) {
                Log.d(TAG, "SAF createDocument failed, trying deduplication", e);
            }

            // If file exists, try with (1), (2), ... suffixes
            if (fileUri == null) {
                String[] parts = FileUtils.spiltFileName(displayName);
                String baseName = parts[0];
                String ext = parts[1];
                for (int i = 1; i <= 999 && fileUri == null; i++) {
                    String dedupName = baseName + " (" + i + ")" + ext;
                    try {
                        fileUri = DocumentsContract.createDocument(cr, docUri, mimeType, dedupName);
                        if (fileUri != null) {
                            displayName = dedupName;
                            Log.d(TAG, "SAF deduplication succeeded: " + dedupName);
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "SAF deduplication attempt " + i + " failed", e);
                    }
                }
            }

            if (fileUri == null) {
                throw new SaveException("Failed to create file in custom folder");
            }
        } else {
            // Use default MediaStore approach
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                // before Android 11 (actually before 11 DP2), MediaStore can't name the file correctly

                String[] displayParts = FileUtils.spiltFileName(displayName);
                List<String> existingNames = new ArrayList<>();
                for (File file : Optional.ofNullable(new File(Environment.getExternalStorageDirectory(), download).listFiles()).orElse(new File[0])) {
                    String name = file.getName();
                    String[] parts = FileUtils.spiltFileName(name);
                    boolean add = false;
                    if (name.equals(displayName)) {
                        add = true;
                    } else if (displayParts[1].equals(parts[1])) {
                        add = parts[0].matches(String.format("%s \\(\\d+\\)", displayParts[0].replaceAll("([\\\\+*?\\[\\](){}|.^$])", "\\\\$1")));
                    }
                    if (add) {
                        existingNames.add(name);
                    }
                }
                if (!existingNames.isEmpty()) {
                    int index = 1;
                    while (existingNames.contains(displayName)) {
                        displayName = String.format(Locale.ENGLISH, "%s (%d)%s", displayParts[0], index++, displayParts[1]);
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, download);
                values.put(MediaStore.MediaColumns.IS_PENDING, true);
            } else {
                File parent = new File(Environment.getExternalStorageDirectory(), download);
                values.put(MediaStore.MediaColumns.DATA, new File(parent, displayName).getPath());

                // on lower versions, if the folder doesn't exist, insert will fail
                // as we have storage permission, just manually create it

                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);

            Uri tableUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // files insert into Files table will not show in the Download table
                tableUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            } else {
                tableUri = MediaStore.Files.getContentUri("external");
            }
            fileUri = cr.insert(tableUri, values);
            if (fileUri == null) {
                throw new SaveException("can't insert");
            }
        }

        int id = fileUri.toString().hashCode();
        _id[0] = id;

        // No progress notification, only Toast at the end
        OutputStream os;
        if (customFolderPath != null) {
            // For SAF, use ParcelFileDescriptor to avoid pipe buffer limit
            ParcelFileDescriptor pfd = cr.openFileDescriptor(fileUri, "w");
            if (pfd == null) throw new SaveException("can't open output");
            os = new java.io.FileOutputStream(pfd.getFileDescriptor());
        } else {
            os = cr.openOutputStream(fileUri, "w");
        }
        if (os == null) {
            throw new SaveException("can't open output");
        }

        long writeSize = 0;
        byte[] b = new byte[8192];
        for (int r; (r = is.read(b)) != -1; ) {
            os.write(b, 0, r);
            os.flush();

            writeSize += r;
            // No progress notification updates
        }
        os.close();
        is.close();

        Log.d(TAG, writeSize + "/" + totalSize);

        if (customFolderPath != null) {
            // For SAF, we don't need to update IS_PENDING
            savedFileName = displayName;
        } else {
            if (Build.VERSION.SDK_INT >= 29) {
                values = new ContentValues();
                values.put(MediaStore.Images.ImageColumns.IS_PENDING, false);
                cr.update(fileUri, values, null, null);
            }

            String newName = displayName;
            try (Cursor cursor = cr.query(fileUri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (displayNameIndex != -1) {
                        newName = cursor.getString(displayNameIndex);
                    }
                }
            }
            savedFileName = newName;
        }
        
        // Notify via callback (Activity will show Toast)
        if (customFolderPath != null) {
            // Show custom folder path in toast
            Uri treeUri = Uri.parse(customFolderPath);
            String folderName = treeUri.getLastPathSegment();
            if (folderName != null) {
                folderName = folderName.replace("tree:", "").replace("primary:", "");
            }
            notifyCallbackCustom(savedFileName, folderName != null ? folderName : "custom folder", null);
        } else {
            notifyCallback(savedFileName, null);
        }
        stopSelf();
    }

    private void notifyCallback(String fileName, String error) {
        SaveCallback callback = sCallback;
        if (callback != null) {
            if (error != null) {
                callback.onSaveComplete(null, error);
            } else {
                callback.onSaveComplete(getString(R.string.toast_saved, fileName), null);
            }
        }
        // Also send broadcast as fallback
        Intent broadcastIntent = new Intent(ACTION_SAVE_COMPLETE);
        if (error != null) {
            broadcastIntent.putExtra(EXTRA_ERROR, error);
        } else {
            broadcastIntent.putExtra(EXTRA_FILE_NAME, fileName);
        }
        sendBroadcast(broadcastIntent);
    }

    private void notifyCallbackCustom(String fileName, String folderName, String error) {
        SaveCallback callback = sCallback;
        if (callback != null) {
            if (error != null) {
                callback.onSaveComplete(null, error);
            } else {
                callback.onSaveComplete(getString(R.string.toast_saved_custom, fileName, folderName), null);
            }
        }
        // Also send broadcast as fallback
        Intent broadcastIntent = new Intent(ACTION_SAVE_COMPLETE);
        if (error != null) {
            broadcastIntent.putExtra(EXTRA_ERROR, error);
        } else {
            broadcastIntent.putExtra(EXTRA_FILE_NAME, fileName);
        }
        sendBroadcast(broadcastIntent);
    }
}
