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

    private static final String MODEL_FILE  = "de_DE-thorsten-medium.onnx";
    private static final String CONFIG_FILE = "de_DE-thorsten-medium.onnx.json";
    private static final String ASSET_DIR   = "tts-thorsten";
    private static final String MODEL_DIR   = "tts/thorsten";
    private static final String MARKER      = ".ready";

    private static final String[] MODEL_PARTS = {
        "de_DE-thorsten-medium.onnx.part_aa",
        "de_DE-thorsten-medium.onnx.part_ab",
        "de_DE-thorsten-medium.onnx.part_ac",
        "de_DE-thorsten-medium.onnx.part_ad",
    };

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
            && new File(dir, CONFIG_FILE).exists();
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

        postStatus("Sprachmodell wird zusammengesetzt …");
        File modelDest = new File(dir, MODEL_FILE);
        try (FileOutputStream out = new FileOutputStream(modelDest)) {
            byte[] buf = new byte[32768];
            for (int i = 0; i < MODEL_PARTS.length; i++) {
                postStatus("Sprachmodell: Teil " + (i + 1) + "/" + MODEL_PARTS.length + " …");
                try (InputStream in = assets.open(ASSET_DIR + "/" + MODEL_PARTS[i])) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }

        copyAsset(assets, ASSET_DIR + "/" + CONFIG_FILE, new File(dir, CONFIG_FILE));

        new File(dir, MARKER).createNewFile();
        Log.i(TAG, "Model extracted from assets");
    }

    private void copyAsset(AssetManager assets, String assetPath, File dest) throws IOException {
        try (InputStream in = assets.open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    private void initEngine() throws Exception {
        File dir = modelDir();
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(new File(dir, MODEL_FILE).getAbsolutePath());
        vits.setTokens("");
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

    public void speak(String text, CompletionCallback onDone) {
        if (ttsEngine == null) {
            Log.e(TAG, "speak() called before engine is ready");
            mainHandler.post(onDone::onDone);
            return;
        }
        new Thread(() -> {
            try {
                float[] samples    = ttsEngine.generate(text, 0, 1.0f).getSamples();
                int     sampleRate = ttsEngine.sampleRate();
                playPcm(samples, sampleRate);
            } catch (Exception e) {
                Log.e(TAG, "speak error", e);
            } finally {
                mainHandler.post(onDone::onDone);
            }
        }).start();
    }

    private void playPcm(float[] samples, int sampleRate) {
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

        int played = 0;
        while (played < pcm16.length) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            played = track.getPlaybackHeadPosition();
        }
        track.stop();
        track.release();
    }

    public void release() {
        if (ttsEngine != null) {
            ttsEngine.release();
            ttsEngine = null;
        }
    }
}
