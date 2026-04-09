package app.rikka.savecopy;

import android.app.Activity;
import android.app.AlertDialog;
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
            btnSaveCopy.setEnabled(false);
            btnSaveCopy.setAlpha(0.5f);
        }

        btnSaveCopy.setOnClickListener(v -> {
            dismissDialogAndStart(SaveActivity.class);
        });
        
        btnDownloadCopy.setOnClickListener(v -> {
            dismissDialogAndStart(SaveActivity.class);
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
        intent.setDataAndType(getIntent().getData(), getIntent().getType());

        if (getIntent().hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Uri> streamUris = new ArrayList<>();
            try {
                ArrayList<Uri> arrayList = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (arrayList != null) {
                    streamUris.addAll(arrayList);
                }
            } catch (ClassCastException e) {
                Uri singleUri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (singleUri != null) {
                    streamUris.add(singleUri);
                }
            }
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streamUris);
        }
        if (getIntent().hasExtra(Intent.EXTRA_TEXT)) {
            intent.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra(Intent.EXTRA_TEXT));
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