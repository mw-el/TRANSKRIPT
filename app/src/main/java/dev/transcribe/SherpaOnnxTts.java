package dev.transcribe;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
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
import java.io.IOException;

public class SherpaOnnxTts {

    public interface StatusListener {
        void onStatus(String message);
    }

    public interface CompletionCallback {
        void onDone();
    }

    private static final String TAG = "SherpaOnnxTts";

    private static final String MODEL_FILE   = "de_DE-thorsten-medium.onnx";
    private static final String CONFIG_FILE  = "de_DE-thorsten-medium.onnx.json";
    private static final String TOKENS_FILE  = "tokens.txt";
    private static final String ESPEAK_DIR   = "espeak-ng-data";
    private static final String ASSET_DIR    = "tts-thorsten";
    private static final String MODEL_DIR    = "tts/thorsten";
    private static final String MARKER       = ".ready";

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

    private File modelDir() {
        return new File(context.getFilesDir(), MODEL_DIR);
    }

    public boolean isModelReady() {
        File dir = modelDir();
        return new File(dir, MARKER).exists()
            && new File(dir, MODEL_FILE).exists()
            && new File(dir, CONFIG_FILE).exists()
            && new File(dir, TOKENS_FILE).exists()
            && new File(dir, ESPEAK_DIR).isDirectory();
    }

    public void ensureModelReady(Runnable onReady) {
        if (isModelReady() && ttsEngine != null) {
            onReady.run();
            return;
        }
        new Thread(() -> {
            try {
                if (!isModelReady()) {
                    postStatus("Sprachmodell wird vorbereitet …");
                    extractFromAssets();
                }
                postStatus("Sprachmodell wird geladen …");
                initEngine();
                postStatus("Sprachmodell bereit (Thorsten Medium)");
                mainHandler.post(onReady);
            } catch (Exception e) {
                Log.e(TAG, "ensureModelReady failed", e);
                postStatus("Sprachmodell konnte nicht geladen werden");
            }
        }).start();
    }

    private void extractFromAssets() throws IOException {
        File dir = modelDir();
        dir.mkdirs();
        new File(dir, MARKER).delete();

        AssetManager assets = context.getAssets();

        postStatus("Sprachmodell wird entpackt …");
        copyAsset(assets, ASSET_DIR + "/" + MODEL_FILE,  new File(dir, MODEL_FILE));
        copyAsset(assets, ASSET_DIR + "/" + CONFIG_FILE, new File(dir, CONFIG_FILE));
        copyAsset(assets, ASSET_DIR + "/" + TOKENS_FILE, new File(dir, TOKENS_FILE));

        postStatus("Phonem-Daten werden entpackt …");
        copyAssetDir(assets, ASSET_DIR + "/" + ESPEAK_DIR, new File(dir, ESPEAK_DIR));

        new File(dir, MARKER).createNewFile();
        Log.i(TAG, "Model extracted from assets");
    }

    private void copyAsset(AssetManager assets, String assetPath, File dest) throws IOException {
        try (InputStream in = assets.open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private void copyAssetDir(AssetManager assets, String assetPath, File destDir) throws IOException {
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            try (InputStream in = assets.open(assetPath);
                 FileOutputStream out = new FileOutputStream(destDir)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return;
        }
        destDir.mkdirs();
        for (String entry : entries) {
            copyAssetDir(assets, assetPath + "/" + entry, new File(destDir, entry));
        }
    }

    private void initEngine() throws Exception {
        File dir = modelDir();
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(dir, MODEL_FILE).getAbsolutePath());
        vits.setTokens(new File(dir, TOKENS_FILE).getAbsolutePath());
        vits.setDataDir(new File(dir, ESPEAK_DIR).getAbsolutePath());
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

    public void speak(String text, CompletionCallback onDone) {
        if (ttsEngine == null) {
            Log.e(TAG, "speak() called before engine is ready");
            mainHandler.post(onDone::onDone);
            return;
        }
        Log.i(TAG, "speak: text length=" + text.length());
        new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                float[] samples    = ttsEngine.generate(text, 0, 1.0f).getSamples();
                int     sampleRate = ttsEngine.sampleRate();
                Log.i(TAG, "speak: generate took " + (System.currentTimeMillis() - t0)
                    + "ms, samples=" + samples.length + " rate=" + sampleRate);
                playPcm(samples, sampleRate);
            } catch (Exception e) {
                Log.e(TAG, "speak error", e);
            } finally {
                mainHandler.post(onDone::onDone);
            }
        }).start();
    }

    private void playPcm(float[] samples, int sampleRate) {
        Log.i(TAG, "playPcm: samples=" + samples.length + " sampleRate=" + sampleRate);
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

        int bufSize = Math.max(minBuf * 4, 16384);

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
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialised (state=" + track.getState() + ")");
            track.release();
            return;
        }

        track.play();

        int offset = 0;
        while (offset < pcm16.length) {
            int chunk = Math.min(pcm16.length - offset, 4096);
            int written = track.write(pcm16, offset, chunk);
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write error: " + written);
                break;
            }
            offset += written;
        }
        Log.i(TAG, "playPcm: wrote " + offset + "/" + pcm16.length + " samples");

        try {
            long durationMs = (long) pcm16.length * 1000L / sampleRate + 200;
            Thread.sleep(durationMs);
        } catch (InterruptedException ignored) {}

        track.stop();
        track.release();
        Log.i(TAG, "playPcm: done");
    }

    public void release() {
        if (ttsEngine != null) {
            ttsEngine.release();
            ttsEngine = null;
        }
    }
}
