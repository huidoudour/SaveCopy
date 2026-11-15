package app.rikka.savecopy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

/**
 * Trampoline for the "download finished" notification.
 *
 * <p>Notifications can only start an activity, so the tap lands here first.
 * Doing the install launch from an activity (instead of a {@code PendingIntent}
 * to an external installer) gives three things the old code lacked: the file is
 * verified to still be readable, the launch cannot silently no-op, and every
 * failure ends in an explicit message.</p>
 */
public class OpenFileActivity extends Activity {

    public static final String ACTION_OPEN_DOWNLOAD =
            "app.rikka.savecopy.action.OPEN_DOWNLOAD";
    public static final String EXTRA_FILE_NAME = "file_name";

    private static final String TAG = "OpenFileActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        Uri uri = intent != null ? intent.getData() : null;
        String fileName = intent != null ? intent.getStringExtra(EXTRA_FILE_NAME) : null;

        if (uri == null) {
            Log.w(TAG, "No file URI supplied");
            toast(R.string.toast_file_unavailable);
            finish();
            return;
        }

        if (!isReadable(uri)) {
            Log.w(TAG, "Downloaded file is gone or empty: " + uri);
            toast(R.string.toast_file_unavailable);
            finish();
            return;
        }

        if (!OpenFileLauncher.open(this, uri, fileName)) {
            boolean installerFile = FileUtils.isInstallerPackageFile(fileName);
            toast(installerFile ? R.string.toast_no_installer : R.string.toast_no_app_to_open);
        }

        finish();
    }

    private boolean isReadable(Uri uri) {
        try (ParcelFileDescriptor descriptor =
                     getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) return false;
            // -1 means the provider cannot report a size; 0 means the download
            // produced an empty file, which is never worth handing to an installer.
            return descriptor.getStatSize() != 0;
        } catch (Exception e) {
            Log.w(TAG, "Unable to read " + uri, e);
            return false;
        }
    }

    private void toast(int stringRes) {
        Toast.makeText(getApplicationContext(), stringRes, Toast.LENGTH_LONG).show();
    }
}
