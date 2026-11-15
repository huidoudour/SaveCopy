package app.rikka.savecopy;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * Opens a finished download.
 *
 * <p>This used to be a bare {@code PendingIntent} straight to the installer,
 * which made a failed lookup indistinguishable from "the tap did nothing".
 * Resolution and launching now happen here, and every failure path returns
 * {@code false} so the caller can tell the user why nothing happened.</p>
 */
public final class OpenFileLauncher {

    private static final String TAG = "OpenFileLauncher";

    /** InstallerX style installers that can consume a VIEW intent for an APK. */
    private static final String PREFERRED_INSTALLER = "io.github.huidoudour.Installer";

    private OpenFileLauncher() {
    }

    /**
     * Launches the best available handler for {@code uri}.
     *
     * @return {@code true} when an activity was actually started
     */
    public static boolean open(Context context, Uri uri, String fileName) {
        if (context == null || uri == null) return false;

        String mimeType = FileUtils.getMimeTypeForFileName(fileName);
        boolean installerFile = FileUtils.isInstallerPackageFile(fileName)
                || FileUtils.isApkMime(mimeType);

        if (installerFile) {
            Intent preferred = viewIntent(uri, FileUtils.MIME_APK);
            preferred.setPackage(PREFERRED_INSTALLER);
            if (startBest(context, preferred)) return true;

            Intent anyInstaller = viewIntent(uri, FileUtils.MIME_APK);
            if (startBest(context, anyInstaller)) return true;

            Log.w(TAG, "No package installer can open " + fileName);
            return false;
        }

        Intent view = viewIntent(uri, mimeType);
        if (startBest(context, view)) return true;

        Log.w(TAG, "No activity can open " + fileName + " (" + mimeType + ")");
        return false;
    }

    private static Intent viewIntent(Uri uri, String mimeType) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /**
     * Resolves explicitly and skips this app, so an APK download cannot bounce
     * back into our own "save a copy" chooser. The chosen component also gets
     * the URI read grant without relying on implicit grant propagation.
     */
    private static boolean startBest(Context context, Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> matches = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (matches.isEmpty()) {
            matches = packageManager.queryIntentActivities(intent, 0);
        }

        String self = context.getPackageName();
        for (ResolveInfo match : matches) {
            if (match.activityInfo == null) continue;
            String packageName = match.activityInfo.packageName;
            if (self.equals(packageName)) continue;

            Intent target = new Intent(intent).setComponent(
                    new ComponentName(packageName, match.activityInfo.name));
            try {
                context.startActivity(target);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "Unable to launch " + packageName, e);
            }
        }
        return false;
    }
}
