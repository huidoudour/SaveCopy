package app.rikka.savecopy;

import android.annotation.TargetApi;
import android.app.IntentService;
import android.app.PendingIntent;
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
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

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

    private static final String TAG = "SaveService";

    private Handler mHandler;

    public SaveService() {
        super("save-thread");
    }

    public SaveService(String name) {
        super("save-thread");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
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

        List<Uri> list = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action)) {
            list.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            list.add(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> extras = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (extras != null) {
                list.addAll(extras);
            }
        }

        for (Uri data: list) {
            try {
                doSave(data, type, id, callingPackage);
            } catch (Throwable e) {
                Log.e(TAG, "save " + data, e);

                Throwable cause = e.getCause() == null ? e : e.getCause();
                CharSequence errorMessage = getString(R.string.notification_error_text) + "\n\n" + cause.getMessage();

                // Show error Toast instead of notification
                mHandler.post(() -> {
                    Toast.makeText(SaveService.this, errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    private void doSave(Uri data, String type, int[] _id, String callingPackage) throws IOException, SaveException {
        Context context = this;
        if (data == null) {
            throw new SaveException("data is null");
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

        String download = Environment.DIRECTORY_DOWNLOADS;
        if (getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE).getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)) {
            String label = null;
            if (callingPackage != null) label = loadLabelForPackage(callingPackage);

            download += (label != null ? File.separator + label : "");
        }

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

        InputStream is = cr.openInputStream(data);
        if (is == null) {
            throw new SaveException("can't open data");
        }

        ContentValues values;
        values = new ContentValues();

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
        Uri fileUri = cr.insert(tableUri, values);
        if (fileUri == null) {
            throw new SaveException("can't insert");
        }
        int id = fileUri.toString().hashCode();
        _id[0] = id;

        // No progress notification, only Toast at the end
        OutputStream os = cr.openOutputStream(fileUri, "w");
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
        
        // Create a final copy for lambda expression
        final String finalNewName = newName;
        
        // Show Toast on success (must be on UI thread)
        // No notification bar notifications, only Toast
        mHandler.post(() -> {
            String toastMessage = getString(R.string.toast_saved, finalNewName);
            Toast.makeText(SaveService.this, toastMessage, Toast.LENGTH_LONG).show();
        });
        
        // Stop the service after completion
        stopSelf();
    }
}
