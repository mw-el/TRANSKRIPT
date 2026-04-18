package dev.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * Settings screen.
 *
 * Exposed settings:
 *  - Save folder for recordings and transcripts (SAF folder picker)
 *  - Default e-mail address for direct mail sharing
 *  - Voice Chat settings (API key, model, endword, system prompt)
 *  - DeepInfra TTS voice ID
 */
public class SettingsActivity extends Activity {

    public static final String PREFS_NAME          = "transkript_settings";
    public static final String KEY_SAVE_FOLDER_URI = "save_folder_uri";
    public static final String KEY_EMAIL_ADDRESS   = "default_email";
    public static final String KEY_LANGUAGE        = "target_language";

    private static final int    REQ_PICK_FOLDER        = 301;
    private static final String TAG                    = "SettingsActivity";
    private static final String FLAG_AUTO_RECORD       = "auto_record";
    private static final String FLAG_SELECT_TRANSCRIPT = "select_transcription";
    private static final String FLAG_PAUSE_AUDIO       = "pause_audio";

    private TextView folderPathText;
    private EditText emailInput;
    private Spinner  languageSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        folderPathText  = findViewById(R.id.txt_folder_path);
        emailInput      = findViewById(R.id.edit_email);
        languageSpinner = findViewById(R.id.spinner_language);

        Button chooseFolderButton = findViewById(R.id.btn_choose_folder);
        Button resetFolderButton  = findViewById(R.id.btn_reset_folder);
        Button saveEmailButton    = findViewById(R.id.btn_save_email);
        android.widget.ImageButton closeButton = findViewById(R.id.btn_settings_close);

        refreshFolderDisplay();
        loadEmailSetting();
        bindFlagSwitch(R.id.switch_auto_record,          FLAG_AUTO_RECORD);
        bindFlagSwitch(R.id.switch_select_transcription, FLAG_SELECT_TRANSCRIPT);
        bindFlagSwitch(R.id.switch_pause_audio,          FLAG_PAUSE_AUDIO);
        bindLanguageSpinner();
        bindChatSettings();
        bindTtsStatus();

        chooseFolderButton.setOnClickListener(v -> openFolderPicker());
        resetFolderButton .setOnClickListener(v -> resetFolder());
        saveEmailButton   .setOnClickListener(v -> saveEmail());
        closeButton       .setOnClickListener(v -> finish());
    }

    // ------------------------------------------------------------------
    // Folder picker
    // ------------------------------------------------------------------

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
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(treeUri, flags);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_SAVE_FOLDER_URI, treeUri.toString()).apply();
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
                getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
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
            try {
                String path = Uri.parse(uriString).getLastPathSegment();
                folderPathText.setText(path != null ? path : uriString);
            } catch (Exception e) {
                folderPathText.setText(uriString);
            }
        } else {
            folderPathText.setText(getString(R.string.settings_folder_default));
        }
    }

    // ------------------------------------------------------------------
    // E-mail setting
    // ------------------------------------------------------------------

    private void loadEmailSetting() {
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_EMAIL_ADDRESS, "");
        emailInput.setText(saved);
    }

    private void saveEmail() {
        String email = emailInput.getText().toString().trim();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_EMAIL_ADDRESS, email).apply();
        Toast.makeText(this,
                email.isEmpty()
                        ? getString(R.string.settings_email_cleared)
                        : getString(R.string.settings_email_saved),
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Feature flag switches
    // ------------------------------------------------------------------

    private void bindFlagSwitch(int switchId, String flagName) {
        Switch sw = findViewById(switchId);
        if (sw == null) return;
        File flag = new File(getFilesDir(), flagName);
        sw.setChecked(flag.exists());
        sw.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                try { flag.createNewFile(); } catch (IOException e) { Log.e(TAG, "flag create", e); }
            } else {
                flag.delete();
            }
        });
    }

    // ------------------------------------------------------------------
    // Language selection
    // ------------------------------------------------------------------

    private void bindLanguageSpinner() {
        String[] codes = getResources().getStringArray(R.array.language_codes);
        String[] names = getResources().getStringArray(R.array.language_display_names);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LanguagePostProcessor.DEFAULT_LANGUAGE);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(saved)) { languageSpinner.setSelection(i); break; }
        }

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putString(KEY_LANGUAGE, codes[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ------------------------------------------------------------------
    // Voice Chat settings (without TTS voice spinner — Thorsten is fixed)
    // ------------------------------------------------------------------

    private void bindChatSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        EditText apiKeyInput    = findViewById(R.id.edit_chat_api_key);
        EditText modelInput     = findViewById(R.id.edit_chat_model);
        Spinner  modelSpinner   = findViewById(R.id.spinner_chat_model);
        EditText ttsVoiceInput  = findViewById(R.id.edit_chat_tts_voice_id);
        EditText endwordInput   = findViewById(R.id.edit_chat_endword);
        EditText syspromptInput = findViewById(R.id.edit_chat_sysprompt);
        Spinner  langSpinner    = findViewById(R.id.spinner_chat_endword_lang);
        Button   saveBtn        = findViewById(R.id.btn_save_chat_settings);

        if (apiKeyInput == null || saveBtn == null) return;

        apiKeyInput.setText(prefs.getString(ChatActivity.KEY_CHAT_API_KEY, ""));
        String currentModel = prefs.getString(ChatActivity.KEY_CHAT_MODEL, ChatActivity.DEFAULT_MODEL);
        modelInput.setText(currentModel);
        if (ttsVoiceInput != null) {
            ttsVoiceInput.setText(prefs.getString(
                ChatActivity.KEY_TTS_VOICE_ID,
                ChatActivity.DEFAULT_TTS_VOICE_ID));
        }
        syspromptInput.setText(prefs.getString(ChatActivity.KEY_CHAT_SYSPROMPT, ChatActivity.DEFAULT_SYSPROMPT));

        String[] modelEntries = new String[ChatActivity.MODEL_OPTIONS.length + 1];
        System.arraycopy(ChatActivity.MODEL_OPTIONS, 0, modelEntries, 0, ChatActivity.MODEL_OPTIONS.length);
        modelEntries[modelEntries.length - 1] = "Custom…";
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, modelEntries);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
        int modelIdx = java.util.Arrays.asList(ChatActivity.MODEL_OPTIONS).indexOf(currentModel);
        modelSpinner.setSelection(modelIdx >= 0 ? modelIdx : modelEntries.length - 1);
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (pos < ChatActivity.MODEL_OPTIONS.length) {
                    modelInput.setText(ChatActivity.MODEL_OPTIONS[pos]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, ChatActivity.LANG_LABELS);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langAdapter);

        String currentLang = prefs.getString(ChatActivity.KEY_CHAT_LANGUAGE, "de");
        int currentIdx = java.util.Arrays.asList(ChatActivity.LANG_CODES).indexOf(currentLang);
        if (currentIdx < 0) currentIdx = 0;
        langSpinner.setSelection(currentIdx);

        final String[] shownLang = { ChatActivity.LANG_CODES[currentIdx] };
        endwordInput.setText(prefs.getString(
            ChatActivity.KEY_CHAT_ENDWORD + "_" + shownLang[0],
            ChatActivity.defaultEndwordsForLang(shownLang[0])));

        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                String currentEndword = endwordInput.getText().toString().trim().toLowerCase();
                String storedForPrev  = prefs.getString(
                    ChatActivity.KEY_CHAT_ENDWORD + "_" + shownLang[0],
                    ChatActivity.defaultEndwordsForLang(shownLang[0]));
                if (!currentEndword.equals(storedForPrev.trim().toLowerCase())) {
                    prefs.edit().putString(
                        ChatActivity.KEY_CHAT_ENDWORD + "_" + shownLang[0],
                        currentEndword.isEmpty()
                            ? ChatActivity.defaultEndwordsForLang(shownLang[0])
                            : currentEndword).apply();
                }
                shownLang[0] = ChatActivity.LANG_CODES[pos];
                endwordInput.setText(prefs.getString(
                    ChatActivity.KEY_CHAT_ENDWORD + "_" + shownLang[0],
                    ChatActivity.defaultEndwordsForLang(shownLang[0])));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        saveBtn.setOnClickListener(v -> {
            String endword = endwordInput.getText().toString().trim().toLowerCase();
            prefs.edit()
                .putString(ChatActivity.KEY_CHAT_API_KEY,
                    apiKeyInput.getText().toString().trim())
                .putString(ChatActivity.KEY_CHAT_MODEL,
                    modelInput.getText().toString().trim())
                .putString(ChatActivity.KEY_TTS_VOICE_ID,
                    ttsVoiceInput == null
                        ? ChatActivity.DEFAULT_TTS_VOICE_ID
                        : ttsVoiceInput.getText().toString().trim())
                .putString(ChatActivity.KEY_CHAT_ENDWORD + "_" + shownLang[0],
                    endword.isEmpty() ? ChatActivity.defaultEndwordsForLang(shownLang[0]) : endword)
                .putString(ChatActivity.KEY_CHAT_SYSPROMPT,
                    syspromptInput.getText().toString().trim())
                .apply();
            Toast.makeText(this, "Chat-Einstellungen gespeichert", Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------
    // TTS status
    // ------------------------------------------------------------------

    private void bindTtsStatus() {
        TextView ttsStatusText = findViewById(R.id.tv_tts_status);
        Button   ttsDownloadBtn = findViewById(R.id.btn_tts_download);
        if (ttsStatusText == null) return;

        ttsStatusText.setText("DeepInfra Chatterbox Multilingual \u2014 nutzt voice_id und language gem\u00e4\u00df Anleitung");
        if (ttsDownloadBtn != null) {
            ttsDownloadBtn.setVisibility(android.view.View.GONE);
        }
    }
}
