package app.rikka.savecopy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
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
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                // Show error and go back to choice activity
                AlertDialog errorDialog = new AlertDialog.Builder(SaveActivity.this, R.style.AppTheme_Dialog_Alert)
                        .setTitle(R.string.notification_error_title)
                        .setMessage(error)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.choose_action_title, null)
                        .create();
                
                errorDialog.setOnShowListener(dialog -> {
                    // Set click listeners after dialog is shown to avoid issues
                    errorDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                        errorDialog.dismiss();
                        finish();
                    });
                    
                    errorDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        // Go back to ChoiceActivity to let user choose again
                        Intent intent = new Intent(SaveActivity.this, ChoiceActivity.class);
                        intent.setAction(getIntent().getAction());
                        
                        // Copy all possible data sources
                        ClipData clipData = getIntent().getClipData();
                        if (clipData != null) {
                            intent.setClipData(clipData);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            android.util.Log.d("SaveActivity", "Copied ClipData to ChoiceActivity with " + clipData.getItemCount() + " items");
                        } else if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
                            // Try to copy EXTRA_STREAM - can be single URI or ArrayList
                            try {
                                Uri singleUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                                if (singleUri != null) {
                                    intent.putExtra(Intent.EXTRA_STREAM, singleUri);
                                    android.util.Log.d("SaveActivity", "Copied single EXTRA_STREAM to ChoiceActivity: " + singleUri);
                                }
                            } catch (ClassCastException e) {
                                try {
                                    ArrayList<Uri> arrayList = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                                    if (arrayList != null) {
                                        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayList);
                                        android.util.Log.d("SaveActivity", "Copied ArrayList EXTRA_STREAM to ChoiceActivity with " + arrayList.size() + " items");
                                    }
                                } catch (ClassCastException e2) {
                                    android.util.Log.e("SaveActivity", "Failed to copy EXTRA_STREAM", e2);
                                }
                            }
                        }
                        
                        // Copy type and data URI
                        Uri data = getIntent().getData();
                        String type = getIntent().getType();
                        if (data != null && type != null) {
                            intent.setDataAndType(data, type);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } else if (data != null) {
                            intent.setData(data);
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } else if (type != null) {
                            intent.setType(type);
                        }
                        if (getIntent().hasExtra(Intent.EXTRA_TEXT)) {
                            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
                        }
                        
                        errorDialog.dismiss();
                        startActivity(intent);
                        finish();
                    });
                });
                
                errorDialog.setOnDismissListener(dialog -> {
                    if (!isFinishing()) {
                        finish();
                    }
                });
                
                errorDialog.show();
            } else if (fileName != null) {
                // fileName is already formatted by SaveService (either toast_saved or toast_saved_custom)
                Toast.makeText(SaveActivity.this, fileName, Toast.LENGTH_LONG).show();
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

        getPackageManager().clearPackagePreferredActivities(getPackageName());
        // Skip confirmation dialog, go directly to permission check
        checkPermission();
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
        
        // Copy ClipData or EXTRA_STREAM if present
        ClipData clipData = getIntent().getClipData();
        if (clipData != null) {
            // Copy ClipData directly
            intent.setClipData(clipData);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.util.Log.d("SaveActivity", "Copied ClipData with " + clipData.getItemCount() + " items");
        } else if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Uri> streamUris = new ArrayList<>();
            // Try to get single Uri first (most common case for ACTION_SEND)
            try {
                Uri singleUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (singleUri != null) {
                    streamUris.add(singleUri);
                }
            } catch (ClassCastException e) {
                // If single Uri fails, try ArrayList (for ACTION_SEND_MULTIPLE or some systems)
                try {
                    ArrayList<Uri> arrayList = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (arrayList != null) {
                        streamUris.addAll(arrayList);
                    }
                } catch (ClassCastException e2) {
                    // Ignore, streamUris will be empty
                }
            }
            if (!streamUris.isEmpty()) {
                // For single URI, use putExtra to avoid migrateExtraStreamToClipData issues
                if (streamUris.size() == 1) {
                    intent.putExtra(Intent.EXTRA_STREAM, streamUris.get(0));
                } else {
                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streamUris);
                }
            }
        }
        
        // Copy data URI and type (use setDataAndType to avoid mutual clearing)
        Uri data = getIntent().getData();
        String type = getIntent().getType();
        if (data != null && type != null) {
            intent.setDataAndType(data, type);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (data != null) {
            intent.setData(data);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (type != null) {
            intent.setType(type);
        }
        
        intent.putExtra(SaveService.CALLING_PACKAGE, callingPackage);
        if (getIntent().hasExtra(Intent.EXTRA_TEXT)) {
            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
        }
        
        startService(intent);
        // Don't finish here - callback from Service will show Toast and finish
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SaveService.setCallback(null);
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
