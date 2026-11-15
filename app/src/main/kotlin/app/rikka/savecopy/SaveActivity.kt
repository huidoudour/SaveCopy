package app.rikka.savecopy

import android.Manifest
import android.app.AlertDialog
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

/**
 * Invisible activity that forwards a received URI to [SaveService] and
 * reports the result. Kept alive until the service answers so the toast and the
 * error dialog have somewhere to live.
 */
class SaveActivity : ComponentActivity() {

    private lateinit var storagePermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storagePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            if (result.isEmpty()) return@registerForActivityResult
            val granted =
                result[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
                    result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
            if (granted) {
                startSave()
            } else {
                showPermissionDeniedDialog()
            }
        }

        SaveService.setCallback { message, error ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (error != null) {
                    showErrorDialog(error)
                } else if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        val action = intent.action
        if (Intent.ACTION_VIEW != action &&
            Intent.ACTION_SEND != action &&
            Intent.ACTION_SEND_MULTIPLE != action
        ) {
            finish()
            return
        }

        // The app is usually the "always open" choice for its own actions; clear
        // that so the chooser stays reachable for the next request.
        clearPreferredActivities()

        checkPermission()
    }

    /**
     * Keeps the legacy preferred-activity state clean. The platform deprecated
     * this API in Android 14 without a general-purpose replacement (RoleManager
     * only covers specific roles), and it is still the only way to undo an
     * "always" choice made on older releases.
     */
    @Suppress("DEPRECATION")
    private fun clearPreferredActivities() {
        try {
            packageManager.clearPackagePreferredActivities(packageName)
        } catch (ignored: Exception) {
        }
    }

    private fun checkPermission() {
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
        startSave()
    }

    private fun startSave() {
        val serviceIntent = Intent(this, SaveService::class.java)
        serviceIntent.action = intent.action
        IntentDataUtils.copyShareData(intent, serviceIntent)
        serviceIntent.putExtra(SaveService.CALLING_PACKAGE, referrer?.authority)

        startService(serviceIntent)
        // The service callback closes this activity.
    }

    private fun showErrorDialog(error: String) {
        AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
            .setTitle(R.string.notification_error_title)
            .setMessage(error)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.choose_action_title) { _, _ ->
                val chooser = Intent(this, ChoiceActivity::class.java)
                chooser.action = intent.action
                IntentDataUtils.copyShareData(intent, chooser)
                startActivity(chooser)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        SaveService.setCallback(null)
    }

    private fun showPermissionDeniedDialog() {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = if (night) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        AlertDialog.Builder(this, theme)
            .setTitle(R.string.dialog_no_permission_title)
            .setMessage(R.string.dialog_no_permission_message)
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
