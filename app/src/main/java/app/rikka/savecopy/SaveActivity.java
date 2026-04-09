package app.rikka.savecopy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;

public class SaveActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Register callback for save completion (more reliable than broadcast on MIUI)
        SaveService.setCallback((fileName, error) -> runOnUiThread(() -> {
            if (error != null) {
                Toast.makeText(SaveActivity.this, error, Toast.LENGTH_LONG).show();
            } else if (fileName != null) {
                String message = getString(R.string.toast_saved, fileName);
                Toast.makeText(SaveActivity.this, message, Toast.LENGTH_LONG).show();
            }
            finish();
        }));

        String action = getIntent().getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            finish();
            return;
        }

        getPackageManager().clearPackagePreferredActivities(getPackageName());
        checkConfirmation();
    }

    private void checkConfirmation() {
        if (shouldShowConfirmation()) {
            new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
                    .setMessage(R.string.dialog_confirmation_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> checkPermission())
                    .setOnDismissListener(dialog -> finish())
                    .show();
            return;
        }
        checkPermission();
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

        startSave();
    }

    private void startSave() {
        String callingPackage = null;
        Uri referrer = getReferrer();
        if (referrer != null) callingPackage = referrer.getAuthority();

        // Create intent explicitly to ensure data is preserved
        Intent intent = new Intent(this, SaveService.class);
        intent.setAction(getIntent().getAction());
        intent.setDataAndType(getIntent().getData(), getIntent().getType());
        intent.putExtra(SaveService.CALLING_PACKAGE, callingPackage);
        
        // Copy EXTRA_STREAM if present - handle both single Uri and ArrayList
        if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Uri> streamUris = new ArrayList<>();
            // Try to get single Uri first (most common case for ACTION_SEND)
            Uri singleUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (singleUri != null) {
                streamUris.add(singleUri);
            } else {
                // If null, try ArrayList (for ACTION_SEND_MULTIPLE or some systems)
                try {
                    ArrayList<Uri> arrayList = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (arrayList != null) {
                        streamUris.addAll(arrayList);
                    }
                } catch (ClassCastException e) {
                    // Ignore, streamUris will be empty
                }
            }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streamUris);
        }
        if (getIntent().hasExtra(Intent.EXTRA_TEXT)) {
            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
        }
        
        startService(intent);
        // Don't finish here - callback from Service will show Toast and finish
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length == 0) {
                return;
            }

            boolean granted = true;
            for (int grantResult : grantResults) {
                granted &= grantResult == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                startSave();
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

    private boolean shouldShowConfirmation() {
        try {
            Intent intentForTest = new Intent(getIntent());
            intentForTest.setComponent(null);
            intentForTest.setPackage(null);
            return getPackageManager().queryIntentActivities(intentForTest, PackageManager.MATCH_DEFAULT_ONLY).size() <= 1;
        } catch (Throwable e) {
            return true;
        }
    }
}
