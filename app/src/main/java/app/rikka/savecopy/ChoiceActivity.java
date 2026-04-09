package app.rikka.savecopy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

public class ChoiceActivity extends Activity {

    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.choice_activity, null);

        TextView btnSaveCopy = dialogView.findViewById(R.id.btn_save_copy);
        TextView btnDownloadCopy = dialogView.findViewById(R.id.btn_download_copy);
        
        // Check if it's an http/https link
        Uri data = getIntent().getData();
        boolean isHttpLink = data != null && ("http".equals(data.getScheme()) || "https".equals(data.getScheme()));
        
        if (isHttpLink) {
            // For HTTP links, only allow download
            btnSaveCopy.setEnabled(false);
            btnSaveCopy.setAlpha(0.5f);
        } else if (data != null && ("content".equals(data.getScheme()) || "file".equals(data.getScheme()))) {
            // For file URIs, both options are available
            // Save copy will save the file itself
            // Download copy will try to extract URL from file content
        }

        btnSaveCopy.setOnClickListener(v -> {
            dismissDialogAndStart(SaveActivity.class);
        });
        
        btnDownloadCopy.setOnClickListener(v -> {
            dismissDialogAndStart(DownloadActivity.class);
        });

        dialog = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
                .setView(dialogView)
                .setOnDismissListener(d -> finish())
                .create();
        dialog.show();
    }

    private void dismissDialogAndStart(Class<? extends Activity> activityClass) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        startChosenActivity(activityClass);
    }

    private void startChosenActivity(Class<? extends Activity> activityClass) {
        Intent intent = new Intent(this, activityClass);
        intent.setAction(getIntent().getAction());

        android.util.Log.d("ChoiceActivity", "startChosenActivity: " + activityClass.getSimpleName());
        android.util.Log.d("ChoiceActivity", "Original action: " + getIntent().getAction());
        android.util.Log.d("ChoiceActivity", "Original data: " + getIntent().getData());
        android.util.Log.d("ChoiceActivity", "Original type: " + getIntent().getType());
        android.util.Log.d("ChoiceActivity", "has EXTRA_STREAM: " + getIntent().hasExtra(Intent.EXTRA_STREAM));

        if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Uri> streamUris = new ArrayList<>();
            // Try to get single Uri first (most common case for ACTION_SEND)
            try {
                Uri singleUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (singleUri != null) {
                    streamUris.add(singleUri);
                    android.util.Log.d("ChoiceActivity", "Got single Uri: " + singleUri);
                } else {
                    android.util.Log.w("ChoiceActivity", "getParcelableExtra returned null");
                }
            } catch (ClassCastException e) {
                android.util.Log.w("ChoiceActivity", "ClassCastException on getParcelableExtra, trying ArrayList", e);
                // If single Uri fails, try ArrayList (for ACTION_SEND_MULTIPLE or some systems)
                try {
                    ArrayList<Uri> arrayList = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (arrayList != null) {
                        streamUris.addAll(arrayList);
                        android.util.Log.d("ChoiceActivity", "Got ArrayList with " + arrayList.size() + " items");
                    } else {
                        android.util.Log.w("ChoiceActivity", "getParcelableArrayListExtra returned null");
                    }
                } catch (ClassCastException e2) {
                    android.util.Log.e("ChoiceActivity", "ClassCastException on getParcelableArrayListExtra", e2);
                    // Ignore, streamUris will be empty
                }
            }
            
            if (!streamUris.isEmpty()) {
                // Use ClipData to avoid migrateExtraStreamToClipData clearing EXTRA_STREAM
                String type = getIntent().getType();
                if (type == null) type = "*/*";
                
                if (streamUris.size() == 1) {
                    // Single URI: use setDataAndType + ClipData
                    intent.setDataAndType(streamUris.get(0), type);
                    ClipData clipData = ClipData.newRawUri(null, streamUris.get(0));
                    intent.setClipData(clipData);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    android.util.Log.d("ChoiceActivity", "Set ClipData for single Uri");
                } else {
                    // Multiple URIs: use ClipData
                    ClipData.Item firstItem = new ClipData.Item(streamUris.get(0));
                    ClipData clipData = new ClipData(null, new String[]{type}, firstItem);
                    for (int i = 1; i < streamUris.size(); i++) {
                        clipData.addItem(new ClipData.Item(streamUris.get(i)));
                    }
                    intent.setClipData(clipData);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    android.util.Log.d("ChoiceActivity", "Set ClipData for " + streamUris.size() + " Uris");
                }
            } else {
                android.util.Log.e("ChoiceActivity", "streamUris is empty after processing!");
            }
        } else {
            // No EXTRA_STREAM, just copy data and type
            intent.setDataAndType(getIntent().getData(), getIntent().getType());
        }
        
        if (getIntent().hasExtra(Intent.EXTRA_TEXT)) {
            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
            android.util.Log.d("ChoiceActivity", "Copied EXTRA_TEXT");
        }

        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}
