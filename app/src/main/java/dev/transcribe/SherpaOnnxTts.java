package dev.transcribe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Offline TTS using Sherpa-ONNX with the Thorsten-Medium Piper VITS model.
 *
 * Model files are downloaded once from Hugging Face into getFilesDir()/tts/thorsten/
 * and reused on subsequent launches.
 *
 * Usage:
 *   SherpaOnnxTts tts = new SherpaOnnxTts(context, statusListener);
 *   tts.ensureModelReady(() -> tts.speak(text, completionCallback));
 */
public class SherpaOnnxTts {

    public interface StatusListener {
        /** Called on main thread with a human-readable status string. */
        void onStatus(String message);
    }

    public interface CompletionCallback {
        void onDone();
    }

    private static final String TAG = "SherpaOnnxTts";

    // Piper VITS Thorsten-Medium files on Hugging Face
    private static final String HF_BASE =
        "https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/medium/";
    private static final String MODEL_FILE = "de_DE-thorsten-medium.onnx";
    private static final String CONFIG_FILE = "de_DE-thorsten-medium.onnx.json";

    private static final String MODEL_DIR = "tts/thorsten";
    private static final String MARKER    = ".ready";

    private final Context        context;
    private final StatusListener statusListener;
    private final Handler        mainHandler = new Handler(Looper.getMainLooper());

    private OfflineTts ttsEngine = null;

    public SherpaOnnxTts(Context context, StatusListener statusListener) {
        this.context        = context.getApplicationContext();
        this.statusListener = statusListener;
    }

    private void postStatus(String message) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onStatus(message));
        }
    }

    /** Returns the directory where model files are stored. */
    private File modelDir() {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    /** True if both model files and marker are present. */
    public boolean isModelReady() {
        File dir = modelDir();
        return new File(dir, MARKER).exists()
            && new File(dir, MODEL_FILE).exists()
            && new File(dir, CONFIG_FILE).exists();
    }

    /**
     * Ensures the model is downloaded and the TTS engine is initialised.
     * Shows status messages to the user during download.
     * Calls onReady on the main thread when done (or on error: shows error status).
     */
    public void ensureModelReady(Runnable onReady) {
        if (isModelReady() && ttsEngine != null) {
            onReady.run();
            return;
        }
        new Thread(() -> {
            try {
                if (!isModelReady()) {
                    postStatus("Sprachmodell wird heruntergeladen (~65 MB) \u2013 bitte warten\u2026");
                    downloadModels();
                    postStatus("Sprachmodell heruntergeladen \u2013 wird geladen\u2026");
                } else {
                    postStatus("Sprachmodell wird geladen\u2026");
                }
                initEngine();
                postStatus("Sprachmodell bereit (Thorsten Medium)");
                mainHandler.post(onReady);
            } catch (Exception e) {
                Log.e(TAG, "ensureModelReady failed", e);
                postStatus("Download fehlgeschlagen \u2013 bitte WLAN pr\u00fcfen");
            }
        }).start();
    }

    /** Download both model files into modelDir(). Writes marker on success. */
    private void downloadModels() throws Exception {
        File dir = modelDir();
        dir.mkdirs();
        // Remove stale marker if present
        new File(dir, MARKER).delete();

        downloadFile(HF_BASE + MODEL_FILE,  new File(dir, MODEL_FILE),  1);
        downloadFile(HF_BASE + CONFIG_FILE, new File(dir, CONFIG_FILE), 2);

        // Write marker
        new File(dir, MARKER).createNewFile();
    }

    private void downloadFile(String urlStr, File dest, int fileIndex) throws Exception {
        Log.i(TAG, "Downloading " + dest.getName() + " from " + urlStr);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.connect();
        int code = conn.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code + " for " + dest.getName());

        long total   = conn.getContentLengthLong();
        long written = 0;
        int  lastPct = -1;

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                written += n;
                if (total > 0) {
                    int pct = (int) (written * 100 / total);
                    if (pct != lastPct && pct % 10 == 0) {
                        lastPct = pct;
                        postStatus("Herunterladen (" + fileIndex + "/2): " + pct + " %\u2026");
                    }
                }
            }
        }
        Log.i(TAG, "Downloaded " + dest.getName() + " (" + written + " bytes)");
    }

    /** Initialise Sherpa-ONNX TTS engine from already-downloaded model files. */
    private void initEngine() throws Exception {
        File dir = modelDir();
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(dir, MODEL_FILE).getAbsolutePath());
        vits.setTokens("");           // Piper VITS uses the JSON config, not a tokens file
        vits.setDataDir("");
        vits.setDictDir("");
        vits.setLexicon("");
        vits.setNoiseScale(0.667f);
        vits.setNoiseScaleW(0.8f);
        vits.setLengthScale(1.0f);

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setVits(vits);
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);
        config.setMaxNumSentences(1);

        ttsEngine = new OfflineTts(null, config);
        Log.i(TAG, "TTS engine initialised");
    }

    /**
     * Synthesise text and play it via AudioTrack.
     * onDone is called on the main thread when playback finishes.
     */
    public void speak(String text, CompletionCallback onDone) {
        if (ttsEngine == null) {
            Log.e(TAG, "speak() called before engine is ready");
            mainHandler.post(onDone::onDone);
            return;
        }
        new Thread(() -> {
            try {
                // speakerId 0 = default for Thorsten-Medium (single speaker model)
                float[] samples   = ttsEngine.generate(text, 0, 1.0f).getSamples();
                int     sampleRate = ttsEngine.sampleRate();
                playPcm(samples, sampleRate);
            } catch (Exception e) {
                Log.e(TAG, "speak error", e);
            } finally {
                mainHandler.post(onDone::onDone);
            }
        }).start();
    }

    /** Play raw f32 PCM samples via AudioTrack. Blocks until playback is complete. */
    private void playPcm(float[] samples, int sampleRate) {
        // Convert f32 to s16
        short[] pcm16 = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (s >  1.0f) s =  1.0f;
            if (s < -1.0f) s = -1.0f;
            pcm16[i] = (short) (s * 32767f);
        }

        int minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT);

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(Math.max(minBuf, pcm16.length * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build();

        track.write(pcm16, 0, pcm16.length);
        track.play();

        // Wait for playback to finish
        int played = 0;
        while (played < pcm16.length) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            played = track.getPlaybackHeadPosition();
        }
        track.stop();
        track.release();
    }

    /** Release the TTS engine (call from onDestroy). */
    public void release() {
        if (ttsEngine != null) {
            ttsEngine.release();
            ttsEngine = null;
        }
    }
}
