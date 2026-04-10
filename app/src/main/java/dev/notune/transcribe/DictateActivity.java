package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DictateActivity extends Activity {

    private static final String TAG       = "DictateActivity";
    private static final String AUTHORITY = "dev.notune.transcribe.fileprovider";

    /** Extra key: timestamp-based base name of the already-saved audio file. */
    public static final String EXTRA_AUDIO_BASE_NAME = "audio_base_name";

    private MediaRecorder recorder;
    private File          outputFile;
    private boolean       isRecording = false;
    private long          startTimeMs;
    private String        audioBaseName; // timestamp base name of the saved recording

    private final Handler  timerHandler  = new Handler(Looper.getMainLooper());
    private final Handler  levelHandler  = new Handler(Looper.getMainLooper());

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            if (!isRecording) return;
            long secs = (System.currentTimeMillis() - startTimeMs) / 1000;
            tvTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", secs / 60, secs % 60));
            timerHandler.postDelayed(this, 500);
        }
    };

    private final Runnable levelRunnable = new Runnable() {
        @Override public void run() {
            if (!isRecording || recorder == null) return;
            int amp = recorder.getMaxAmplitude();
            waveformView.setLevel(amp / 32767f);
            levelHandler.postDelayed(this, 80);
        }
    };

    private ImageButton  btnRecord;
    private ImageButton  btnClose;
    private ImageButton  btnLock;
    private View         lockSection;
    private boolean      screenLocked = false;
    private TextView     tvStatus;
    private TextView     tvTimer;
    private TextView     tvHint;
    private WaveformView waveformView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dictate_activity);

        btnRecord    = findViewById(R.id.btn_record);
        btnClose     = findViewById(R.id.btn_close);
        btnLock      = findViewById(R.id.btn_screen_lock);
        lockSection  = findViewById(R.id.lock_section);
        tvStatus     = findViewById(R.id.tv_status);
        tvTimer      = findViewById(R.id.tv_timer);
        tvHint       = findViewById(R.id.tv_hint);
        waveformView = findViewById(R.id.waveform_view);

        btnClose.setOnClickListener(v -> finish());
        btnLock.setOnClickListener(v -> toggleScreenLock());

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopAndTranscribe();
            else             startRecording();
        });
    }

    private void startRecording() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        outputFile = new File(getCacheDir(), "diktat_" + ts + ".m4a");
        // Base name uses date format compatible with FileNameHelper (for sorting/grouping)
        audioBaseName = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(new Date());

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        // Record directly at 16 kHz mono — no resampling needed during transcription
        recorder.setAudioSamplingRate(16_000);
        recorder.setAudioChannels(1);
        recorder.setAudioEncodingBitRate(32_000);
        recorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            Log.e(TAG, "prepare() failed", e);
            tvStatus.setText(getString(R.string.dictate_error_mic));
            releaseRecorder();
            return;
        }

        isRecording = true;
        startTimeMs = System.currentTimeMillis();

        // UI: red round button with stop icon
        btnRecord.setBackground(getDrawable(R.drawable.bg_round_button_recording));
        btnRecord.setImageResource(R.drawable.ic_stop);
        tvStatus.setText(getString(R.string.dictate_recording));
        tvTimer.setText("00:00");
        tvHint.setVisibility(View.VISIBLE);

        // Show waveform, lock button, and start animation + level polling
        waveformView.setVisibility(View.VISIBLE);
        lockSection.setVisibility(View.VISIBLE);
        timerHandler.post(timerRunnable);
        levelHandler.postDelayed(levelRunnable, 80);
    }

    private void stopAndTranscribe() {
        timerHandler.removeCallbacks(timerRunnable);
        levelHandler.removeCallbacks(levelRunnable);
        isRecording = false;

        try { recorder.stop(); } catch (Exception e) { Log.w(TAG, "stop: " + e.getMessage()); }
        releaseRecorder();

        // Reset UI (also release lock if active)
        if (screenLocked) {
            screenLocked = false;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            btnRecord.setEnabled(true);
            btnClose.setEnabled(true);
        }
        waveformView.setVisibility(View.INVISIBLE);
        lockSection.setVisibility(View.GONE);
        btnRecord.setBackground(getDrawable(R.drawable.bg_round_button));
        btnRecord.setImageResource(R.drawable.ic_mic);
        tvHint.setVisibility(View.GONE);

        if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) {
            tvStatus.setText(getString(R.string.dictate_error_empty));
            return;
        }

        // Save a copy of the recording to the settings folder with timestamp name.
        // Do this on a background thread; TranscribeFileActivity handles the rename
        // after transcription is complete.
        final File fileToSave = outputFile;
        final String baseNameToPass = audioBaseName;
        new Thread(() -> {
            TranscribeSaver.saveAudioRaw(this, fileToSave, baseNameToPass);
            // Ignore save errors here — transcription proceeds regardless.
        }).start();

        Uri uri = FileProvider.getUriForFile(this, AUTHORITY, outputFile);
        Intent intent = new Intent(this, TranscribeFileActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "audio/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(EXTRA_AUDIO_BASE_NAME, audioBaseName);
        startActivity(intent);
        finish();
    }

    private void toggleScreenLock() {
        screenLocked = !screenLocked;
        if (screenLocked) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            btnRecord.setEnabled(false);
            btnClose.setEnabled(false);
            btnLock.setImageDrawable(getDrawable(R.drawable.ic_lock));
            btnLock.setColorFilter(getColor(R.color.cl_accent));
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            btnRecord.setEnabled(true);
            btnClose.setEnabled(true);
            btnLock.setImageDrawable(getDrawable(R.drawable.ic_lock_open));
            btnLock.clearColorFilter();
        }
    }

    private void releaseRecorder() {
        if (recorder != null) { recorder.release(); recorder = null; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        levelHandler.removeCallbacks(levelRunnable);
        if (isRecording) try { recorder.stop(); } catch (Exception ignored) {}
        releaseRecorder();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
