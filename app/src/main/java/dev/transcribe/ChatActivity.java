package dev.transcribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class ChatActivity extends Activity {

    private static final String TAG       = "ChatActivity";
    private static final String AUTHORITY = "dev.transcribe.fileprovider";
    public  static final String INTERNAL_CHAT_DIR = "chats";
    public  static final String EXTRA_CHAT_BASE_NAME = "dev.transcribe.CHAT_BASE_NAME";
    private static final int    REQ_PICK_FILE = 4711;
    private static final int    MAX_ATTACHMENT_BYTES = 200_000;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    public static final String KEY_CHAT_API_KEY   = "chat_api_key";
    public static final String KEY_CHAT_MODEL     = "chat_model";
    public static final String KEY_CHAT_ENDWORD   = "chat_endword";
    public static final String KEY_CHAT_SYSPROMPT = "chat_sysprompt";
    public static final String KEY_CHAT_LANGUAGE  = "chat_language";
    public static final String KEY_TTS_VOICE_ID   = "tts_voice_id";

    public static final String DEFAULT_MODEL        = "Qwen/Qwen3-235B-A22B-Instruct-2507";
    public static final String DEFAULT_TTS_VOICE_ID = "jqy7yrjgagtomro39ddy";

    public static final String[] MODEL_OPTIONS = {
        "Qwen/Qwen3-235B-A22B-Instruct-2507",
        "Qwen/Qwen3-235B-A22B-Thinking-2507",
        "Qwen/Qwen3-235B-A22B",
        "Qwen/Qwen3.5-397B-A17B",
        "Qwen/Qwen3.5-122B-A10B",
        "Qwen/Qwen3.5-35B-A3B",
        "Qwen/Qwen3.5-27B",
        "Qwen/Qwen2.5-72B-Instruct",
        "Qwen/Qwen2-72B-Instruct",
        "Qwen/QwQ-32B",
        "meta-llama/Llama-3.3-70B-Instruct-Turbo",
        "meta-llama/Meta-Llama-3.1-70B-Instruct",
        "meta-llama/Llama-4-Maverick-17B-128E-Instruct-FP8",
        "deepseek-ai/DeepSeek-R1-Distill-Llama-70B",
        "NousResearch/Hermes-3-Llama-3.1-405B",
    };

    public static final String DEFAULT_SYSPROMPT =
        "Du bist ein pr\u00e4ziser Diskussionspartner. Antworte konzise auf Deutsch. "
        + "Keine unn\u00f6tigen Wiederholungen. Stelle R\u00fcckfragen wenn n\u00f6tig.";

    private static final String DEEPINFRA_CHAT_URL =
        "https://api.deepinfra.com/v1/openai/chat/completions";

    static final String[] LANG_CODES  = {"de", "en", "fr", "es", "it", "pt", "nl", "pl", "ru", "ja", "zh"};
    static final String[] LANG_LABELS = {"Deutsch", "English", "Fran\u00e7ais", "Espa\u00f1ol",
        "Italiano", "Portugu\u00eas", "Nederlands", "Polski", "Русский", "日本語", "中文"};

    private static final Map<String, String> DEFAULT_ENDWORDS = new HashMap<>();
    static {
        DEFAULT_ENDWORDS.put("de", "dialogende, dialog ende");
        DEFAULT_ENDWORDS.put("en", "endofspeech, end of speech");
        DEFAULT_ENDWORDS.put("fr", "findialogue, fin dialogue, fin du dialogue");
        DEFAULT_ENDWORDS.put("es", "findialogo, fin dialogo, fin del dialogo");
        DEFAULT_ENDWORDS.put("it", "finedialogo, fine dialogo, fine del dialogo");
        DEFAULT_ENDWORDS.put("pt", "fimdialogo, fim dialogo, fim do dialogo");
        DEFAULT_ENDWORDS.put("nl", "einddialoog, eind dialoog");
        DEFAULT_ENDWORDS.put("pl", "koniecdialogu, koniec dialogu");
        DEFAULT_ENDWORDS.put("ru", "конецдиалога, конец диалога");
        DEFAULT_ENDWORDS.put("ja", "対話終了, たいわしゅうりょう");
        DEFAULT_ENDWORDS.put("zh", "对话结束");
    }

    static String defaultEndwordsForLang(String langCode) {
        String d = DEFAULT_ENDWORDS.get(langCode);
        return d != null ? d : "dialogende";
    }

    private LinearLayout chatMessages;
    private TextView     tvStatus;
    private ScrollView   scrollView;
    private ImageButton  btnMic;
    private TextView     btnLang;
    private TextView     liveBubble;

    private AudioRecord audioRecord;
    private Thread      audioThread;
    private boolean     isListening  = false;
    private boolean     isProcessing = false;

    private DeepInfraTts tts;

    private final StringBuilder    currentBuffer = new StringBuilder();
    private final List<JSONObject> history       = new ArrayList<>();
    private final StringBuilder    markdownLog   = new StringBuilder();
    private String                 sessionName;
    private String                 activeModel;
    private final Handler          mainHandler   = new Handler(Looper.getMainLooper());

    private AudioManager      audioManager;
    private AudioFocusRequest audioFocusRequest;

    private List<String> endwords;
    private Pattern      endwordPattern;
    private Runnable     endwordTimeoutRunnable;

    private String currentLang;

    private final List<String> attachedFiles = new ArrayList<>();

    private native void initNative(ChatActivity activity);
    private native void cleanupNative();
    private native void pushAudio(float[] data, int length);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat_activity);

        chatMessages = findViewById(R.id.chat_messages);
        tvStatus     = findViewById(R.id.tv_chat_status);
        scrollView   = findViewById(R.id.scroll_chat);
        btnMic       = findViewById(R.id.btn_chat_mic);
        btnLang      = findViewById(R.id.btn_chat_lang);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        sessionName = sessionNameFromIntent();

        SharedPreferences prefs = getSharedPreferences(
            SettingsActivity.PREFS_NAME, MODE_PRIVATE);

        currentLang = prefs.getString(KEY_CHAT_LANGUAGE, "de");
        activeModel = prefs.getString(KEY_CHAT_MODEL, DEFAULT_MODEL);
        loadEndwords(prefs);
        updateLangButton();

        String sysPrompt = prefs.getString(KEY_CHAT_SYSPROMPT, DEFAULT_SYSPROMPT);
        boolean resumedChat = loadExistingChat(prefs, sysPrompt);
        if (!resumedChat) {
            startNewChatLog(sysPrompt);
        }

        initNative(this);

        tts = new DeepInfraTts(this, msg -> setStatus(msg));
        btnMic.setEnabled(true);
        setStatus(resumedChat
            ? "Chat geladen. Mikrofon-Taste dr\u00fccken zum Fortsetzen"
            : "Mikrofon-Taste dr\u00fccken zum Starten");

        findViewById(R.id.btn_chat_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_share).setOnClickListener(v -> shareTranscript());
        findViewById(R.id.btn_chat_settings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        View attachBtn = findViewById(R.id.btn_chat_attach);
        if (attachBtn != null) attachBtn.setOnClickListener(v -> openFilePicker());
        btnMic.setOnClickListener(v -> {
            if (isProcessing) return;
            if (isListening) stopListening();
            else             startListening();
        });
        btnLang.setOnClickListener(v -> showLanguagePicker());
    }

    private String sessionNameFromIntent() {
        String baseName = getIntent().getStringExtra(EXTRA_CHAT_BASE_NAME);
        if (baseName != null && !baseName.trim().isEmpty()) {
            String name = baseName.trim();
            if (name.endsWith(".md")) name = name.substring(0, name.length() - 3);
            if (name.startsWith("chat_")) name = name.substring("chat_".length());
            if (!name.isEmpty()) return name;
        }
        return new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(new Date());
    }

    private void startNewChatLog(String sysPrompt) {
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", sysPrompt);
            history.add(sys);
        } catch (Exception e) { Log.e(TAG, "sys msg", e); }

        markdownLog.append("# Chat-Session ").append(sessionName).append("\n\n");
        markdownLog.append("**Modell:** ").append(activeModel).append("  \n");
        markdownLog.append("**System:** ").append(sysPrompt).append("\n\n---\n\n");
    }

    private boolean loadExistingChat(SharedPreferences prefs, String fallbackSysPrompt) {
        File json = internalChatJsonFile();
        if (json.exists() && loadExistingChatJson(json, prefs, fallbackSysPrompt)) {
            setStatus("Chat geladen. Mikrofon-Taste dr\u00fccken zum Fortsetzen");
            return true;
        }
        File md = internalChatFile();
        if (md.exists() && loadExistingChatMarkdown(md, fallbackSysPrompt)) {
            setStatus("Chat geladen. Mikrofon-Taste dr\u00fccken zum Fortsetzen");
            return true;
        }
        return false;
    }

    private boolean loadExistingChatJson(
            File jsonFile,
            SharedPreferences prefs,
            String fallbackSysPrompt) {
        try {
            JSONObject root = new JSONObject(readFile(jsonFile));
            activeModel = root.optString("model", activeModel);
            currentLang = root.optString("language", currentLang);
            loadEndwords(prefs);
            updateLangButton();

            markdownLog.setLength(0);
            String markdown = root.optString("markdown", "");
            if (markdown.isEmpty() && internalChatFile().exists()) {
                markdown = readFile(internalChatFile());
            }
            markdownLog.append(markdown);

            history.clear();
            JSONArray messages = root.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    history.add(messages.getJSONObject(i));
                }
            }
            ensureSystemMessage(fallbackSysPrompt);
            renderHistoryBubbles();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "loadExistingChatJson", e);
            return false;
        }
    }

    private boolean loadExistingChatMarkdown(File mdFile, String fallbackSysPrompt) {
        try {
            String markdown = readFile(mdFile);
            markdownLog.setLength(0);
            markdownLog.append(markdown);
            rebuildHistoryFromMarkdown(markdown, fallbackSysPrompt);
            renderHistoryBubbles();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "loadExistingChatMarkdown", e);
            return false;
        }
    }

    private void ensureSystemMessage(String fallbackSysPrompt) throws Exception {
        for (JSONObject msg : history) {
            if ("system".equals(msg.optString("role"))) return;
        }
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", fallbackSysPrompt);
        history.add(0, sys);
    }

    private void rebuildHistoryFromMarkdown(String markdown, String fallbackSysPrompt) throws Exception {
        history.clear();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", extractMarkdownSystemPrompt(markdown, fallbackSysPrompt));
        history.add(sys);

        String[] turns = markdown.split("\\n\\n---\\n\\n");
        Pattern userPattern = Pattern.compile("(?s)\\*\\*User:\\*\\*\\s*(.*?)(?=\\n\\n\\*\\*AI\\*\\*|\\z)");
        Pattern aiPattern = Pattern.compile("(?s)\\*\\*AI\\*\\*\\s*\\*\\([^\\n]*?\\):\\*\\s*(.*)");
        for (String turn : turns) {
            java.util.regex.Matcher um = userPattern.matcher(turn);
            if (um.find()) addHistoryMessage("user", um.group(1).trim());
            java.util.regex.Matcher am = aiPattern.matcher(turn);
            if (am.find()) addHistoryMessage("assistant", am.group(1).trim());
        }
    }

    private String extractMarkdownSystemPrompt(String markdown, String fallback) {
        for (String line : markdown.split("\\n")) {
            if (line.startsWith("**System:**")) {
                String value = line.substring("**System:**".length()).trim();
                if (!value.isEmpty()) return value;
            }
        }
        return fallback;
    }

    private void addHistoryMessage(String role, String content) throws Exception {
        if (content.isEmpty()) return;
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        history.add(msg);
    }

    private void renderHistoryBubbles() {
        chatMessages.removeAllViews();
        for (JSONObject msg : history) {
            String role = msg.optString("role");
            String content = msg.optString("content");
            if ("user".equals(role)) {
                addBubble(content, true);
            } else if ("assistant".equals(role)) {
                addBubble(content, false);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // -----------------------------------------------------------------------
    // Language & Endwords
    // -----------------------------------------------------------------------

    private void loadEndwords(SharedPreferences prefs) {
        String key = KEY_CHAT_ENDWORD + "_" + currentLang;
        String raw = prefs.getString(key, defaultEndwordsForLang(currentLang));
        parseEndwords(raw);
    }

    private void parseEndwords(String raw) {
        endwords = new ArrayList<>();
        for (String w : raw.split(",")) {
            String trimmed = w.trim().toLowerCase(Locale.getDefault());
            if (!trimmed.isEmpty()) endwords.add(trimmed);
        }
        if (endwords.isEmpty()) endwords.add("dialogende");

        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < endwords.size(); i++) {
            if (i > 0) regex.append("|");
            regex.append(Pattern.quote(endwords.get(i)));
        }
        endwordPattern = Pattern.compile("(?i)(?:" + regex + ")");
    }

    private void updateLangButton() {
        btnLang.setText(currentLang.toUpperCase(Locale.ROOT));
    }

    private void showLanguagePicker() {
        int checked = Arrays.asList(LANG_CODES).indexOf(currentLang);
        new AlertDialog.Builder(this)
            .setTitle("Dialogsprache")
            .setSingleChoiceItems(LANG_LABELS, checked, (dialog, which) -> {
                currentLang = LANG_CODES[which];
                SharedPreferences prefs = getSharedPreferences(
                    SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                prefs.edit().putString(KEY_CHAT_LANGUAGE, currentLang).apply();
                loadEndwords(prefs);
                updateLangButton();
                dialog.dismiss();
                if (isListening) {
                    setStatus("H\u00f6re zu\u2026 (\"" + endwords.get(0) + "\" zum Senden)");
                }
            })
            .show();
    }

    // -----------------------------------------------------------------------
    // File attachment
    // -----------------------------------------------------------------------

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "text/*", "application/json", "application/xml",
            "application/javascript", "application/pdf", "application/x-yaml"
        });
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) readAndAttach(uri);
        }
    }

    private void readAndAttach(Uri uri) {
        new Thread(() -> {
            try {
                String name = queryDisplayName(uri);
                StringBuilder sb = new StringBuilder();
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new Exception("Kein Stream");
                    byte[] buf = new byte[4096];
                    int n; int total = 0;
                    while ((n = is.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_ATTACHMENT_BYTES) {
                            sb.append(new String(buf, 0, Math.max(0, n - (total - MAX_ATTACHMENT_BYTES)),
                                StandardCharsets.UTF_8));
                            sb.append("\n[…Datei gek\u00fcrzt nach ").append(MAX_ATTACHMENT_BYTES)
                              .append(" Byte]");
                            break;
                        }
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                String content = sb.toString();
                String ctxMsg = "[Angeh\u00e4ngte Datei: " + name + "]\n\n" + content;
                try {
                    JSONObject ctx = new JSONObject();
                    ctx.put("role", "system");
                    ctx.put("content", ctxMsg);
                    history.add(ctx);
                } catch (Exception e) { Log.e(TAG, "attach ctx", e); }
                attachedFiles.add(name);
                markdownLog.append("**Kontext-Datei:** ").append(name).append(" (")
                    .append(content.length()).append(" Zeichen)\n\n");
                mainHandler.post(() -> {
                    addAttachmentBubble(name, content.length());
                    Toast.makeText(this, "Datei angeh\u00e4ngt: " + name,
                        Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "readAndAttach", e);
                mainHandler.post(() -> Toast.makeText(this,
                    "Datei kann nicht gelesen werden: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "anhang.txt";
    }

    private void addAttachmentBubble(String name, int chars) {
        TextView bubble = new TextView(this);
        bubble.setText("\uD83D\uDCCE " + name + "  \u00b7  " + chars + " Zeichen");
        bubble.setTextSize(13);
        bubble.setTextColor(getColor(R.color.cl_ink_soft));
        bubble.setBackgroundResource(R.drawable.bg_chat_bubble_ai);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6);
        lp.bottomMargin = dpToPx(2);
        lp.gravity = Gravity.CENTER;
        bubble.setLayoutParams(lp);
        chatMessages.addView(bubble);
        scrollDown();
    }

    // -----------------------------------------------------------------------
    // Chat bubble helpers
    // -----------------------------------------------------------------------

    private TextView addBubble(String text, boolean isUser) {
        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(15);
        bubble.setTextColor(getColor(R.color.cl_ink));
        bubble.setLineSpacing(0, 1.4f);
        bubble.setBackgroundResource(isUser
            ? R.drawable.bg_chat_bubble_user
            : R.drawable.bg_chat_bubble_ai);
        bubble.setTextIsSelectable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6);
        lp.bottomMargin = dpToPx(2);
        int sideMargin = dpToPx(48);
        if (isUser) {
            lp.gravity = Gravity.END;
            lp.leftMargin = sideMargin;
        } else {
            lp.gravity = Gravity.START;
            lp.rightMargin = sideMargin;
        }
        bubble.setLayoutParams(lp);

        chatMessages.addView(bubble);
        scrollDown();
        return bubble;
    }

    private void ensureLiveBubble() {
        if (liveBubble != null) return;
        liveBubble = new TextView(this);
        liveBubble.setTextSize(15);
        liveBubble.setTextColor(getColor(R.color.cl_ink));
        liveBubble.setLineSpacing(0, 1.4f);
        liveBubble.setBackgroundResource(R.drawable.bg_chat_bubble_live);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(6);
        lp.bottomMargin = dpToPx(2);
        lp.gravity = Gravity.END;
        lp.leftMargin = dpToPx(48);
        liveBubble.setLayoutParams(lp);

        chatMessages.addView(liveBubble);
    }

    private void updateLiveBubble(String text) {
        ensureLiveBubble();
        liveBubble.setText(text);
        scrollDown();
    }

    private void finalizeLiveBubble(String finalText) {
        if (liveBubble != null) {
            chatMessages.removeView(liveBubble);
            liveBubble = null;
        }
        if (!finalText.isEmpty()) {
            addBubble(finalText, true);
        }
    }

    private void scrollDown() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // -----------------------------------------------------------------------
    // STT
    // -----------------------------------------------------------------------

    private void startListening() {
        if (isListening) return;
        int sampleRate  = 16000;
        int channelConf = AudioFormat.CHANNEL_IN_MONO;
        int audioFmt    = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf      = AudioRecord.getMinBufferSize(sampleRate, channelConf, audioFmt);
        int bufSize     = Math.max(minBuf, 16000);

        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConf, audioFmt, bufSize);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord init failed", e);
            showError("Mikrofon nicht verf\u00fcgbar: " + e.getMessage());
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            showError("AudioRecord konnte nicht initialisiert werden.");
            audioRecord.release();
            audioRecord = null;
            return;
        }

        isListening = true;
        btnMic.setImageResource(R.drawable.ic_stop);
        setStatus("H\u00f6re zu\u2026 (\"" + endwords.get(0) + "\" zum Senden)");
        audioRecord.startRecording();

        audioThread = new Thread(() -> {
            short[] buf  = new short[1024];
            float[] fbuf = new float[1024];
            while (isListening) {
                int read = audioRecord.read(buf, 0, buf.length);
                if (read > 0) {
                    for (int i = 0; i < read; i++)
                        fbuf[i] = buf[i] / 32768.0f;
                    pushAudio(fbuf, read);
                }
            }
        });
        audioThread.start();
    }

    private void stopListening() {
        isListening = false;
        btnMic.setImageResource(R.drawable.ic_mic);
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        setStatus("Pausiert");
    }

    // -----------------------------------------------------------------------
    // Callback from Rust
    // -----------------------------------------------------------------------

    public void onChatSegment(String segment) {
        mainHandler.post(() -> handleSpeechSegment(segment.trim()));
    }

    private void handleSpeechSegment(String segment) {
        if (segment.isEmpty()) return;
        String lower = segment.toLowerCase(Locale.getDefault());

        boolean endwordFound = false;
        for (String ew : endwords) {
            if (lower.contains(ew)) {
                endwordFound = true;
                break;
            }
        }

        if (endwordFound) {
            String cleaned = endwordPattern.matcher(segment).replaceAll("").trim();
            if (!cleaned.isEmpty()) currentBuffer.append(cleaned).append(" ");

            if (endwordTimeoutRunnable != null)
                mainHandler.removeCallbacks(endwordTimeoutRunnable);

            String bufferSnapshot = currentBuffer.toString().trim();
            updateLiveBubble(bufferSnapshot + " \u2026");

            endwordTimeoutRunnable = () -> {
                String userText = currentBuffer.toString().trim();
                currentBuffer.setLength(0);
                finalizeLiveBubble(userText);
                if (!userText.isEmpty()) sendToLLM(userText);
            };
            mainHandler.postDelayed(endwordTimeoutRunnable, 800);
        } else {
            currentBuffer.append(segment).append(" ");
            String bufferSoFar = currentBuffer.toString().trim();
            updateLiveBubble(bufferSoFar);
            setStatus("\u2026 " + segment);
        }
    }

    // -----------------------------------------------------------------------
    // LLM call
    // -----------------------------------------------------------------------

    private void sendToLLM(String userText) {
        isProcessing = true;
        stopListening();
        setStatus("Denkt nach\u2026");

        appendMarkdown("**User:** " + userText + "\n\n");

        try {
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", userText);
            history.add(msg);
        } catch (Exception e) { Log.e(TAG, "history", e); }

        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                String apiKey = deepInfraApiKey(prefs);
                if (apiKey.isEmpty()) {
                    showError("Kein API Key. Bitte in den Einstellungen hinterlegen.");
                    mainHandler.post(() -> { isProcessing = false; startListening(); });
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("model", activeModel);
                body.put("stream", false);
                JSONArray msgs = new JSONArray();
                for (JSONObject m : history) msgs.put(m);
                body.put("messages", msgs);

                String responseText = httpPost(DEEPINFRA_CHAT_URL, apiKey, body.toString());
                JSONObject resp = new JSONObject(responseText);
                String aiText = resp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content").trim();

                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiText);
                history.add(aiMsg);

                appendMarkdown("**AI** *(" + activeModel + "):* " + aiText + "\n\n---\n\n");
                saveTranscriptInternal();
                mainHandler.post(() -> {
                    addBubble(aiText, false);
                    speakText(aiText);
                });

            } catch (Exception e) {
                Log.e(TAG, "LLM error", e);
                showError("Fehler: " + e.getMessage());
                mainHandler.post(() -> { isProcessing = false; startListening(); });
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // TTS
    // -----------------------------------------------------------------------

    private void speakText(String text) {
        if (tts == null) {
            Log.w(TAG, "speakText: TTS not ready, skipping");
            setStatus("(TTS nicht bereit, nur Text)");
            finishProcessing();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String apiKey = deepInfraApiKey(prefs);
        String voiceId = prefs.getString(KEY_TTS_VOICE_ID, DEFAULT_TTS_VOICE_ID).trim();
        if (apiKey.isEmpty()) {
            setStatus("(kein DeepInfra API Key, nur Text)");
            finishProcessing();
            return;
        }
        if (voiceId.isEmpty()) voiceId = DEFAULT_TTS_VOICE_ID;

        setStatus("Sprachausgabe\u2026");
        requestAudioFocus();
        tts.speak(text, apiKey, voiceId, currentLang, () -> {
            abandonAudioFocus();
            finishProcessing();
        });
    }

    private void finishProcessing() {
        mainHandler.post(() -> {
            isProcessing = false;
            saveTranscriptInternal();
            saveTranscriptSaf();
            mainHandler.postDelayed(this::startListening, 300);
        });
    }

    private String deepInfraApiKey(SharedPreferences prefs) {
        String apiKey = prefs.getString(KEY_CHAT_API_KEY, "").trim();
        if (!apiKey.isEmpty()) return apiKey;
        return BuildConfig.DEEPINFRA_API_KEY.trim();
    }

    // -----------------------------------------------------------------------
    // Transcript persistence
    // -----------------------------------------------------------------------

    private File internalChatFile() {
        File dir = new File(getFilesDir(), INTERNAL_CHAT_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "chat_" + sessionName + ".md");
    }

    private File internalChatJsonFile() {
        File dir = new File(getFilesDir(), INTERNAL_CHAT_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "chat_" + sessionName + ".json");
    }

    private void saveTranscriptInternal() {
        try {
            File out = internalChatFile();
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(markdownLog.toString());
            }
            saveStructuredChatInternal();
        } catch (Exception e) {
            Log.e(TAG, "saveTranscriptInternal", e);
        }
    }

    private void saveStructuredChatInternal() {
        try {
            JSONObject root = new JSONObject();
            root.put("sessionName", sessionName);
            root.put("model", activeModel);
            root.put("language", currentLang);
            root.put("markdown", markdownLog.toString());
            JSONArray messages = new JSONArray();
            for (JSONObject msg : history) messages.put(msg);
            root.put("messages", messages);
            JSONArray files = new JSONArray();
            for (String file : attachedFiles) files.put(file);
            root.put("attachedFiles", files);

            try (FileWriter fw = new FileWriter(internalChatJsonFile())) {
                fw.write(root.toString(2));
            }
        } catch (Exception e) {
            Log.e(TAG, "saveStructuredChatInternal", e);
        }
    }

    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void saveTranscriptSaf() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                String folderUriStr = prefs.getString(
                    SettingsActivity.KEY_SAVE_FOLDER_URI, null);
                if (folderUriStr == null) return;
                Uri treeUri = Uri.parse(folderUriStr);
                DocumentFile dir = DocumentFile.fromTreeUri(this, treeUri);
                if (dir == null || !dir.canWrite()) return;
                String filename = "chat_" + sessionName + ".md";
                DocumentFile existing = dir.findFile(filename);
                DocumentFile docFile = (existing != null) ? existing
                    : dir.createFile("text/markdown", filename);
                if (docFile == null) return;
                try (OutputStream os = getContentResolver()
                        .openOutputStream(docFile.getUri(), "wt")) {
                    if (os != null)
                        os.write(markdownLog.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) { Log.e(TAG, "saveTranscriptSaf", e); }
        }).start();
    }

    private void shareTranscript() {
        saveTranscriptInternal();
        saveTranscriptSaf();
        try {
            File f = internalChatFile();
            Uri uri = FileProvider.getUriForFile(this, AUTHORITY, f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_TEXT, markdownLog.toString());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Transkript teilen"));
        } catch (Exception e) {
            Log.e(TAG, "share", e);
            Toast.makeText(this, "Fehler beim Teilen", Toast.LENGTH_SHORT).show();
        }
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private void appendMarkdown(String text) { markdownLog.append(text); }
    private void setStatus(String msg) { mainHandler.post(() -> tvStatus.setText(msg)); }
    private void showError(String msg) {
        mainHandler.post(() -> {
            setStatus("Fehler");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String httpPost(String urlStr, String apiKey, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(120_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code >= 400)
            throw new Exception("HTTP " + code + ": " + readStream(conn.getErrorStream()));
        return readStream(conn.getInputStream());
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Audio focus
    // -----------------------------------------------------------------------

    private void requestAudioFocus() {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        audioFocusRequest = new AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs).build();
        audioManager.requestAudioFocus(audioFocusRequest);
    }

    private void abandonAudioFocus() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
        cleanupNative();
        if (tts != null) tts.release();
        abandonAudioFocus();
        if (endwordTimeoutRunnable != null)
            mainHandler.removeCallbacks(endwordTimeoutRunnable);
        saveTranscriptInternal();
        saveTranscriptSaf();
    }
}
