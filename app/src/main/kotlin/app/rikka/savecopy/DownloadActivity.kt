package app.rikka.savecopy

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.net.URLDecoder

/**
 * Entry point for "download copy". Resolves the link carried by the incoming
 * intent, checks the permissions the foreground service needs and hands the
 * transfer over to [DownloadService]; the activity then finishes so the
 * download continues from the notification.
 */
class DownloadActivity : ComponentActivity() {

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storagePermissionLauncher: ActivityResultLauncher<Array<String>>

    private var downloadUrl: String? = null
    private var suggestedFileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            // The download itself does not need notifications; without them the
            // user only loses progress updates, so a denial is reported instead
            // of silently continuing.
            if (result[Manifest.permission.POST_NOTIFICATIONS] == true) {
                checkLegacyStoragePermission()
            } else {
                showBlockingDialog(
                    R.string.dialog_no_notification_permission_title,
                    R.string.dialog_no_notification_permission_message,
                )
            }
        }

        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            if (result.isEmpty()) return@registerForActivityResult
            val granted =
                result[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
                    result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
            if (granted) {
                startDownload()
            } else {
                showBlockingDialog(
                    R.string.dialog_no_permission_title,
                    R.string.dialog_no_permission_message,
                )
            }
        }

        downloadUrl = resolveDownloadUrl(intent)
        if (downloadUrl == null) {
            Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        suggestedFileName = suggestFileName(downloadUrl!!)
        requestNotificationPermission()
    }

    /** Extracts the http(s) link from VIEW or SEND intents, including text files. */
    private fun resolveDownloadUrl(intent: Intent?): String? {
        if (intent == null) return null

        if (Intent.ACTION_VIEW == intent.action) {
            val data = intent.data ?: return null
            if (IntentDataUtils.isHttpUri(data)) return data.toString()
            if (IntentDataUtils.isLocalUri(data)) {
                return IntentDataUtils.readUrlFromDocument(this, data)
            }
            return null
        }

        if (Intent.ACTION_SEND != intent.action &&
            Intent.ACTION_SEND_MULTIPLE != intent.action
        ) {
            return null
        }

        IntentDataUtils.findHttpUrl(intent)?.let { return it }

        // A shared text/document can hold the link instead of carrying it as data.
        for (uri in IntentDataUtils.streamUris(intent)) {
            if (IntentDataUtils.isLocalUri(uri)) {
                IntentDataUtils.readUrlFromDocument(this, uri)?.let { return it }
            }
        }
        val clipData: ClipData? = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val uri = clipData.getItemAt(i).uri
                if (IntentDataUtils.isLocalUri(uri)) {
                    IntentDataUtils.readUrlFromDocument(this, uri)?.let { return it }
                }
            }
        }
        return null
    }

    private fun suggestFileName(url: String): String {
        try {
            val path = Uri.parse(url).path
            if (path != null) {
                val name = path.substring(path.lastIndexOf('/') + 1)
                if (name.contains(".")) {
                    return URLDecoder.decode(name, "UTF-8")
                }
            }
        } catch (ignored: Exception) {
        }
        val host = Uri.parse(url).host
        return if (host != null) "$host-download" else "download"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            )
            return
        }
        checkLegacyStoragePermission()
    }

    private fun checkLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(permissions[1]) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(permissions)
                return
            }
        }
        startDownload()
    }

    private fun startDownload() {
        val intent = Intent(this, DownloadService::class.java)
        intent.putExtra(DownloadService.EXTRA_DOWNLOAD_URL, downloadUrl)
        intent.putExtra(DownloadService.EXTRA_FILE_NAME, suggestedFileName)
        intent.putExtra(DownloadService.EXTRA_CALLING_PACKAGE, referrer?.authority)

        startForegroundService(intent)
        finish()
    }

    private fun showBlockingDialog(titleRes: Int, messageRes: Int) {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }

        AlertDialog.Builder(this, theme)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.dialog_no_permission_button_app_info) { _, _ ->
                val settings = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
                settings.addCategory(Intent.CATEGORY_DEFAULT)
                startActivity(settings)
            }
            .setOnDismissListener { finish() }
            .show()
    }
}
