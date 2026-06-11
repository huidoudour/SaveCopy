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
        android.util.Log.d("DownloadActivity", "onCreate - action: " + action + ", data: " + data);

        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            // ACTION_VIEW with data
            String scheme = data.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                // Direct HTTP/HTTPS link
                downloadUrl = data.toString();
                extractFileName(downloadUrl);
                android.util.Log.d("DownloadActivity", "Got HTTP URL from data: " + downloadUrl);
            } else if ("content".equals(scheme) || "file".equals(scheme)) {
                // File URI - try to extract URL from file content
                downloadUrl = extractUrlFromFile(data);
                if (downloadUrl == null) {
                    Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                extractFileName(downloadUrl);
                android.util.Log.d("DownloadActivity", "Extracted URL from file: " + downloadUrl);
            } else {
                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else if (Intent.ACTION_SEND.equals(action)) {
            // Handle SEND action - check EXTRA_TEXT first, then ClipData, then EXTRA_STREAM
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                downloadUrl = text.trim();
                extractFileName(downloadUrl);
                android.util.Log.d("DownloadActivity", "Got HTTP URL from EXTRA_TEXT: " + downloadUrl);
            } else {
                // Try to get URL from ClipData
                android.content.ClipData clipData = getIntent().getClipData();
                if (clipData != null && clipData.getItemCount() > 0) {
                    Uri uri = clipData.getItemAt(0).getUri();
                    if (uri != null) {
                        String scheme = uri.getScheme();
                        android.util.Log.d("DownloadActivity", "ClipData URI scheme: " + scheme + ", uri: " + uri);
                        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                            // Direct HTTP/HTTPS URL in ClipData
                            downloadUrl = uri.toString();
                            extractFileName(downloadUrl);
                            android.util.Log.d("DownloadActivity", "Got HTTP URL from ClipData: " + downloadUrl);
                        } else if ("content".equals(scheme) || "file".equals(scheme)) {
                            // File URI - try to extract URL from file content
                            downloadUrl = extractUrlFromFile(uri);
                            if (downloadUrl == null) {
                                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                                finish();
                                return;
                            }
                            extractFileName(downloadUrl);
                            android.util.Log.d("DownloadActivity", "Extracted URL from ClipData file: " + downloadUrl);
                        }
                    }
                }
            }
            
            // If still no URL from EXTRA_TEXT or ClipData, try EXTRA_STREAM
            if (downloadUrl == null && getIntent().hasExtra(Intent.EXTRA_STREAM)) {
                try {
                    Uri streamUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                    if (streamUri != null) {
                        String scheme = streamUri.getScheme();
                        android.util.Log.d("DownloadActivity", "EXTRA_STREAM URI scheme: " + scheme + ", uri: " + streamUri);
                        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                            // Direct HTTP/HTTPS URL in EXTRA_STREAM
                            downloadUrl = streamUri.toString();
                            extractFileName(downloadUrl);
                            android.util.Log.d("DownloadActivity", "Got HTTP URL from EXTRA_STREAM: " + downloadUrl);
                        } else if ("content".equals(scheme) || "file".equals(scheme)) {
                            // File URI - try to extract URL from file content
                            downloadUrl = extractUrlFromFile(streamUri);
                            if (downloadUrl == null) {
                                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                                finish();
                                return;
                            }
                            extractFileName(downloadUrl);
                            android.util.Log.d("DownloadActivity", "Extracted URL from EXTRA_STREAM file: " + downloadUrl);
                        }
                    }
                } catch (ClassCastException e) {
                    android.util.Log.e("DownloadActivity", "Failed to get EXTRA_STREAM", e);
                }
            }
            
            // If still no URL, show error
            if (downloadUrl == null) {
                android.util.Log.e("DownloadActivity", "No valid URL found!");
                Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else {
            // Unsupported action
            android.util.Log.e("DownloadActivity", "Unsupported action: " + action);
            Toast.makeText(this, R.string.toast_invalid_uri, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // No callback needed - DownloadService will show notifications directly
        // This allows DownloadActivity to finish immediately and run in background

        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                return;
            }
        }
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

    private String extractUrlFromFile(Uri fileUri) {
        try {
            java.io.InputStream inputStream = getContentResolver().openInputStream(fileUri);
            if (inputStream == null) {
                return null;
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8)
            );

            StringBuilder content = new StringBuilder();
            char[] buffer = new char[4096];
            int bytesRead;
            while ((bytesRead = reader.read(buffer)) != -1) {
                content.append(buffer, 0, bytesRead);
                // Limit reading to first 64KB to avoid performance issues
                if (content.length() > 65536) {
                    break;
                }
            }
            reader.close();
            inputStream.close();

            // Try to find URL in the content using regex
            String text = content.toString();
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pattern.matcher(text);
            
            if (matcher.find()) {
                String url = matcher.group();
                // Clean up trailing punctuation that might not be part of URL
                url = url.replaceAll("[.,;!?]+$", "");
                return url;
            }
        } catch (Exception e) {
            android.util.Log.e("DownloadActivity", "Failed to extract URL from file", e);
        }
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_progress),
                        NotificationManager.IMPORTANCE_DEFAULT
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
        
        // Finish immediately - service runs in background with notifications
        android.util.Log.d("DownloadActivity", "Started download service, finishing activity");
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 100) {
            createNotificationChannel();
            checkPermission();
            return;
        }
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
