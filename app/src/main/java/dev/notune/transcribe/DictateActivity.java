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
import android.widget.Button;
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

    private MediaRecorder recorder;
    private File          outputFile;
    private boolean       isRecording = false;
    private long          startTimeMs;

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

    private Button      btnRecord;
    private TextView    tvStatus;
    private TextView    tvTimer;
    private WaveformView waveformView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dictate_activity);

        btnRecord    = findViewById(R.id.btn_record);
        tvStatus     = findViewById(R.id.tv_status);
        tvTimer      = findViewById(R.id.tv_timer);
        waveformView = findViewById(R.id.waveform_view);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopAndTranscribe();
            else             startRecording();
        });
    }

    private void startRecording() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        outputFile = new File(getCacheDir(), "diktat_" + ts + ".m4a");

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(128_000);
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

        // UI: red round button
        btnRecord.setBackground(getDrawable(R.drawable.bg_round_button_recording));
        btnRecord.setText(getString(R.string.dictate_btn_stop));
        tvStatus.setText(getString(R.string.dictate_recording));
        tvTimer.setText("00:00");

        // Show waveform and start animation + level polling
        waveformView.setVisibility(View.VISIBLE);
        timerHandler.post(timerRunnable);
        levelHandler.postDelayed(levelRunnable, 80);
    }

    private void stopAndTranscribe() {
        timerHandler.removeCallbacks(timerRunnable);
        levelHandler.removeCallbacks(levelRunnable);
        isRecording = false;

        try { recorder.stop(); } catch (Exception e) { Log.w(TAG, "stop: " + e.getMessage()); }
        releaseRecorder();

        // Reset UI
        waveformView.setVisibility(View.INVISIBLE);
        btnRecord.setBackground(getDrawable(R.drawable.bg_round_button));
        btnRecord.setText(getString(R.string.dictate_btn_start));

        if (outputFile == null || !outputFile.exists() || outputFile.length() == 0) {
            tvStatus.setText(getString(R.string.dictate_error_empty));
            return;
        }

        Uri uri = FileProvider.getUriForFile(this, AUTHORITY, outputFile);
        Intent intent = new Intent(this, TranscribeFileActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "audio/mp4");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
        finish();
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
    }
}
