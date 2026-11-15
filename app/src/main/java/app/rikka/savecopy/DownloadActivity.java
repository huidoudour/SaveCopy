package app.rikka.savecopy;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for "download copy". Resolves the link carried by the incoming
 * intent, checks the permissions the foreground service needs and hands the
 * transfer over to {@link DownloadService}; the activity then finishes so the
 * download continues from the notification.
 */
public class DownloadActivity extends ComponentActivity {

    private ActivityResultLauncher<String[]> notificationPermissionLauncher;
    private ActivityResultLauncher<String[]> storagePermissionLauncher;

    private String downloadUrl;
    private String suggestedFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    // The download itself does not need notifications; without
                    // them the user only loses progress updates, so a denial is
                    // reported instead of silently continuing.
                    if (Boolean.TRUE.equals(result.get(Manifest.permission.POST_NOTIFICATIONS))) {
                        checkLegacyStoragePermission();
                    } else {
                        showBlockingDialog(R.string.dialog_no_notification_permission_title,
                                R.string.dialog_no_notification_permission_message);
                    }
                });

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (result.isEmpty()) return;
                    boolean granted =
                            Boolean.TRUE.equals(result.get(Manifest.permission.READ_EXTERNAL_STORAGE))
                                    && Boolean.TRUE.equals(
                                    result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                    if (granted) {
                        startDownload();
                    } else {
                        showBlockingDialog(R.string.dialog_no_permission_title,
                                R.string.dialog_no_permission_message);
                    }
                });

        downloadUrl = resolveDownloadUrl(getIntent());
        if (downloadUrl == null) {
            Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        suggestedFileName = suggestFileName(downloadUrl);
        requestNotificationPermission();
    }

    /** Extracts the http(s) link from VIEW or SEND intents, including text files. */
    private String resolveDownloadUrl(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (IntentDataUtils.isHttpUri(data)) return data.toString();
            if (IntentDataUtils.isLocalUri(data)) {
                return IntentDataUtils.readUrlFromDocument(this, data);
            }
            return null;
        }

        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return null;
        }

        String sharedLink = IntentDataUtils.findHttpUrl(intent);
        if (sharedLink != null) return sharedLink;

        // A shared text/document can hold the link instead of carrying it as data.
        for (Uri uri : IntentDataUtils.streamUris(intent)) {
            if (IntentDataUtils.isLocalUri(uri)) {
                String fromFile = IntentDataUtils.readUrlFromDocument(this, uri);
                if (fromFile != null) return fromFile;
            }
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (IntentDataUtils.isLocalUri(uri)) {
                    String fromFile = IntentDataUtils.readUrlFromDocument(this, uri);
                    if (fromFile != null) return fromFile;
                }
            }
        }
        return null;
    }

    private String suggestFileName(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.contains(".")) {
                    return URLDecoder.decode(name, StandardCharsets.UTF_8.name());
                }
            }
        } catch (Exception ignored) {
        }
        String host = Uri.parse(url).getHost();
        return host != null ? host + "-download" : "download";
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS});
            return;
        }
        checkLegacyStoragePermission();
    }

    private void checkLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            String[] permissions = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
            if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(permissions[1]) != PackageManager.PERMISSION_GRANTED) {
                storagePermissionLauncher.launch(permissions);
                return;
            }
        }
        startDownload();
    }

    private void startDownload() {
        Intent intent = new Intent(this, DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_DOWNLOAD_URL, downloadUrl);
        intent.putExtra(DownloadService.EXTRA_FILE_NAME, suggestedFileName);

        Uri referrer = getReferrer();
        intent.putExtra(DownloadService.EXTRA_CALLING_PACKAGE,
                referrer != null ? referrer.getAuthority() : null);

        startForegroundService(intent);
        finish();
    }

    private void showBlockingDialog(int titleRes, int messageRes) {
        boolean isNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_YES) > 0;
        int theme = isNight ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;

        new AlertDialog.Builder(this, theme)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.dialog_no_permission_button_app_info, (dialog, which) -> {
                    Intent settings = new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    settings.addCategory(Intent.CATEGORY_DEFAULT);
                    startActivity(settings);
                })
                .setOnDismissListener(dialog -> finish())
                .show();
    }
}
