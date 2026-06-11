package app.rikka.savecopy;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import app.rikka.savecopy.databinding.SettingsActivityBinding;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SELECT_FOLDER = 1001;

    private SettingsActivityBinding binding;
    private SharedPreferences sharedPreferences;
    private boolean isInitializing = true; // Flag to prevent listener loops during initialization

    private final ActivityResultLauncher<Intent> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null) {
                        // Take persistable permission
                        try {
                            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                        } catch (SecurityException e) {
                            // Ignore if can't take persistable permission
                        }

                        // Save the URI
                        sharedPreferences.edit()
                                .putString(Settings.KEY_CUSTOM_FOLDER_PATH, treeUri.toString())
                                .apply();

                        // Update UI
                        updateFolderPathUI(treeUri.toString());

                        Toast.makeText(this, getString(R.string.toast_folder_selected, treeUri.getPath()), Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = SettingsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sharedPreferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE);

        // Setup prefer app folder switch
        binding.preferAppFolder.setChecked(sharedPreferences.getBoolean(Settings.KEY_PREFER_APP_FOLDER, false));
        binding.preferAppFolder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isInitializing) return; // Skip during initialization
            sharedPreferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, isChecked).apply();
            // Disable custom folder if prefer app folder is enabled
            if (isChecked) {
                binding.useCustomFolder.setChecked(false);
            }
        });

        // Setup custom folder switch
        boolean useCustomFolder = sharedPreferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
        binding.useCustomFolder.setChecked(useCustomFolder);
        binding.useCustomFolder.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isInitializing) return; // Skip during initialization
            sharedPreferences.edit().putBoolean(Settings.KEY_USE_CUSTOM_FOLDER, isChecked).apply();
            updateCustomFolderVisibility(isChecked);
            // Disable prefer app folder if custom folder is enabled
            if (isChecked) {
                binding.preferAppFolder.setChecked(false);
            }
        });

        // Mark initialization as complete
        isInitializing = false;

        // Setup folder path container click
        binding.customFolderPathContainer.setOnClickListener(v -> openFolderPicker());

        // Update UI
        String savedPath = sharedPreferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
        updateFolderPathUI(savedPath);
        updateCustomFolderVisibility(useCustomFolder);
    }

    private void updateCustomFolderVisibility(boolean visible) {
        binding.customFolderPathContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateFolderPathUI(String path) {
        if (path != null) {
            // Try to get a readable path
            Uri uri = Uri.parse(path);
            String displayPath = uri.getLastPathSegment();
            if (displayPath != null) {
                // Remove the tree prefix for display
                displayPath = displayPath.replace("tree:", "").replace("primary:", "Internal Storage/");
                binding.customFolderPath.setText(displayPath);
            } else {
                binding.customFolderPath.setText(path);
            }
        } else {
            binding.customFolderPath.setText(R.string.settings_custom_folder_not_set);
        }
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        // Try to show initial folder
        String savedPath = sharedPreferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
        if (savedPath != null) {
            try {
                Uri treeUri = Uri.parse(savedPath);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri);
            } catch (Exception e) {
                // Ignore, use default
            }
        }

        folderPickerLauncher.launch(intent);
    }
}