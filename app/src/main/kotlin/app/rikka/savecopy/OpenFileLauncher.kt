package app.rikka.savecopy

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Opens a finished download.
 *
 * <p>Resolution and launching happen here, and every failure path returns
 * {@code false} so the caller can tell the user why nothing happened.</p>
 */
object OpenFileLauncher {

    private const val TAG = "OpenFileLauncher"

    /** InstallerX style installers that can consume a VIEW intent for an APK. */
    private const val PREFERRED_INSTALLER = "io.github.huidoudour.Installer"

    /**
     * Launches the best available handler for {@code uri}.
     *
     * @return {@code true} when an activity was actually started
     */
    fun open(context: Context?, uri: Uri?, fileName: String?): Boolean {
        if (context == null || uri == null) return false

        val mimeType = FileUtils.getMimeTypeForFileName(fileName)
        val installerFile = FileUtils.isInstallerPackageFile(fileName) ||
            FileUtils.isApkMime(mimeType)

        if (installerFile) {
            val preferred = viewIntent(uri, FileUtils.MIME_APK)
                .setPackage(PREFERRED_INSTALLER)
            if (startBest(context, preferred)) return true

            if (startBest(context, viewIntent(uri, FileUtils.MIME_APK))) return true

            Log.w(TAG, "No package installer can open $fileName")
            return false
        }

        if (startBest(context, viewIntent(uri, mimeType))) return true

        Log.w(TAG, "No activity can open $fileName ($mimeType)")
        return false
    }

    private fun viewIntent(uri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /**
     * Resolves explicitly and skips this app, so an APK download cannot bounce
     * back into our own "save a copy" chooser. The chosen component also gets
     * the URI read grant without relying on implicit grant propagation.
     */
    private fun startBest(context: Context, intent: Intent): Boolean {
        val packageManager = context.packageManager
        var matches = packageManager.queryIntentActivities(
            intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (matches.isEmpty()) {
            matches = packageManager.queryIntentActivities(intent, 0)
        }

        val self = context.packageName
        for (match in matches) {
            val activityInfo = match.activityInfo ?: continue
            val packageName = activityInfo.packageName
            if (self == packageName) continue

            val target = Intent(intent).setComponent(
                ComponentName(packageName, activityInfo.name),
            )
            try {
                context.startActivity(target)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Unable to launch $packageName", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to launch $packageName", e)
            }
        }
        return false
    }
}
