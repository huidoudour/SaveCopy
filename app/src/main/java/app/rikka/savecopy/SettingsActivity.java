package app.rikka.savecopy;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import app.rikka.savecopy.databinding.SettingsActivityBinding;

public class SettingsActivity extends Activity {

    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_CODE_SELECT_FOLDER = 1001;

    private SettingsActivityBinding binding;
    private SharedPreferences sharedPreferences;
    private boolean isInitializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate start");
        try {
            binding = SettingsActivityBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
            Log.d(TAG, "layout inflated");

            sharedPreferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE);
            Log.d(TAG, "SharedPreferences initialized");

            // Load saved values
            boolean preferAppFolder = sharedPreferences.getBoolean(Settings.KEY_PREFER_APP_FOLDER, false);
            boolean useCustomFolder = sharedPreferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
            String savedPath = sharedPreferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
            Log.d(TAG, "Loaded settings: preferAppFolder=" + preferAppFolder
                    + ", useCustomFolder=" + useCustomFolder
                    + ", customFolderPath=" + savedPath);

            // Setup prefer app folder switch
            binding.preferAppFolder.setChecked(preferAppFolder);
            binding.preferAppFolder.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isInitializing) return;
                Log.d(TAG, "preferAppFolder toggled: " + isChecked);
                sharedPreferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, isChecked).apply();
                if (isChecked) {
                    Log.d(TAG, "unchecking useCustomFolder due to preferAppFolder");
                    binding.useCustomFolder.setChecked(false);
                }
            });

            // Setup custom folder switch
            binding.useCustomFolder.setChecked(useCustomFolder);
            binding.useCustomFolder.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isInitializing) return;
                Log.d(TAG, "useCustomFolder toggled: " + isChecked);
                sharedPreferences.edit().putBoolean(Settings.KEY_USE_CUSTOM_FOLDER, isChecked).apply();
                updateCustomFolderVisibility(isChecked);
                if (isChecked) {
                    Log.d(TAG, "unchecking preferAppFolder due to useCustomFolder");
                    binding.preferAppFolder.setChecked(false);
                }
            });

            // Mark initialization as complete
            isInitializing = false;
            Log.d(TAG, "initialization complete");

            // Setup folder path container click
            binding.customFolderPathContainer.setOnClickListener(v -> {
                Log.d(TAG, "customFolderPathContainer clicked");
                openFolderPicker();
            });

            // Update UI
            updateFolderPathUI(savedPath);
            updateCustomFolderVisibility(useCustomFolder);
            Log.d(TAG, "onCreate end");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate crashed", t);
            throw t;
        }
    }

    private void updateCustomFolderVisibility(boolean visible) {
        Log.d(TAG, "updateCustomFolderVisibility: " + visible);
        binding.customFolderPathContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateFolderPathUI(String path) {
        Log.d(TAG, "updateFolderPathUI: path=" + path);
        try {
            if (path != null) {
                Uri uri = Uri.parse(path);
                String displayPath = uri.getLastPathSegment();
                Log.d(TAG, "updateFolderPathUI: uri=" + uri + ", lastPathSegment=" + displayPath);
                if (displayPath != null) {
                    displayPath = displayPath.replace("tree:", "").replace("primary:", "Internal Storage/");
                    Log.d(TAG, "updateFolderPathUI: displayPath=" + displayPath);
                    binding.customFolderPath.setText(displayPath);
                } else {
                    binding.customFolderPath.setText(path);
                }
            } else {
                binding.customFolderPath.setText(R.string.settings_custom_folder_not_set);
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateFolderPathUI crashed", t);
            throw t;
        }
    }

    private void openFolderPicker() {
        Log.d(TAG, "openFolderPicker start");
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

            String savedPath = sharedPreferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
            Log.d(TAG, "openFolderPicker: savedPath=" + savedPath);
            if (savedPath != null) {
                try {
                    Uri treeUri = Uri.parse(savedPath);
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri);
                    Log.d(TAG, "openFolderPicker: EXTRA_INITIAL_URI set to " + treeUri);
                } catch (Exception e) {
                    Log.w(TAG, "openFolderPicker: failed to set EXTRA_INITIAL_URI", e);
                }
            }

            Log.d(TAG, "openFolderPicker: launching SAF picker");
            startActivityForResult(intent, REQUEST_CODE_SELECT_FOLDER);
        } catch (Throwable t) {
            Log.e(TAG, "openFolderPicker crashed", t);
            throw t;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode
                + ", resultCode=" + resultCode
                + ", data=" + (data != null ? data.getData() : "null"));
        try {
            if (requestCode == REQUEST_CODE_SELECT_FOLDER && resultCode == RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                Log.d(TAG, "onActivityResult: treeUri=" + treeUri);
                if (treeUri != null) {
                    // Take persistable permission
                    try {
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                        Log.d(TAG, "onActivityResult: persistable permission taken successfully");
                    } catch (SecurityException e) {
                        Log.w(TAG, "onActivityResult: failed to take persistable permission", e);
                    }

                    // Save the URI
                    String uriString = treeUri.toString();
                    Log.d(TAG, "onActivityResult: saving uriString=" + uriString);
                    sharedPreferences.edit()
                            .putString(Settings.KEY_CUSTOM_FOLDER_PATH, uriString)
                            .apply();
                    Log.d(TAG, "onActivityResult: saved to SharedPreferences");

                    // Update UI
                    updateFolderPathUI(uriString);

                    // Show toast
                    String toastMsg = getString(R.string.toast_folder_selected, treeUri.getPath());
                    Log.d(TAG, "onActivityResult: showing toast: " + toastMsg);
                    Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
                } else {
                    Log.w(TAG, "onActivityResult: treeUri is null");
                }
            } else {
                Log.d(TAG, "onActivityResult: not our request or cancelled");
            }
        } catch (Throwable t) {
            Log.e(TAG, "onActivityResult crashed", t);
            throw t;
        }
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, "onBackPressed");
        super.onBackPressed();
    }
}
