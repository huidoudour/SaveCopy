package app.rikka.savecopy

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Two mutually exclusive destinations: {@code Download/<app>} or a custom SAF
 * folder. Only one can be on at a time, so enabling either disables the other.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var preferences: SharedPreferences

    private var preferAppFolder by mutableStateOf(false)
    private var useCustomFolder by mutableStateOf(false)
    private var customFolderPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderPickerLauncher = registerForActivityResult(
            FolderPickerContract(),
            ::onFolderSelected,
        )

        preferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE)

        var prefer = preferences.getBoolean(Settings.KEY_PREFER_APP_FOLDER, false)
        val custom = preferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false)

        // Older versions could leave both switches on; the custom folder wins.
        if (prefer && custom) {
            prefer = false
            preferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, false).apply()
        }

        preferAppFolder = prefer
        useCustomFolder = custom
        customFolderPath = preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null)

        setContent {
            SettingsScreen(
                preferAppFolder = preferAppFolder,
                useCustomFolder = useCustomFolder,
                customFolderPath = customFolderPath,
                onPreferAppFolderChange = ::onPreferAppFolderChanged,
                onUseCustomFolderChange = ::onUseCustomFolderChanged,
                onPickCustomFolder = ::openFolderPicker,
            )
        }
    }

    private fun onPreferAppFolderChanged(checked: Boolean) {
        preferAppFolder = checked
        preferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, checked).apply()
        if (checked) {
            useCustomFolder = false
            preferences.edit().putBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false).apply()
        }
    }

    private fun onUseCustomFolderChanged(checked: Boolean) {
        useCustomFolder = checked
        preferences.edit().putBoolean(Settings.KEY_USE_CUSTOM_FOLDER, checked).apply()
        if (checked) {
            preferAppFolder = false
            preferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, false).apply()
        }
    }

    private fun openFolderPicker() {
        var initialUri: Uri? = null
        val savedPath = preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null)
        if (savedPath != null) {
            try {
                initialUri = Uri.parse(savedPath)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to restore the last folder", e)
            }
        }
        folderPickerLauncher.launch(initialUri)
    }

    private fun onFolderSelected(treeUri: Uri?) {
        if (treeUri == null) return

        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // The folder still works for this session; only persistence is lost.
            Log.w(TAG, "Unable to persist access to $treeUri", e)
        }

        val uriString = treeUri.toString()
        preferences.edit().putString(Settings.KEY_CUSTOM_FOLDER_PATH, uriString).apply()
        customFolderPath = uriString
        Toast.makeText(
            this,
            getString(R.string.toast_folder_selected, treeUri.path),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * [ActivityResultContracts.OpenDocumentTree] variant that keeps the
     * read/write/persistable grant flags the previous implementation requested.
     */
    private class FolderPickerContract : ActivityResultContracts.OpenDocumentTree() {
        override fun createIntent(context: Context, input: Uri?): Intent =
            super.createIntent(context, input).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
    }

    private companion object {
        private const val TAG = "SettingsActivity"
    }
}
