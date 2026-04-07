package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen.
 *
 * Currently exposed settings:
 *  - Save folder for recordings and transcripts
 *    Default: app-internal files/recordings/transkript
 *    Override: any folder chosen via ACTION_OPEN_DOCUMENT_TREE
 */
public class SettingsActivity extends Activity {

    public static final String PREFS_NAME = "transkript_settings";
    public static final String KEY_SAVE_FOLDER_URI = "save_folder_uri";

    private static final int REQ_PICK_FOLDER = 301;

    private TextView folderPathText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        folderPathText = findViewById(R.id.txt_folder_path);
        Button chooseFolderButton = findViewById(R.id.btn_choose_folder);
        Button resetFolderButton = findViewById(R.id.btn_reset_folder);
        Button closeButton = findViewById(R.id.btn_settings_close);

        refreshFolderDisplay();

        chooseFolderButton.setOnClickListener(v -> openFolderPicker());
        resetFolderButton.setOnClickListener(v -> resetFolder());
        closeButton.setOnClickListener(v -> finish());
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;

            // Persist the permission across reboots
            int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

            // Save the URI string in prefs
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(KEY_SAVE_FOLDER_URI, treeUri.toString()).apply();

            refreshFolderDisplay();
            Toast.makeText(this, getString(R.string.settings_folder_saved), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetFolder() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SAVE_FOLDER_URI, null);
        if (uriString != null) {
            try {
                Uri uri = Uri.parse(uriString);
                getContentResolver().releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        prefs.edit().remove(KEY_SAVE_FOLDER_URI).apply();
        refreshFolderDisplay();
        Toast.makeText(this, getString(R.string.settings_folder_reset), Toast.LENGTH_SHORT).show();
    }

    private void refreshFolderDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SAVE_FOLDER_URI, null);
        if (uriString != null) {
            // Show a human-readable path segment if possible
            try {
                Uri uri = Uri.parse(uriString);
                String path = uri.getLastPathSegment();
                folderPathText.setText(path != null ? path : uriString);
            } catch (Exception e) {
                folderPathText.setText(uriString);
            }
        } else {
            folderPathText.setText(getString(R.string.settings_folder_default));
        }
    }
}
