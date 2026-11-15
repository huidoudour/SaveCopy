package app.rikka.savecopy;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import app.rikka.savecopy.databinding.ActivitySettingsBinding;

/**
 * Two mutually exclusive destinations: {@code Download/<app>} or a custom SAF
 * folder. Only one can be on at a time, so enabling either disables the other.
 */
public class SettingsActivity extends ComponentActivity {

    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_SELECT_FOLDER = 1001;

    private ActivitySettingsBinding binding;
    private SharedPreferences preferences;
    private boolean initializing = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        preferences = getSharedPreferences(Settings.FILE_NAME, MODE_PRIVATE);

        boolean preferAppFolder = preferences.getBoolean(Settings.KEY_PREFER_APP_FOLDER, false);
        boolean useCustomFolder = preferences.getBoolean(Settings.KEY_USE_CUSTOM_FOLDER, false);
        String savedPath = preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);

        // Older versions could leave both switches on; the custom folder wins.
        if (preferAppFolder && useCustomFolder) {
            preferAppFolder = false;
            preferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, false).apply();
        }

        binding.preferAppFolder.setChecked(preferAppFolder);
        binding.preferAppFolder.setOnCheckedChangeListener((button, checked) -> {
            if (initializing) return;
            preferences.edit().putBoolean(Settings.KEY_PREFER_APP_FOLDER, checked).apply();
            if (checked) binding.useCustomFolder.setChecked(false);
            updateDestinationState();
        });

        binding.useCustomFolder.setChecked(useCustomFolder);
        binding.useCustomFolder.setOnCheckedChangeListener((button, checked) -> {
            if (initializing) return;
            preferences.edit().putBoolean(Settings.KEY_USE_CUSTOM_FOLDER, checked).apply();
            binding.customFolderPathContainer.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) binding.preferAppFolder.setChecked(false);
            updateDestinationState();
        });

        binding.customFolderPathContainer.setOnClickListener(v -> openFolderPicker());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        initializing = false;
        renderFolderPath(savedPath);
        binding.customFolderPathContainer.setVisibility(
                useCustomFolder ? View.VISIBLE : View.GONE);
        updateDestinationState();
    }

    private void updateDestinationState() {
        boolean preferAppFolder = binding.preferAppFolder.isChecked();
        boolean useCustomFolder = binding.useCustomFolder.isChecked();

        setEnabled(binding.preferAppFolder, binding.preferAppFolderSummary, !useCustomFolder);
        setEnabled(binding.useCustomFolder, binding.useCustomFolderSummary, !preferAppFolder);
    }

    private void setEnabled(View control, View summary, boolean enabled) {
        control.setEnabled(enabled);
        summary.setEnabled(enabled);
        summary.setAlpha(enabled ? 1f : 0.38f);
    }

    private void renderFolderPath(@Nullable String path) {
        if (path == null) {
            binding.customFolderPath.setText(R.string.settings_custom_folder_not_set);
            return;
        }
        Uri uri = Uri.parse(path);
        String segment = uri.getLastPathSegment();
        binding.customFolderPath.setText(segment != null
                ? segment.replace("tree:", "").replace("primary:", "Internal Storage/")
                : path);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        String savedPath = preferences.getString(Settings.KEY_CUSTOM_FOLDER_PATH, null);
        if (savedPath != null) {
            try {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(savedPath));
            } catch (Exception e) {
                Log.w(TAG, "Unable to restore the last folder", e);
            }
        }

        startActivityForResult(intent, REQUEST_SELECT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SELECT_FOLDER || resultCode != RESULT_OK || data == null) return;

        Uri treeUri = data.getData();
        if (treeUri == null) return;

        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            // The folder still works for this session; only persistence is lost.
            Log.w(TAG, "Unable to persist access to " + treeUri, e);
        }

        String uriString = treeUri.toString();
        preferences.edit().putString(Settings.KEY_CUSTOM_FOLDER_PATH, uriString).apply();
        renderFolderPath(uriString);
        Toast.makeText(this, getString(R.string.toast_folder_selected, treeUri.getPath()),
                Toast.LENGTH_SHORT).show();
    }
}
