package dev.notune.transcribe;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import java.io.File;

public class RustInputMethodService extends InputMethodService {

    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private TextView statusView;
    private TextView hintView;
    private View recordContainer;
    private android.widget.ImageView micIcon;
    private ProgressBar progressBar;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    private static final long REPEAT_INITIAL_DELAY = 400;
    private static final long REPEAT_INTERVAL = 50;
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceRepeatRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private boolean pauseAudioActive = false;

    // Transkript-Text, der zusammen mit der PCM-Aufnahme gespeichert wird.
    // Wird in onTextTranscribed() gesetzt und in onPcmAvailable() verwendet.
    private volatile String pendingTranscriptForSave = null;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Exception e) {
            Log.e(TAG, "Error in initNative", e);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            View view = getLayoutInflater().inflate(R.layout.ime_layout, null);

            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            statusView = view.findViewById(R.id.ime_status_text);
            progressBar = view.findViewById(R.id.ime_progress);
            recordContainer = view.findViewById(R.id.ime_record_container);
            micIcon = view.findViewById(R.id.ime_mic_icon);
            hintView = view.findViewById(R.id.ime_hint);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopRecording();
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            });

            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            spaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(" ", 1);
                    }
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL));
                        }
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.commitText(" ", 1);
                        }
                        mainHandler.postDelayed(spaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(spaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordContainer.setOnClickListener(v -> {
                if (!recordContainer.isEnabled()) return;
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (statusView != null) statusView.setText("No mic permission - grant in app");
                    if (hintView != null) hintView.setText("Open the app to grant permission");
                    return;
                }
                if (isRecording) {
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    updateRecordButtonUI(false);
                } else {
                    pendingTranscriptForSave = null;
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    startRecording();
                    updateRecordButtonUI(true);
                }
            });

            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (!isRecording && new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                pendingTranscriptForSave = null;
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                startRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        if (isRecording) {
            try {
                cancelRecording();
            } catch (Throwable t) {
                Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                try { stopRecording(); } catch (Throwable ignored) { }
            }
            updateRecordButtonUI(false);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recording) {
            micIcon.setColorFilter(0xFFF44336);
            statusView.setText("Listening...");
            hintView.setText("Tap to Stop");
        } else {
            micIcon.setColorFilter(0xFF2196F3);
            statusView.setText("Processing...");
            hintView.setText("Tap to Record");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // ----------------------------------------------------------------
    // Callbacks from Rust
    // ----------------------------------------------------------------

    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;
            updateUiState();
            if (pendingSwitchBack && status.startsWith("Error")) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    public void onTextTranscribed(String text) {
        // Remember text so onPcmAvailable() can use it as filename
        pendingTranscriptForSave = text;

        mainHandler.post(() -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String committed = text + " ";
                ic.commitText(committed, 1);

                if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
                    android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                        new android.view.inputmethod.ExtractedTextRequest(), 0);
                    if (et != null) {
                        int end = et.selectionStart;
                        int start = end - committed.length();
                        if (start >= 0) ic.setSelection(start, end);
                    }
                }
            }
            if (pauseAudioActive) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
            updateRecordButtonUI(false);
            if (statusView != null) statusView.setText("Tap to Record");
            if (pendingSwitchBack) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
        });
    }

    /**
     * Called from Rust after stopRecording() completes.
     * {@code pcm16} contains the full signed 16-bit little-endian PCM buffer
     * recorded at 16 kHz mono.
     *
     * The buffer is encoded to Opus and saved in a background thread so we
     * don't block the main thread or the Rust caller.
     *
     * Rust-side: call this via JNI with the accumulated PCM bytes before
     * discarding the audio buffer. Example (pseudo-Rust):
     *   env.call_method(&service, "onPcmAvailable", "([B)V", &[pcm_jbytearray])
     */
    public void onPcmAvailable(byte[] pcm16) {
        if (pcm16 == null || pcm16.length == 0) return;
        final String transcriptText = pendingTranscriptForSave != null
                ? pendingTranscriptForSave : "";
        pendingTranscriptForSave = null;

        new Thread(() -> {
            String saved = TranscribeSaver.saveRecording(this, transcriptText, pcm16);
            if (saved != null)
                Log.d(TAG, "IME Aufnahme gespeichert: " + saved);
            else
                Log.w(TAG, "IME Aufnahme konnte nicht gespeichert werden");
        }).start();
    }

    public void onAudioLevel(float level) { }

    // ----------------------------------------------------------------
    // Native methods
    // ----------------------------------------------------------------

    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void updateUiState() {
        boolean isLoading      = lastStatus.contains("Loading") || lastStatus.contains("Initializing");
        boolean isTranscribing = lastStatus.contains("Transcribing") || lastStatus.contains("Processing");
        boolean isWaiting      = lastStatus.contains("Waiting");
        boolean isError        = lastStatus.startsWith("Error");

        if (statusView != null && !isRecording) {
            if (isError)                          statusView.setText(lastStatus);
            else if (isTranscribing || isWaiting) statusView.setText("Processing...");
            else                                  statusView.setText("Tap to Record");
        }
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (recordContainer != null) {
            boolean disable = isTranscribing || isWaiting || isError;
            recordContainer.setEnabled(!disable);
            recordContainer.setAlpha(disable ? 0.5f : 1.0f);
        }
        if (hintView != null && !isRecording) hintView.setText("Tap to Record");
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }
}
