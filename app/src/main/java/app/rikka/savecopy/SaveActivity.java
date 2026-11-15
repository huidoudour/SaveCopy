package app.rikka.savecopy;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;

/**
 * Invisible activity that forwards a received URI to {@link SaveService} and
 * reports the result. Kept alive until the service answers so the toast and the
 * error dialog have somewhere to live.
 */
public class SaveActivity extends ComponentActivity {

    private static final int REQUEST_STORAGE_PERMISSION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SaveService.setCallback((message, error) -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                showErrorDialog(error);
            } else if (message != null) {
                Toast.makeText(SaveActivity.this, message, Toast.LENGTH_LONG).show();
                finish();
            }
        }));

        String action = getIntent().getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }

        // The app is usually the "always open" choice for its own actions; clear
        // that so the chooser stays reachable for the next request.
        try {
            getPackageManager().clearPackagePreferredActivities(getPackageName());
        } catch (Exception ignored) {
        }

        checkPermission();
    }

    private void checkPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            String[] permissions = {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
            if (checkSelfPermission(permissions[0]) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(permissions[1]) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permissions, REQUEST_STORAGE_PERMISSION);
                return;
            }
        }
        startSave();
    }

    private void startSave() {
        Intent intent = new Intent(this, SaveService.class);
        intent.setAction(getIntent().getAction());
        IntentDataUtils.copyShareData(getIntent(), intent);

        Uri referrer = getReferrer();
        intent.putExtra(SaveService.CALLING_PACKAGE,
                referrer != null ? referrer.getAuthority() : null);

        startService(intent);
        // The service callback closes this activity.
    }

    private void showErrorDialog(String error) {
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
                .setTitle(R.string.notification_error_title)
                .setMessage(error)
                .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                .setPositiveButton(R.string.choose_action_title, (d, which) -> {
                    Intent chooser = new Intent(this, ChoiceActivity.class);
                    chooser.setAction(getIntent().getAction());
                    IntentDataUtils.copyShareData(getIntent(), chooser);
                    startActivity(chooser);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .create();
        dialog.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SaveService.setCallback(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) return;
        if (grantResults.length == 0) return;

        boolean granted = true;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            startSave();
            return;
        }

        boolean isNight = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_YES) > 0;
        int theme = isNight ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new AlertDialog.Builder(this, theme)
                .setTitle(R.string.dialog_no_permission_title)
                .setMessage(R.string.dialog_no_permission_message)
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
