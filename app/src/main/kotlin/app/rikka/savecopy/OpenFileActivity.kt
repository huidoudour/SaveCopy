package app.rikka.savecopy

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Trampoline for the "download finished" notification.
 *
 * <p>Notifications can only start an activity, so the tap lands here first.
 * Doing the install launch from an activity gives three things a bare
 * PendingIntent lacked: the file is verified to still be readable, the launch
 * cannot silently no-op, and every failure ends in an explicit message.</p>
 */
class OpenFileActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = intent
        val uri: Uri? = request?.data
        val fileName: String? = request?.getStringExtra(EXTRA_FILE_NAME)

        if (uri == null) {
            Log.w(TAG, "No file URI supplied")
            toast(R.string.toast_file_unavailable)
            finish()
            return
        }

        if (!isReadable(uri)) {
            Log.w(TAG, "Downloaded file is gone or empty: $uri")
            toast(R.string.toast_file_unavailable)
            finish()
            return
        }

        if (!OpenFileLauncher.open(this, uri, fileName)) {
            val installerFile = FileUtils.isInstallerPackageFile(fileName)
            toast(
                if (installerFile) R.string.toast_no_installer else R.string.toast_no_app_to_open,
            )
        }

        finish()
    }

    private fun isReadable(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            // -1 means the provider cannot report a size; 0 means the download
            // produced an empty file, which is never worth handing to an installer.
            descriptor.statSize != 0L
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Unable to read $uri", e)
        false
    }

    private fun toast(stringRes: Int) {
        Toast.makeText(applicationContext, stringRes, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_OPEN_DOWNLOAD = "app.rikka.savecopy.action.OPEN_DOWNLOAD"
        const val EXTRA_FILE_NAME = "file_name"

        private const val TAG = "OpenFileActivity"
    }
}
