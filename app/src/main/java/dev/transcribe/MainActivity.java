package dev.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TAG              = "MainActivity";
    private static final int    PERM_REQ_CODE    = 101;
    private static final int    PERM_REQ_STORAGE = 102;
    private static final int    REQ_PICK_AUDIO   = 201;
    private static final int    REQ_PICK_FOLDER  = 302;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load dependencies (c++_shared or onnxruntime)", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private TextView statusText;

    // Setup card views
    private View     setupCard;
    private Button   btnMicGrant;
    private TextView txtMicDone;
    private Button   btnImeSetup;
    private TextView txtImeDone;
    private Button   btnFolderSetup;
    private TextView txtFolderDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText     = findViewById(R.id.text_status);
        setupCard      = findViewById(R.id.card_setup);
        btnMicGrant    = findViewById(R.id.btn_mic_grant);
        txtMicDone     = findViewById(R.id.txt_mic_done);
        btnImeSetup    = findViewById(R.id.btn_ime_setup);
        txtImeDone     = findViewById(R.id.txt_ime_done);
        btnFolderSetup = findViewById(R.id.btn_folder_setup);
        txtFolderDone  = findViewById(R.id.txt_folder_done);

        btnMicGrant   .setOnClickListener(v -> checkAndRequestPermissions());
        btnImeSetup   .setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        btnFolderSetup.setOnClickListener(v -> openFolderPickerForSetup());

        ImageButton menuButton = findViewById(R.id.btn_menu);
        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, menuButton);
            popup.getMenuInflater().inflate(R.menu.main_popup, popup.getMenu());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_ime_settings) {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                    return true;
                } else if (id == R.id.action_settings) {
                    startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                } else if (id == R.id.action_close) {
                    finish();
                    return true;
                }
                return false;
            });
            popup.show();
        });

        // Voice Chat (NEW)
        Button chatButton = findViewById(R.id.btn_chat);
        chatButton.setOnClickListener(v ->
                startActivity(new Intent(this, ChatActivity.class)));
        Button chatHistoryButton = findViewById(R.id.btn_chat_history);
        if (chatHistoryButton != null) {
            chatHistoryButton.setOnClickListener(v ->
                startActivity(new Intent(this, ChatHistoryActivity.class)));
        }

        Button dictateButton = findViewById(R.id.btn_dictate);
        dictateButton.setOnClickListener(v ->
                startActivity(new Intent(this, DictateActivity.class)));

        Button recordingsButton = findViewById(R.id.btn_recordings_manager);
        recordingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsManagerActivity.class)));

        Button transcribeFileButton = findViewById(R.id.btn_transcribe_file);
        transcribeFileButton.setOnClickListener(v -> pickAudioFile());

        Button startSubsButton = findViewById(R.id.btn_subs_start);
        startSubsButton.setOnClickListener(v ->
                startActivity(new Intent(this, LiveSubtitleActivity.class)));

        updateSetupCard();
        showOnboardingIfNeeded();
        initNative(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSetupCard();
    }

    private void updateSetupCard() {
        boolean hasMic    = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasIme    = isImeEnabled();
        boolean hasFolder = isFolderConfigured();

        boolean allDone = hasMic && hasIme && hasFolder;
        setupCard.setVisibility(allDone ? View.GONE : View.VISIBLE);

        btnMicGrant .setVisibility(hasMic    ? View.GONE : View.VISIBLE);
        txtMicDone  .setVisibility(hasMic    ? View.VISIBLE : View.GONE);
        btnImeSetup .setVisibility(hasIme    ? View.GONE : View.VISIBLE);
        txtImeDone  .setVisibility(hasIme    ? View.VISIBLE : View.GONE);
        btnFolderSetup.setVisibility(hasFolder ? View.GONE : View.VISIBLE);
        txtFolderDone .setVisibility(hasFolder ? View.VISIBLE : View.GONE);
    }

    private boolean isFolderConfigured() {
        return getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_SAVE_FOLDER_URI, null) != null;
    }

    private void openFolderPickerForSetup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    private void showOnboardingIfNeeded() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && isImeEnabled()
                && isFolderConfigured()) return;

        SharedPreferences prefs = getSharedPreferences("onboarding", MODE_PRIVATE);
        if (prefs.getBoolean("shown", false)) return;
        prefs.edit().putBoolean("shown", true).apply();

        int dp24 = (int)(24 * getResources().getDisplayMetrics().density);
        int dp8  = (int)(8  * getResources().getDisplayMetrics().density);
        int dp12 = (int)(12 * getResources().getDisplayMetrics().density);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp24, dp8, dp24, dp8);

        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(
                "Damit Transkr!pt funktioniert, sind drei Schritte nötig:\n\n" +
                "1. Mikrofon freigeben – damit die App Sprache aufnehmen kann.\n\n" +
                "2. Tastatur einrichten – Transkr!pt als alternative Tastatur " +
                "aktivieren, damit du direkt in jede App diktieren kannst.\n\n" +
                "3. Speicherort wählen – wo Aufnahmen und Transkripte gespeichert " +
                "werden (z.B. Dokumente → Transkript).\n\n" +
                "Alles kannst du direkt auf dieser Seite erledigen.");
        text.setTextSize(14);
        text.setTextColor(getColor(R.color.cl_ink));
        text.setLineSpacing(0, 1.4f);
        root.addView(text);

        android.widget.TextView langLabel = new android.widget.TextView(this);
        langLabel.setText(getString(R.string.settings_language_label));
        langLabel.setTextSize(14);
        langLabel.setTextColor(getColor(R.color.cl_ink));
        android.widget.LinearLayout.LayoutParams labelParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp12;
        langLabel.setLayoutParams(labelParams);
        root.addView(langLabel);

        final String[] codes = getResources().getStringArray(R.array.language_codes);
        final String[] names = getResources().getStringArray(R.array.language_display_names);
        Spinner langSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(adapter);

        String currentLang = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                .getString(SettingsActivity.KEY_LANGUAGE, LanguagePostProcessor.DEFAULT_LANGUAGE);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(currentLang)) { langSpinner.setSelection(i); break; }
        }
        android.widget.LinearLayout.LayoutParams spinnerParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        spinnerParams.topMargin = dp8;
        langSpinner.setLayoutParams(spinnerParams);
        root.addView(langSpinner);

        new android.app.AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("App einrichten")
                .setView(root)
                .setPositiveButton("Weiter", (dialog, which) -> {
                    int pos = langSpinner.getSelectedItemPosition();
                    if (pos >= 0 && pos < codes.length) {
                        getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                                .edit().putString(SettingsActivity.KEY_LANGUAGE, codes[pos]).apply();
                    }
                })
                .show();
    }

    private boolean isImeEnabled() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        for (android.view.inputmethod.InputMethodInfo imi : imm.getEnabledInputMethodList()) {
            if (imi.getPackageName().equals(getPackageName())) return true;
        }
        return false;
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    private void pickAudioFile() {
        String storagePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(storagePermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{storagePermission}, PERM_REQ_STORAGE);
            return;
        }
        launchFilePicker();
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(
                Intent.createChooser(intent, "Audiodatei wählen"), REQ_PICK_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updateSetupCard();
        } else if (requestCode == PERM_REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                launchFilePicker();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            Uri audioUri = data.getData();
            if (audioUri != null) {
                Intent intent = new Intent(this, TranscribeFileActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(audioUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        } else if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(treeUri, flags);
                getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(SettingsActivity.KEY_SAVE_FOLDER_URI, treeUri.toString())
                        .apply();
                updateSetupCard();
            }
        }
    }

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> statusText.setText("Status: " + status));
    }

    private native void initNative(MainActivity activity);
}
