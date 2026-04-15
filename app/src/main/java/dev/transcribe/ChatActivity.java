package dev.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ChatActivity — Voice-first AI conversation screen.
 *
 * Flow:
 *  1. Kontinuierliches STT via Android SpeechRecognizer.
 *  2. Wenn Codewort (default "over") + Pause erkannt → Text an LLM senden.
 *  3. Antwort via DeepInfra TTS (Kokoro) als MP3 abspielen.
 *  4. Transkript als Markdown-Datei im konfigurierten Speicherordner ablegen.
 *
 * Settings keys (in SettingsActivity.PREFS_NAME):
 *   KEY_CHAT_API_KEY    — DeepInfra API Key
 *   KEY_CHAT_MODEL      — LLM-Modell
 *   KEY_CHAT_TTS_VOICE  — Kokoro-Stimme
 *   KEY_CHAT_ENDWORD    — Codewort (default: over)
 *   KEY_CHAT_SYSPROMPT  — System Prompt
 */
public class ChatActivity extends Activity {

    private static final String TAG = "ChatActivity";
    private static final String AUTHORITY = "dev.transcribe.fileprovider";

    public static final String KEY_CHAT_API_KEY   = "chat_api_key";
    public static final String KEY_CHAT_MODEL     = "chat_model";
    public static final String KEY_CHAT_TTS_VOICE = "chat_tts_voice";
    public static final String KEY_CHAT_ENDWORD   = "chat_endword";
    public static final String KEY_CHAT_SYSPROMPT = "chat_sysprompt";

    public static final String DEFAULT_MODEL     = "meta-llama/Meta-Llama-3.1-70B-Instruct";
    public static final String DEFAULT_TTS_VOICE = "af_sarah";
    public static final String DEFAULT_ENDWORD   = "over";
    public static final String DEFAULT_SYSPROMPT =
        "Du bist ein präziser Diskussionspartner. Antworte konzise auf Deutsch. "
        + "Keine unnötigen Wiederholungen. Stelle Rückfragen wenn nötig.";

    private static final String DEEPINFRA_CHAT_URL =
        "https://api.deepinfra.com/v1/openai/chat/completions";
    private static final String DEEPINFRA_TTS_URL =
        "https://api.deepinfra.com/v1/inference/kokoro";

    // UI
    private TextView    tvTranscript;
    private TextView    tvStatus;
    private ScrollView  scrollView;
    private ImageButton btnMic;

    // State
    private SpeechRecognizer speechRecognizer;
    private boolean          isListening   = false;
    private boolean          isProcessing  = false;
    private StringBuilder    currentBuffer = new StringBuilder();
    private final Handler    mainHandler   = new Handler(Looper.getMainLooper());

    // Conversation
    private final List<JSONObject> history      = new ArrayList<>();
    private final StringBuilder   markdownLog   = new StringBuilder();
    private String                sessionName;
    private String                activeModel;

    // Audio
    private AudioManager       audioManager;
    private AudioFocusRequest  audioFocusRequest;
    private MediaPlayer        mediaPlayer;

    // Endword detection
    private String   endword;
    private Runnable endwordTimeoutRunnable;

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
        endword     = prefs.getString(KEY_CHAT_ENDWORD, DEFAULT_ENDWORD).toLowerCase(Locale.getDefault());
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

        findViewById(R.id.btn_chat_close).setOnClickListener(v -> finish());
        findViewById(R.id.btn_chat_share).setOnClickListener(v -> shareTranscript());
        findViewById(R.id.btn_chat_settings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        btnMic.setOnClickListener(v -> {
            if (isProcessing) return;
            if (isListening) stopListening();
            else             startListening();
        });

        setStatus("Mikrofon-Taste drücken zum Starten");
    }

    // -----------------------------------------------------------------------
    // STT
    // -----------------------------------------------------------------------

    private void startListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p)  { setStatus("Höre zu\u2026 (\"" + endword + "\" zum Senden)"); }
                @Override public void onBeginningOfSpeech()       {}
                @Override public void onRmsChanged(float rms)     {}
                @Override public void onBufferReceived(byte[] b)  {}
                @Override public void onEndOfSpeech()             {}
                @Override public void onEvent(int t, Bundle b)    {}

                @Override
                public void onPartialResults(Bundle partialResults) {
                    List<String> parts = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (parts != null && !parts.isEmpty())
                        setStatus("\u2026" + parts.get(0));
                }

                @Override
                public void onResults(Bundle results) {
                    List<String> matches = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty())
                        handleSpeechSegment(matches.get(0).trim());
                    if (isListening && !isProcessing) restartListening();
                }

                @Override
                public void onError(int error) {
                    Log.w(TAG, "STT error: " + error);
                    if (isListening && !isProcessing)
                        mainHandler.postDelayed(() -> { if (isListening && !isProcessing) restartListening(); }, 300);
                }
            });
        }

        isListening = true;
        btnMic.setImageResource(R.drawable.ic_stop);
        setStatus("Initialisiere\u2026");
        launchSpeechIntent();
    }

    private void launchSpeechIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizer.startListening(intent);
    }

    private void restartListening() {
        if (speechRecognizer != null) speechRecognizer.cancel();
        mainHandler.postDelayed(this::launchSpeechIntent, 150);
    }

    private void stopListening() {
        isListening = false;
        btnMic.setImageResource(R.drawable.ic_mic);
        if (speechRecognizer != null) { speechRecognizer.stopListening(); speechRecognizer.cancel(); }
        setStatus("Pausiert");
    }

    // -----------------------------------------------------------------------
    // Endword detection
    // -----------------------------------------------------------------------

    private void handleSpeechSegment(String segment) {
        if (segment.isEmpty()) return;
        String lower = segment.toLowerCase(Locale.getDefault());

        if (lower.contains(endword)) {
            String cleaned = segment.replaceAll("(?i)\\b" + endword + "\\b", "").trim();
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
                    .getString("content")
                    .trim();

                JSONObject aiMsg = new JSONObject();
                aiMsg.put("role", "assistant");
                aiMsg.put("content", aiText);
                history.add(aiMsg);

                appendMarkdown("**AI** *(" + activeModel + "):* " + aiText + "\n\n---\n\n");
                mainHandler.post(() -> appendTranscript(
                    "**AI** *(" + shortModel(activeModel) + "):* " + aiText));

                speakText(aiText, apiKey);

            } catch (Exception e) {
                Log.e(TAG, "LLM error", e);
                showError("Fehler: " + e.getMessage());
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // TTS — DeepInfra Kokoro
    // -----------------------------------------------------------------------

    private void speakText(String text, String apiKey) {
        mainHandler.post(() -> setStatus("Sprachausgabe\u2026"));
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    SettingsActivity.PREFS_NAME, MODE_PRIVATE);
                String voice = prefs.getString(KEY_CHAT_TTS_VOICE, DEFAULT_TTS_VOICE);

                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("voice", voice);
                body.put("output_format", "mp3");

                URL url = new URL(DEEPINFRA_TTS_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    showError("TTS Fehler " + code + ": " + readStream(conn.getErrorStream()));
                    finishProcessing();
                    return;
                }

                String jsonResp = readStream(conn.getInputStream());
                JSONObject jResp = new JSONObject(jsonResp);
                String audioB64 = jResp.optString("audio", "");

                if (audioB64.isEmpty()) {
                    showError("TTS: Keine Audiodaten erhalten.");
                    finishProcessing();
                    return;
                }

                byte[] audioBytes = android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT);
                File tmpMp3 = File.createTempFile("chat_tts_", ".mp3", getCacheDir());
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpMp3)) {
                    fos.write(audioBytes);
                }

                mainHandler.post(() -> playAudioFile(tmpMp3));

            } catch (Exception e) {
                Log.e(TAG, "TTS error", e);
                showError("TTS Fehler: " + e.getMessage());
                finishProcessing();
            }
        }).start();
    }

    private void playAudioFile(File mp3) {
        requestAudioFocus();
        try {
            if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
            mediaPlayer.setDataSource(mp3.getAbsolutePath());
            mediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                mediaPlayer = null;
                mp3.delete();
                abandonAudioFocus();
                finishProcessing();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                finishProcessing();
                return true;
            });
            mediaPlayer.prepare();
            mediaPlayer.start();
            setStatus("AI spricht\u2026");
        } catch (Exception e) {
            Log.e(TAG, "playAudioFile", e);
            finishProcessing();
        }
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

                // Always keep a cache copy for sharing
                File cache = new File(getCacheDir(), "chat_" + sessionName + ".md");
                try (FileWriter fw = new FileWriter(cache)) {
                    fw.write(markdownLog.toString());
                }

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
        mainHandler.post(() -> {
            tvTranscript.append("\n\n" + text);
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
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
    // HTTP helper
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
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
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
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build();
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
        if (isListening && speechRecognizer != null) speechRecognizer.cancel();
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        abandonAudioFocus();
        if (endwordTimeoutRunnable != null) mainHandler.removeCallbacks(endwordTimeoutRunnable);
        saveTranscript();
    }
}
