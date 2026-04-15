package dev.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.media.MediaRecorder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ChatActivity — Voice-first AI conversation screen.
 *
 * Flow:
 *  1. Kontinuierliches STT via AudioRecord (Mikrofon) + Parakeet JNI.
 *  2. Wenn Codewort (default "over") erkannt → Text an LLM senden.
 *  3. Antwort via SherpaOnnxTts (Thorsten-Medium, lokal/offline) abspielen.
 *  4. Transkript als Markdown-Datei im konfigurierten Speicherordner ablegen.
 */
public class ChatActivity extends Activity {

    private static final String TAG       = "ChatActivity";
    private static final String AUTHORITY = "dev.transcribe.fileprovider";

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

    public static final String DEFAULT_MODEL     = "Qwen/Qwen3-235B-A22B-Instruct";
    public static final String DEFAULT_ENDWORD   = "dialogende";
    public static final String DEFAULT_SYSPROMPT =
        "Du bist ein pr\u00e4ziser Diskussionspartner. Antworte konzise auf Deutsch. "
        + "Keine unn\u00f6tigen Wiederholungen. Stelle R\u00fcckfragen wenn n\u00f6tig.";

    private static final String DEEPINFRA_CHAT_URL =
        "https://api.deepinfra.com/v1/openai/chat/completions";

    // UI
    private TextView    tvTranscript;
    private TextView    tvStatus;
    private ScrollView  scrollView;
    private ImageButton btnMic;

    // STT via AudioRecord + Parakeet JNI
    private AudioRecord audioRecord;
    private Thread      audioThread;
    private boolean     isListening  = false;
    private boolean     isProcessing = false;

    // TTS — Sherpa-ONNX Thorsten-Medium (offline)
    private SherpaOnnxTts tts;

    // Conversation
    private final StringBuilder    currentBuffer = new StringBuilder();
    private final List<JSONObject> history       = new ArrayList<>();
    private final StringBuilder    markdownLog   = new StringBuilder();
    private String                 sessionName;
    private String                 activeModel;
    private final Handler          mainHandler   = new Handler(Looper.getMainLooper());

    // Audio focus
    private AudioManager      audioManager;
    private AudioFocusRequest audioFocusRequest;

    // Endword
    private String   endword;
    private Runnable endwordTimeoutRunnable;

    // JNI — Parakeet STT
    private native void initNative(ChatActivity activity);
    private native void cleanupNative();
    private native void pushAudio(float[] data, int length);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat_activity);

        tvTranscript = findViewById(R.id.tv_chat_transcript);
        tvStatus     = findViewById(R.id.tv_chat_status);
        scrollView   = findViewById(R.id.scroll_chat);
        btnMic       = findViewById(R.id.btn_chat_mic);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        sessionName = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(new Date());

        SharedPreferences prefs = getSharedPreferences(
            SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        endword     = prefs.getString(KEY_CHAT_ENDWORD, DEFAULT_ENDWORD)
                          .toLowerCase(Locale.getDefault());
        activeModel = prefs.getString(KEY_CHAT_MODEL, DEFAULT_MODEL);

        String sysPrompt = prefs.getString(KEY_CHAT_SYSPROMPT, DEFAULT_SYSPROMPT);
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", sysPrompt);
            history.add(sys);
        } catch (Exception e) { Log.e(TAG, "sys msg", e); }

        markdownLog.append("# Chat-Session ").append(sessionName).append("\n\n");
        markdownLog.append("**Modell:** ").append(activeModel).append("  \n");
        markdownLog.append("**System:** ").append(sysPrompt).append("\n\n---\n\n");

        // Parakeet STT JNI
        initNative(this);

        // Sherpa-ONNX TTS — initialise (download if needed)
        tts = new SherpaOnnxTts(this, msg -> setStatus(msg));
        tts.ensureModelReady(() -> {
            // Model ready — mic button becomes active
            btnMic.setEnabled(true);
            setStatus("Mikrofon-Taste dr\u00fccken zum Starten");
        });

        // Disable mic until TTS model is ready
        btnMic.setEnabled(false);
        setStatus("Sprachmodell wird vorbereitet\u2026");

        findViewById(R.id.btn_chat_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_share).setOnClickListener(v -> shareTranscript());
        findViewById(R.id.btn_chat_settings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        btnMic.setOnClickListener(v -> {
            if (isProcessing) return;
            if (isListening) stopListening();
            else             startListening();
        });
    }

    // -----------------------------------------------------------------------
    // STT via AudioRecord + Parakeet JNI
    // -----------------------------------------------------------------------

    private void startListening() {
        int sampleRate  = 16000;
        int channelConf = AudioFormat.CHANNEL_IN_MONO;
        int audioFmt    = AudioFormat.ENCODING_PCM_16BIT;
        int minBuf      = AudioRecord.getMinBufferSize(sampleRate, channelConf, audioFmt);
        int bufSize     = Math.max(minBuf, 16000);

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConf, audioFmt, bufSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            showError("AudioRecord konnte nicht initialisiert werden.");
            return;
        }

        isListening = true;
        btnMic.setImageResource(R.drawable.ic_stop);
        setStatus("H\u00f6re zu\u2026 (\"" + endword + "\" zum Senden)");
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
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        setStatus("Pausiert");
    }

    // -----------------------------------------------------------------------
    // Callback aus Rust
    // -----------------------------------------------------------------------

    public void onChatSegment(String segment) {
        mainHandler.post(() -> handleSpeechSegment(segment.trim()));
    }

    // -----------------------------------------------------------------------
    // Endword-Detection
    // -----------------------------------------------------------------------

    private void handleSpeechSegment(String segment) {
        if (segment.isEmpty()) return;
        String lower = segment.toLowerCase(Locale.getDefault());

        if (lower.contains(endword)) {
            String cleaned = segment
                .replaceAll("(?i)\\b" + endword + "\\b", "").trim();
            if (!cleaned.isEmpty()) currentBuffer.append(cleaned).append(" ");

            if (endwordTimeoutRunnable != null)
                mainHandler.removeCallbacks(endwordTimeoutRunnable);

            endwordTimeoutRunnable = () -> {
                String userText = currentBuffer.toString().trim();
                currentBuffer.setLength(0);
                if (!userText.isEmpty()) sendToLLM(userText);
            };
            mainHandler.postDelayed(endwordTimeoutRunnable, 800);
        } else {
            currentBuffer.append(segment).append(" ");
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
        appendTranscript("**User:** " + userText);

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
                String apiKey = prefs.getString(KEY_CHAT_API_KEY, "");
                if (apiKey.isEmpty()) {
                    showError("Kein API Key. Bitte in den Einstellungen hinterlegen.");
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
                mainHandler.post(() -> {
                    appendTranscript("**AI** *(" + shortModel(activeModel) + "):* " + aiText);
                    speakText(aiText);
                });

            } catch (Exception e) {
                Log.e(TAG, "LLM error", e);
                showError("Fehler: " + e.getMessage());
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // TTS — Sherpa-ONNX Thorsten (offline)
    // -----------------------------------------------------------------------

    private void speakText(String text) {
        setStatus("Sprachausgabe\u2026");
        requestAudioFocus();
        tts.speak(text, () -> {
            abandonAudioFocus();
            finishProcessing();
        });
    }

    private void finishProcessing() {
        mainHandler.post(() -> {
            isProcessing = false;
            saveTranscript();
            startListening();
        });
    }

    // -----------------------------------------------------------------------
    // Transcript save & share
    // -----------------------------------------------------------------------

    private void saveTranscript() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                String folderUriStr = prefs.getString(
                    SettingsActivity.KEY_SAVE_FOLDER_URI, null);
                File cache = new File(getCacheDir(), "chat_" + sessionName + ".md");
                try (FileWriter fw = new FileWriter(cache)) { fw.write(markdownLog.toString()); }
                if (folderUriStr == null) return;
                android.net.Uri treeUri = android.net.Uri.parse(folderUriStr);
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
            } catch (Exception e) { Log.e(TAG, "saveTranscript", e); }
        }).start();
    }

    private void shareTranscript() {
        saveTranscript();
        mainHandler.postDelayed(() -> {
            try {
                File tmp = new File(getCacheDir(), "chat_" + sessionName + ".md");
                if (!tmp.exists()) {
                    try (FileWriter fw = new FileWriter(tmp)) { fw.write(markdownLog.toString()); }
                }
                android.net.Uri uri = FileProvider.getUriForFile(this, AUTHORITY, tmp);
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
        }, 500);
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private void appendTranscript(String text) {
        tvTranscript.append("\n\n" + text);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }
    private void appendMarkdown(String text) { markdownLog.append(text); }
    private void setStatus(String msg) { mainHandler.post(() -> tvStatus.setText(msg)); }
    private void showError(String msg) {
        mainHandler.post(() -> {
            setStatus("Fehler");
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            isProcessing = false;
        });
    }
    private String shortModel(String model) {
        String[] parts = model.split("/");
        String last = parts[parts.length - 1];
        return last.length() > 20 ? last.substring(0, 20) : last;
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
        conn.setReadTimeout(60_000);
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

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopListening();
        cleanupNative();
        if (tts != null) tts.release();
        abandonAudioFocus();
        if (endwordTimeoutRunnable != null)
            mainHandler.removeCallbacks(endwordTimeoutRunnable);
        saveTranscript();
    }
}
