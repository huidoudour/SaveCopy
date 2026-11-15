package app.rikka.savecopy;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.util.List;

/**
 * Chooser shown for "open with" / "share" requests. Links can only be
 * downloaded, files can either be copied or downloaded.
 */
public class ChoiceActivity extends ComponentActivity {

    private AlertDialog dialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View content = LayoutInflater.from(this).inflate(R.layout.activity_choice, null);
        TextView saveCopy = content.findViewById(R.id.btn_save_copy);
        TextView downloadCopy = content.findViewById(R.id.btn_download_copy);

        // A plain link has no bytes to copy, so only the download makes sense.
        if (IntentDataUtils.findHttpUrl(getIntent()) != null) {
            saveCopy.setEnabled(false);
            saveCopy.setAlpha(0.5f);
        }

        saveCopy.setOnClickListener(v -> startChosenActivity(SaveActivity.class));
        downloadCopy.setOnClickListener(v -> startChosenActivity(DownloadActivity.class));

        dialog = new AlertDialog.Builder(this, R.style.AppTheme_Dialog_Alert)
                .setView(content)
                .setOnDismissListener(d -> finish())
                .create();
        dialog.show();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                } else {
                    finish();
                }
            }
        });
    }

    private void startChosenActivity(Class<? extends ComponentActivity> activityClass) {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();

        Intent source = getIntent();
        Intent target = new Intent(this, activityClass);
        target.setAction(source.getAction());

        List<Uri> uris = IntentDataUtils.collectUris(source, false);
        if (!uris.isEmpty()) {
            String type = source.getType() != null ? source.getType() : "*/*";
            if (uris.size() == 1) {
                target.setDataAndType(uris.get(0), type);
            }
            target.setClipData(buildClipData(uris, type));
            target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            target.setDataAndType(source.getData(), source.getType());
        }

        if (source.hasExtra(Intent.EXTRA_TEXT)) {
            target.putExtra(Intent.EXTRA_TEXT, source.getStringExtra(Intent.EXTRA_TEXT));
        }

        startActivity(target);
        finish();
    }

    private static ClipData buildClipData(List<Uri> uris, String type) {
        if (uris.size() == 1) {
            return ClipData.newRawUri(null, uris.get(0));
        }
        ClipData clipData = new ClipData(null, new String[]{type}, new ClipData.Item(uris.get(0)));
        for (int i = 1; i < uris.size(); i++) {
            clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        return clipData;
    }
}
