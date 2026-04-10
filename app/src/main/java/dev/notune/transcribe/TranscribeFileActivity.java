package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class TranscribeFileActivity extends Activity {

    private static final String TAG              = "TranscribeFileActivity";
    private static final int    TARGET_SAMPLE_RATE = 16_000;

    private static final int STAGE_INIT        = 0;
    private static final int STAGE_LOADING     = 1;
    private static final int STAGE_DECODING    = 2;
    private static final int STAGE_TRANSCRIBE  = 3;
    private static final int STAGE_DONE        = 4;
    private static final int STAGE_ERROR       = 5;
    private volatile int stage = STAGE_INIT;

    private TextView   statusText;
    private TextView   detailText;
    private View       dot1, dot2, dot3;
    private View       progressArea;
    private ScrollView resultArea;
    private EditText    resultText;
    private TextView   savedPathText;
    private ImageButton copyButton;
    private ImageButton saveButton;
    private ImageButton shareButton;

    /** True once the first text chunk has arrived and dots have been hidden. */
    private boolean firstChunkReceived = false;

    private Uri    savedUri;
    private String savedFileName;
    private String pendingAudioBaseName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText    = findViewById(R.id.txt_status);
        detailText    = findViewById(R.id.txt_detail);
        dot1          = findViewById(R.id.dot_1);
        dot2          = findViewById(R.id.dot_2);
        dot3          = findViewById(R.id.dot_3);
        progressArea  = findViewById(R.id.progress_area);
        resultArea    = findViewById(R.id.result_area);
        resultText    = findViewById(R.id.txt_result);
        savedPathText = findViewById(R.id.txt_saved_path);
        copyButton    = findViewById(R.id.btn_copy);
        saveButton    = findViewById(R.id.btn_save);
        shareButton   = findViewById(R.id.btn_share);

        pendingAudioBaseName = getIntent().getStringExtra(DictateActivity.EXTRA_AUDIO_BASE_NAME);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> saveTranscript());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Transcription", text));
                Toast.makeText(this, getString(R.string.transcript_copied), Toast.LENGTH_SHORT).show();
            }
        });

        shareButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (text.isEmpty()) return;
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, getString(R.string.transcript_share_title)));
        });

        startDecodeAndTranscribe();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDotAnimation();
        cleanupNative();
    }

    // ------------------------------------------------------------------
    // Pulsing dot animation
    // ------------------------------------------------------------------

    private void startDotAnimation() {
        animateDot(dot1, 0);
        animateDot(dot2, 200);
        animateDot(dot3, 400);
    }

    private void animateDot(View dot, long startOffset) {
        if (dot == null) return;
        AlphaAnimation anim = new AlphaAnimation(0.2f, 1.0f);
        anim.setDuration(600);
        anim.setStartOffset(startOffset);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(Animation.INFINITE);
        dot.startAnimation(anim);
    }

    private void stopDotAnimation() {
        if (dot1 != null) dot1.clearAnimation();
        if (dot2 != null) dot2.clearAnimation();
        if (dot3 != null) dot3.clearAnimation();
    }

    // ------------------------------------------------------------------
    // Save / open
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
                            getString(R.string.transcript_saved_at, r.displayName, r.location));
                    savedPathText.setVisibility(View.VISIBLE);
                    saveButton.setVisibility(View.VISIBLE);
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

    // ------------------------------------------------------------------
    // Transcription callbacks (called from Rust via JNI)
    // ------------------------------------------------------------------

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if (stage > STAGE_LOADING && "Ready".equals(status)) return;
            if (stage == STAGE_DONE || stage == STAGE_ERROR) return;
            statusText.setText(status);
            if (status != null && status.startsWith("Error")) {
                stage = STAGE_ERROR;
                stopDotAnimation();
                detailText.setText(status);
                detailText.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Called by Rust after each 60-second chunk with the accumulated text so far.
     * On the first call the pulsing dots are hidden and the text area appears.
     * On subsequent calls only the text is updated.
     */
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            if (!firstChunkReceived) {
                firstChunkReceived = true;
                stopDotAnimation();
                progressArea.setVisibility(View.GONE);
                resultArea.setVisibility(View.VISIBLE);
            }
            resultText.setText(text);
        });
    }

    /**
     * Called by Rust once after ALL chunks are done.
     * Triggers auto-copy, auto-save and reveals the action buttons.
     */
    public void onTranscriptionComplete() {
        runOnUiThread(() -> {
            stage = STAGE_DONE;
            String text = resultText.getText().toString();

            copyButton .setVisibility(View.VISIBLE);
            saveButton .setVisibility(View.VISIBLE);
            shareButton.setVisibility(View.VISIBLE);

            // Auto-save and optionally rename the source audio file
            final String audioBase = pendingAudioBaseName;
            new Thread(() -> {
                TranscribeSaver.SaveResult r = TranscribeSaver.saveTranscriptRich(this, text);
                if (audioBase != null && r.isSuccess()) {
                    String slugBase = FileNameHelper.buildBaseName(text);
                    TranscribeSaver.renameAudio(this, audioBase, slugBase);
                }
                runOnUiThread(() -> {
                    if (r.isSuccess()) {
                        savedUri = r.shareUri;
                        savedFileName = r.displayName;
                        savedPathText.setText(
                                getString(R.string.transcript_saved_at,
                                        r.displayName, r.location));
                        savedPathText.setVisibility(View.VISIBLE);
                        saveButton.setVisibility(View.VISIBLE);
                    } else {
                        savedPathText.setText(
                                getString(R.string.transcript_save_error) + ": " + r.errorMessage);
                        savedPathText.setVisibility(View.VISIBLE);
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
                double durationSec = decodeAndFeedAudio(audioUri);
                long decodeMs = System.currentTimeMillis() - t0;
                if (durationSec < 0) {
                    stage = STAGE_ERROR;
                    runOnUiThread(() -> {
                        statusText.setText(getString(R.string.error_decode_empty));
                        detailText.setText(getString(R.string.error_decode_empty_detail));
                        stopDotAnimation();
                    });
                    return;
                }
                Log.i(TAG, "Decoded " + String.format("%.1f", durationSec) + "s in " + decodeMs + "ms");
                stage = STAGE_TRANSCRIBE;
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.status_transcribing));
                    detailText.setVisibility(View.GONE);
                    startDotAnimation();
                });
                transcribeAccumulated();
            } catch (Throwable e) {
                Log.e(TAG, "Error decoding audio", e);
                stage = STAGE_ERROR;
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    statusText.setText(getString(R.string.error_decode) + ": " + msg);
                    detailText.setText(e.toString());
                    detailText.setVisibility(View.VISIBLE);
                    stopDotAnimation();
                });
            }
        }).start();
    }

    /**
     * Decodes the audio at {@code uri}, resamples to {@link #TARGET_SAMPLE_RATE} Hz,
     * and streams the mono float samples to native memory in small chunks via
     * {@link #appendAudioSamples}.  No large Java-heap float[] is ever allocated.
     *
     * @return estimated duration in seconds, or -1 if no audio track was found.
     */
    private double decodeAndFeedAudio(Uri uri) throws IOException {
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
        if (audioTrackIndex < 0) {
            extractor.release();
            return -1;
        }

        extractor.selectTrack(audioTrackIndex);
        int sampleRate   = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        double durationSec = -1;
        if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            durationSec = inputFormat.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0;
        }

        MediaCodec codec = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        // Prepend 500 ms of silence to avoid decoder warm-up artefacts at the beginning
        float[] silence = new float[8_000]; // 0.5 s × 16 000 Hz, already zero-initialised
        appendAudioSamples(silence, 8_000);

        final double ratio = (double) sampleRate / TARGET_SAMPLE_RATE;
        double nextOutSrcPos = 0.0;
        float prevMono = 0.0f;
        long monoIdx = 0;

        final int CHUNK_SIZE = 8192;
        float[] chunk = new float[CHUNK_SIZE];
        int chunkLen = 0;

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
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(idx, 0, bytesRead, extractor.getSampleTime(), 0);
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

                    if (isPcmFloat[0]) {
                        FloatBuffer fb = outBuf.asFloatBuffer();
                        int frames = fb.remaining() / ch;
                        for (int i = 0; i < frames; i++) {
                            float mono;
                            if (ch == 1) {
                                mono = fb.get();
                            } else {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += fb.get();
                                mono = sum / ch;
                            }
                            while (nextOutSrcPos <= monoIdx) {
                                long floorPos = (long) nextOutSrcPos;
                                double frac   = nextOutSrcPos - floorPos;
                                float  xFloor = (floorPos == monoIdx) ? mono : prevMono;
                                chunk[chunkLen++] = (float) (xFloor * (1.0 - frac) + mono * frac);
                                nextOutSrcPos += ratio;
                                if (chunkLen == CHUNK_SIZE) {
                                    appendAudioSamples(chunk, CHUNK_SIZE);
                                    chunkLen = 0;
                                }
                            }
                            prevMono = mono;
                            monoIdx++;
                        }
                    } else {
                        ShortBuffer sb = outBuf.asShortBuffer();
                        int frames = sb.remaining() / ch;
                        for (int i = 0; i < frames; i++) {
                            float mono;
                            if (ch == 1) {
                                mono = sb.get() / 32768.0f;
                            } else {
                                float sum = 0;
                                for (int c = 0; c < ch; c++) sum += sb.get() / 32768.0f;
                                mono = sum / ch;
                            }
                            while (nextOutSrcPos <= monoIdx) {
                                long floorPos = (long) nextOutSrcPos;
                                double frac   = nextOutSrcPos - floorPos;
                                float  xFloor = (floorPos == monoIdx) ? mono : prevMono;
                                chunk[chunkLen++] = (float) (xFloor * (1.0 - frac) + mono * frac);
                                nextOutSrcPos += ratio;
                                if (chunkLen == CHUNK_SIZE) {
                                    appendAudioSamples(chunk, CHUNK_SIZE);
                                    chunkLen = 0;
                                }
                            }
                            prevMono = mono;
                            monoIdx++;
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        if (chunkLen > 0) {
            appendAudioSamples(chunk, chunkLen);
        }

        if (durationSec < 0) {
            long totalOutSamples = (long) (monoIdx / ratio);
            durationSec = totalOutSamples / (double) TARGET_SAMPLE_RATE;
        }
        return durationSec;
    }

    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    /** Appends {@code len} samples from {@code chunk} into native accumulation buffer. */
    private native void appendAudioSamples(float[] chunk, int len);
    /** Triggers transcription of everything accumulated via {@link #appendAudioSamples}. */
    private native void transcribeAccumulated();
}
