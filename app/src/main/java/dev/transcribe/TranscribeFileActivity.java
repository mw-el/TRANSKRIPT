package dev.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
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
    /** 60 seconds of 16 kHz audio — one transcription chunk. */
    private static final int    CHUNK_SAMPLES    = 60 * TARGET_SAMPLE_RATE; // 960 000

    private static final int STAGE_INIT       = 0;
    private static final int STAGE_LOADING    = 1;
    private static final int STAGE_DECODING   = 2;
    private static final int STAGE_TRANSCRIBE = 3;
    private static final int STAGE_DONE       = 4;
    private static final int STAGE_ERROR      = 5;
    private volatile int stage = STAGE_INIT;

    private ScrollView  resultArea;
    private EditText    resultText;
    private TextView    savedPathText;
    private ImageButton copyButton;
    private ImageButton saveButton;
    private ImageButton shareButton;

    // Floating status pill
    private View        pillStatus;
    private TextView    pillText;
    private ProgressBar pillSpinner;
    private float       pillDragStartX, pillDragStartY;
    private float       pillTouchRawX,  pillTouchRawY;

    private Uri    savedUri;
    private String savedFileName;
    private String pendingAudioBaseName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        resultArea    = findViewById(R.id.result_area);
        resultText    = findViewById(R.id.txt_result);
        savedPathText = findViewById(R.id.txt_saved_path);
        copyButton    = findViewById(R.id.btn_copy);
        saveButton    = findViewById(R.id.btn_save);
        shareButton   = findViewById(R.id.btn_share);

        pillStatus  = findViewById(R.id.pill_status);
        pillText    = findViewById(R.id.pill_text);
        pillSpinner = findViewById(R.id.pill_spinner);
        pillStatus.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pillDragStartX = v.getX();
                    pillDragStartY = v.getY();
                    pillTouchRawX  = event.getRawX();
                    pillTouchRawY  = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setX(pillDragStartX + (event.getRawX() - pillTouchRawX));
                    v.setY(pillDragStartY + (event.getRawY() - pillTouchRawY));
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        });

        pendingAudioBaseName = getIntent().getStringExtra(DictateActivity.EXTRA_AUDIO_BASE_NAME);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

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
        cleanupNative();
    }

    // ------------------------------------------------------------------
    // Save / transcript persistence
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
    // Transcription callbacks (called from Rust via JNI on background thread)
    // ------------------------------------------------------------------

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            if (stage > STAGE_LOADING && "Ready".equals(status)) return;
            if (stage == STAGE_DONE || stage == STAGE_ERROR) return;
            setPillText(status);
            if (status != null && status.startsWith("Error")) {
                stage = STAGE_ERROR;
                pillSpinner.setVisibility(View.GONE);
                // Keep pill visible for errors — no auto-hide
            }
        });
    }

    /** Called by Rust after each 60-second chunk with all text accumulated so far. */
    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            setPillText("Transkription läuft\u2026");
            resultText.setText(text);
        });
    }

    /** Called by Rust once after ALL chunks are transcribed. */
    public void onTranscriptionComplete() {
        runOnUiThread(() -> {
            stage = STAGE_DONE;
            String text = resultText.getText().toString();

            copyButton .setVisibility(View.VISIBLE);
            saveButton .setVisibility(View.VISIBLE);
            shareButton.setVisibility(View.VISIBLE);

            // Show "done" in pill, stop spinner, then fade out
            pillSpinner.setVisibility(View.GONE);
            setPillText("Transkription abgeschlossen \u2713");
            new Handler(Looper.getMainLooper()).postDelayed(this::hidePill, 3000);

            // Auto-save and rename audio
            final String audioBase = pendingAudioBaseName;
            new Thread(() -> {
                TranscribeSaver.SaveResult r = TranscribeSaver.saveTranscriptRich(this, text);
                if (audioBase != null && r.isSuccess()) {
                    String slugBase = FileNameHelper.buildBaseName(text);
                    TranscribeSaver.renameAudio(this, audioBase, slugBase);
                }
                runOnUiThread(() -> {
                    if (r.isSuccess()) {
                        savedUri      = r.shareUri;
                        savedFileName = r.displayName;
                        savedPathText.setText(
                                getString(R.string.transcript_saved_at,
                                        r.displayName, r.location));
                        savedPathText.setVisibility(View.VISIBLE);
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
    // Interleaved decode + transcribe
    //
    // Each 60-second decoded block is handed to Rust immediately via
    // transcribeChunkNative(), which blocks until Whisper finishes that
    // chunk and fires onTextTranscribed().  This means text starts
    // appearing after ~60 s instead of after the full file is decoded.
    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                return intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
        }
        return intent.getData();
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            stage = STAGE_ERROR;
            pillSpinner.setVisibility(View.GONE);
            setPillText(getString(R.string.error_no_audio));
            return;
        }
        stage = STAGE_LOADING;
        setPillText(getString(R.string.status_loading_model));

        initNative(this);

        new Thread(() -> {
            try {
                stage = STAGE_DECODING;
                runOnUiThread(() -> setPillText(getString(R.string.status_decoding)));
                decodeAndTranscribeInterleaved(audioUri);
            } catch (Throwable e) {
                Log.e(TAG, "Error during decode/transcribe", e);
                stage = STAGE_ERROR;
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                runOnUiThread(() -> {
                    pillSpinner.setVisibility(View.GONE);
                    setPillText(getString(R.string.error_decode) + ": " + msg);
                });
            }
        }).start();
    }

    /**
     * Decodes {@code uri}, resamples to 16 kHz mono, and feeds the audio to
     * {@link #transcribeChunkNative} in 60-second blocks — without ever holding
     * the entire file in memory.  Each call to transcribeChunkNative blocks until
     * Whisper finishes that chunk, so text trickles in progressively.
     */
    private void decodeAndTranscribeInterleaved(Uri uri) throws IOException {
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
            stage = STAGE_ERROR;
            runOnUiThread(() -> {
                pillSpinner.setVisibility(View.GONE);
                setPillText(getString(R.string.error_decode_empty));
            });
            return;
        }

        extractor.selectTrack(audioTrackIndex);
        int sampleRate   = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        // Estimate total minutes for progress display
        final int totalMinutes;
        if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            totalMinutes = Math.max(1, (int)(inputFormat.getLong(MediaFormat.KEY_DURATION) / 60_000_000L));
        } else {
            totalMinutes = 0;
        }

        MediaCodec codec = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        // Transcription chunk buffer.  Pre-filled with 500 ms of silence (warm-up).
        float[] chunkBuf = new float[CHUNK_SAMPLES]; // zero-initialised
        int chunkLen = 8_000;  // reserve first 0.5 s as silence
        int chunkNum = 0;

        final double ratio = (double) sampleRate / TARGET_SAMPLE_RATE;
        double nextOutSrcPos = 0.0;
        float  prevMono = 0.0f;
        long   monoIdx  = 0;

        int[]     outChannels = {channelCount};
        boolean[] isPcmFloat  = {false};

        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long    timeoutUs  = 10_000;

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
                                long   fp   = (long) nextOutSrcPos;
                                double frac = nextOutSrcPos - fp;
                                float  xfl  = (fp == monoIdx) ? mono : prevMono;
                                chunkBuf[chunkLen++] = (float)(xfl * (1.0 - frac) + mono * frac);
                                nextOutSrcPos += ratio;
                                if (chunkLen == CHUNK_SAMPLES) {
                                    final int cn = ++chunkNum;
                                    final int tm = totalMinutes;
                                    runOnUiThread(() -> updateDecodeProgress(cn, tm));
                                    transcribeChunkNative(chunkBuf, CHUNK_SAMPLES, false);
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
                                long   fp   = (long) nextOutSrcPos;
                                double frac = nextOutSrcPos - fp;
                                float  xfl  = (fp == monoIdx) ? mono : prevMono;
                                chunkBuf[chunkLen++] = (float)(xfl * (1.0 - frac) + mono * frac);
                                nextOutSrcPos += ratio;
                                if (chunkLen == CHUNK_SAMPLES) {
                                    final int cn = ++chunkNum;
                                    final int tm = totalMinutes;
                                    runOnUiThread(() -> updateDecodeProgress(cn, tm));
                                    transcribeChunkNative(chunkBuf, CHUNK_SAMPLES, false);
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

        // Final (possibly partial) chunk
        transcribeChunkNative(chunkBuf, chunkLen, true);
    }

    /** Updates pill while a chunk is being transcribed. Runs on UI thread. */
    private void updateDecodeProgress(int chunkNum, int totalMinutes) {
        if (stage < STAGE_TRANSCRIBE) stage = STAGE_TRANSCRIBE;
        String msg = (totalMinutes > 0)
                ? "Minute " + chunkNum + " von " + totalMinutes + "\u2026"
                : "Transkribiere\u2026";
        setPillText(msg);
    }

    // ------------------------------------------------------------------
    // Floating pill helpers
    // ------------------------------------------------------------------

    private void setPillText(String text) {
        if (pillText == null) return;
        pillText.setText(text);
        if (pillStatus.getVisibility() != View.VISIBLE) {
            pillStatus.setAlpha(0f);
            pillStatus.setVisibility(View.VISIBLE);
            pillStatus.animate().alpha(1f).setDuration(200).start();
        }
    }

    private void hidePill() {
        if (pillStatus == null) return;
        pillStatus.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> pillStatus.setVisibility(View.GONE))
                .start();
    }

    // ------------------------------------------------------------------
    // Native interface
    // ------------------------------------------------------------------

    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();

    /**
     * Transcribes {@code len} samples from {@code chunk} synchronously.
     * Calls onTextTranscribed with accumulated text after each chunk.
     * Calls onTranscriptionComplete after the last chunk ({@code isLast=true}).
     */
    private native void transcribeChunkNative(float[] chunk, int len, boolean isLast);
}
