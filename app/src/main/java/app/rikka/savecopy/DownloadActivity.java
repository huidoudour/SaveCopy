package app.rikka.savecopy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class DownloadActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 2;
    private static final String CHANNEL_ID = "download_channel";

    private String downloadUrl;
    private String suggestedFileName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check for VIEW action with HTTP/HTTPS URL
        String action = getIntent().getAction();
        Uri data = getIntent().getData();

        if (!Intent.ACTION_VIEW.equals(action) || data == null) {
            // Also accept SEND action for text sharing (URLs)
            if (Intent.ACTION_SEND.equals(action)) {
                String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
                if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                    downloadUrl = text.trim();
                    extractFileName(downloadUrl);
                }
            }
            if (downloadUrl == null) {
                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else {
            // ACTION_VIEW with data
            String scheme = data.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            downloadUrl = data.toString();
            extractFileName(downloadUrl);
        }

        // Register callback for download completion
        DownloadService.setCallback((fileName, error) -> runOnUiThread(() -> {
            if (error != null) {
                Toast.makeText(DownloadActivity.this, error, Toast.LENGTH_LONG).show();
            } else if (fileName != null) {
                String message = getString(R.string.toast_saved, fileName);
                Toast.makeText(DownloadActivity.this, message, Toast.LENGTH_LONG).show();
            }
            finish();
        }));

        createNotificationChannel();
        checkPermission();
    }

    private void extractFileName(String url) {
        if (suggestedFileName != null) return;

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path != null && path.contains("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty() && name.contains(".")) {
                    suggestedFileName = name;
                    return;
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }

        // Default filename based on URL
        String host = Uri.parse(url).getHost();
        suggestedFileName = host != null ? host + "-download" : "download";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_progress),
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription(getString(R.string.notification_channel_result));
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(permissions[1]) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, PERMISSION_REQUEST_CODE);
                return;
            }
        }

        startDownload();
    }

    private void startDownload() {
        Intent intent = new Intent(this, DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_DOWNLOAD_URL, downloadUrl);
        intent.putExtra(DownloadService.EXTRA_FILE_NAME, suggestedFileName);

        String callingPackage = null;
        Uri referrer = getReferrer();
        if (referrer != null) callingPackage = referrer.getAuthority();
        intent.putExtra(DownloadService.EXTRA_CALLING_PACKAGE, callingPackage);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        // Don't finish here - callback from Service will show Toast and finish
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length == 0) {
                return;
            }

            boolean granted = true;
            for (int grantResult : grantResults) {
                granted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                startDownload();
            } else {
                boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_YES) > 0;
                int theme = isNight ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
                new AlertDialog.Builder(this, theme)
                        .setTitle(R.string.dialog_no_permission_title)
                        .setMessage(R.string.dialog_no_permission_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.dialog_no_permission_button_app_info, (dialog, which) -> {
                            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            i.addCategory(Intent.CATEGORY_DEFAULT);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        })
                        .setOnDismissListener((dialog -> finish()))
                        .show();
            }
        }
    }
}
