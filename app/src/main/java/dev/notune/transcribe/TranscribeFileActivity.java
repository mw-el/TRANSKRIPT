package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

public class TranscribeFileActivity extends Activity {

    private static final String TAG              = "TranscribeFileActivity";
    private static final int    TARGET_SAMPLE_RATE = 16_000;

    // Stage of the pipeline — later stages lock out "Ready" status noise
    // coming from the background model-loading thread.
    private static final int STAGE_INIT        = 0;
    private static final int STAGE_LOADING     = 1;
    private static final int STAGE_DECODING    = 2;
    private static final int STAGE_TRANSCRIBE  = 3;
    private static final int STAGE_DONE        = 4;
    private static final int STAGE_ERROR       = 5;
    private volatile int stage = STAGE_INIT;

    private TextView    statusText;
    private TextView    detailText;
    private ProgressBar progressBar;
    private View        progressArea;
    private ScrollView  resultArea;
    private TextView    resultText;
    private TextView    savedPathText;
    private Button      copyButton;
    private Button      saveButton;
    private Button      openButton;
    private Button      shareButton;
    private Button      mailButton;

    private Uri    savedUri;       // content:// of auto-saved .txt
    private String savedFileName;  // display name

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText    = findViewById(R.id.txt_status);
        detailText    = findViewById(R.id.txt_detail);
        progressBar   = findViewById(R.id.progress_bar);
        progressArea  = findViewById(R.id.progress_area);
        resultArea    = findViewById(R.id.result_area);
        resultText    = findViewById(R.id.txt_result);
        savedPathText = findViewById(R.id.txt_saved_path);
        copyButton    = findViewById(R.id.btn_copy);
        saveButton    = findViewById(R.id.btn_save);
        openButton    = findViewById(R.id.btn_open);
        shareButton   = findViewById(R.id.btn_share);
        mailButton    = findViewById(R.id.btn_mail);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        openButton.setOnClickListener(v -> openSavedFile());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Transcription", text));
                Toast.makeText(this, getString(R.string.transcript_copied), Toast.LENGTH_SHORT).show();
            }
        });

        saveButton.setOnClickListener(v -> saveTranscript());

        shareButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (text.isEmpty()) return;
            // Share as plain text via Android share sheet
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.transcript_share_title)));
        });

        mailButton.setOnClickListener(v -> sendByEmail());

        startDecodeAndTranscribe();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupNative();
    }

    // ------------------------------------------------------------------
    // E-mail
    // ------------------------------------------------------------------

    private void sendByEmail() {
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String defaultEmail = prefs.getString(SettingsActivity.KEY_EMAIL_ADDRESS, "");

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:"));
        if (!defaultEmail.isEmpty())
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{defaultEmail});
        intent.putExtra(Intent.EXTRA_SUBJECT,
                getString(R.string.mail_subject_prefix) + " "
                        + java.time.LocalDate.now());
        intent.putExtra(Intent.EXTRA_TEXT, text);

        if (intent.resolveActivity(getPackageManager()) != null)
            startActivity(intent);
        else
            Toast.makeText(this, getString(R.string.mail_no_app), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private void saveTranscript() {
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;
        new Thread(() -> {
            TranscribeSaver.SaveResult r = TranscribeSaver.saveTranscriptRich(this, text);
            runOnUiThread(() -> {
                if (r.isSuccess()) {
                    savedUri = r.shareUri;
                    savedFileName = r.displayName;
                    savedPathText.setText(
                            getString(R.string.transcript_saved_at,
                                    r.displayName, r.location));
                    savedPathText.setVisibility(View.VISIBLE);
                    openButton.setVisibility(View.VISIBLE);
                    Toast.makeText(this,
                            getString(R.string.transcript_saved, r.displayName),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            getString(R.string.transcript_save_error) + ": " + r.errorMessage,
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void openSavedFile() {
        if (savedUri == null) {
            Toast.makeText(this, getString(R.string.transcript_no_saved),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(savedUri, "text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(intent,
                    getString(R.string.transcript_open_with)));
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.transcript_open_error) + ": " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------
    // Transcription callbacks (called from Rust)
    // ------------------------------------------------------------------

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            // Filter "Ready" once we've moved past the loading stage —
            // the engine load thread keeps pinging "Ready" and would
            // otherwise overwrite more useful status like "Dekodiere…".
            if (stage > STAGE_LOADING && "Ready".equals(status)) return;
            // Don't overwrite terminal states
            if (stage == STAGE_DONE || stage == STAGE_ERROR) return;
            statusText.setText(status);
            if (status != null && status.startsWith("Error")) {
                stage = STAGE_ERROR;
                progressBar.setVisibility(View.GONE);
                detailText.setText(status);
                detailText.setVisibility(View.VISIBLE);
            }
        });
    }

    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            stage = STAGE_DONE;
            progressArea.setVisibility(View.GONE);
            resultArea  .setVisibility(View.VISIBLE);
            copyButton  .setVisibility(View.VISIBLE);
            saveButton  .setVisibility(View.VISIBLE);
            shareButton .setVisibility(View.VISIBLE);
            mailButton  .setVisibility(View.VISIBLE);
            resultText  .setText(text);

            // Auto-copy
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Transcription", text));

            // Auto-save transcript and show path + Öffnen button
            new Thread(() -> {
                TranscribeSaver.SaveResult r =
                        TranscribeSaver.saveTranscriptRich(this, text);
                runOnUiThread(() -> {
                    if (r.isSuccess()) {
                        savedUri = r.shareUri;
                        savedFileName = r.displayName;
                        savedPathText.setText(
                                getString(R.string.transcript_saved_at,
                                        r.displayName, r.location));
                        savedPathText.setVisibility(View.VISIBLE);
                        openButton.setVisibility(View.VISIBLE);
                        Toast.makeText(this,
                                getString(R.string.transcript_saved, r.displayName),
                                Toast.LENGTH_LONG).show();
                    } else {
                        savedPathText.setText(
                                getString(R.string.transcript_save_error)
                                        + ": " + r.errorMessage);
                        savedPathText.setVisibility(View.VISIBLE);
                        Toast.makeText(this,
                                getString(R.string.transcript_save_error) + ": "
                                        + r.errorMessage,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }).start();
        });
    }

    // ------------------------------------------------------------------
    // Audio decoding
    // ------------------------------------------------------------------

    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action))
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return intent.getData();
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            stage = STAGE_ERROR;
            statusText.setText(getString(R.string.error_no_audio));
            progressBar.setVisibility(View.GONE);
            return;
        }
        stage = STAGE_LOADING;
        statusText.setText(getString(R.string.status_loading_model));
        detailText.setText(getString(R.string.status_loading_model_detail));
        detailText.setVisibility(View.VISIBLE);

        initNative(this);
        new Thread(() -> {
            try {
                stage = STAGE_DECODING;
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.status_decoding));
                    detailText.setText(getString(R.string.status_decoding_detail));
                });
                long t0 = System.currentTimeMillis();
                float[] samples = decodeAudioToSamples(audioUri);
                long decodeMs = System.currentTimeMillis() - t0;
                if (samples == null || samples.length == 0) {
                    stage = STAGE_ERROR;
                    runOnUiThread(() -> {
                        statusText.setText(getString(R.string.error_decode_empty));
                        detailText.setText(getString(R.string.error_decode_empty_detail));
                        progressBar.setVisibility(View.GONE);
                    });
                    return;
                }
                final int sampleCount = samples.length;
                final double durationSec = sampleCount / (double) TARGET_SAMPLE_RATE;
                Log.i(TAG, "Decoded " + sampleCount + " samples ("
                        + String.format("%.1f", durationSec) + "s) in " + decodeMs + "ms");
                stage = STAGE_TRANSCRIBE;
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.status_transcribing));
                    detailText.setText(getString(R.string.status_transcribing_detail,
                            String.format("%.1f", durationSec)));
                });
                transcribeAudio(samples, samples.length);
            } catch (Throwable e) {
                Log.e(TAG, "Error decoding audio", e);
                stage = STAGE_ERROR;
                final String msg = e.getMessage() != null
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.error_decode) + ": " + msg);
                    detailText.setText(e.toString());
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private float[] decodeAudioToSamples(Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(this, uri, null);

        int audioTrackIndex = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            if (fmt.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrackIndex = i;
                inputFormat = fmt;
                break;
            }
        }
        if (audioTrackIndex < 0) return null;

        extractor.selectTrack(audioTrackIndex);
        int sampleRate   = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        // Pre-allocate a single buffer based on duration to avoid double-buffering.
        // The old approach (List<float[]> + mergeChunks) used 2x the memory peak.
        int estimatedSamples = TARGET_SAMPLE_RATE * 120; // fallback: 2 minutes
        if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            long durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION);
            double srcSamples = (durationUs / 1_000_000.0) * sampleRate;
            estimatedSamples = (int) (srcSamples * TARGET_SAMPLE_RATE / (double) sampleRate * 1.05 + 0.5);
            if (estimatedSamples < TARGET_SAMPLE_RATE * 5) estimatedSamples = TARGET_SAMPLE_RATE * 5;
        }
        float[] sampleBuf = new float[estimatedSamples];
        int writtenSamples = 0;

        MediaCodec codec = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        int[]     outChannels = {channelCount};
        boolean[] isPcmFloat  = {false};

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone) {
            if (!inputDone) {
                int idx = codec.dequeueInputBuffer(timeoutUs);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(idx);
                    int bytesRead = extractor.readSampleData(buf, 0);
                    if (bytesRead < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(idx, 0, bytesRead,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = codec.getOutputFormat();
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    outChannels[0] = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    isPcmFloat[0] = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4;
            } else if (outIdx >= 0) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    outputDone = true;
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset);
                    outBuf.limit(bufferInfo.offset + bufferInfo.size);
                    outBuf.order(ByteOrder.LITTLE_ENDIAN);
                    int ch = outChannels[0];
                    int monoCount;
                    if (isPcmFloat[0]) {
                        FloatBuffer fb = outBuf.asFloatBuffer();
                        monoCount = fb.remaining() / ch;
                        if (writtenSamples + monoCount > sampleBuf.length)
                            sampleBuf = Arrays.copyOf(sampleBuf,
                                    Math.max(sampleBuf.length * 2, writtenSamples + monoCount));
                        for (int i = 0; i < monoCount; i++) {
                            if (ch == 1) {
                                sampleBuf[writtenSamples++] = fb.get();
                            } else {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += fb.get();
                                sampleBuf[writtenSamples++] = sum / ch;
                            }
                        }
                    } else {
                        ShortBuffer sb = outBuf.asShortBuffer();
                        monoCount = sb.remaining() / ch;
                        if (writtenSamples + monoCount > sampleBuf.length)
                            sampleBuf = Arrays.copyOf(sampleBuf,
                                    Math.max(sampleBuf.length * 2, writtenSamples + monoCount));
                        for (int i = 0; i < monoCount; i++) {
                            if (ch == 1) {
                                sampleBuf[writtenSamples++] = sb.get() / 32768.0f;
                            } else {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += sb.get() / 32768.0f;
                                sampleBuf[writtenSamples++] = sum / ch;
                            }
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
            }
        }
        codec.stop(); codec.release();
        extractor.release();

        // Trim to actual size only if significantly over-allocated (> 5% waste)
        float[] mono;
        if (writtenSamples < sampleBuf.length * 0.95) {
            mono = Arrays.copyOf(sampleBuf, writtenSamples);
            sampleBuf = null; // help GC
        } else {
            mono = sampleBuf;
        }

        if (sampleRate != TARGET_SAMPLE_RATE)
            mono = resample(mono, sampleRate, TARGET_SAMPLE_RATE);
        return mono;
    }

    private float[] resample(float[] input, int from, int to) {
        double ratio = (double) from / to;
        float[] output = new float[(int) (input.length / ratio)];
        for (int i = 0; i < output.length; i++) {
            double src  = i * ratio;
            int    idx  = (int) src;
            double frac = src - idx;
            output[i] = (idx + 1 < input.length)
                    ? (float) (input[idx] * (1 - frac) + input[idx + 1] * frac)
                    : (idx < input.length ? input[idx] : 0);
        }
        return output;
    }

    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    private native void transcribeAudio(float[] samples, int length);
}
