package dev.notune.transcribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages saved recordings (.ogg) and transcripts (.txt).
 * Lists files from both internal storage and the custom SAF folder.
 * Supports playback, rename, delete for audio; edit additionally for text.
 */
public class RecordingsManagerActivity extends Activity {

    private static final String TAG       = "RecordingsManager";
    private static final String AUTHORITY = "dev.notune.transcribe.fileprovider";
    private static final String INTERNAL_DIR = "recordings/transkript";

    /** Represents a discovered file, either internal (File) or SAF (DocumentFile). */
    private static final class RecordingEntry {
        final String  baseName;    // without extension
        final String  ext;         // "ogg" or "txt"
        final File    internalFile; // null if SAF
        final DocumentFile safFile; // null if internal
        final Uri     uri;

        RecordingEntry(String baseName, String ext, File f) {
            this.baseName = baseName; this.ext = ext;
            this.internalFile = f; this.safFile = null;
            this.uri = null; // resolved via FileProvider when needed
        }
        RecordingEntry(String baseName, String ext, DocumentFile df) {
            this.baseName = baseName; this.ext = ext;
            this.internalFile = null; this.safFile = df;
            this.uri = df.getUri();
        }
        boolean isAudio() { return "ogg".equals(ext) || "m4a".equals(ext); }
        boolean isText()  { return "txt".equals(ext); }
    }

    /** A group of entries sharing the same base name. */
    private static final class RecordingGroup {
        final String baseName;
        RecordingEntry audio;
        RecordingEntry text;
        RecordingGroup(String baseName) { this.baseName = baseName; }
    }

    private LinearLayout listContainer;
    private MediaPlayer   mediaPlayer;
    private Button        activePlayButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recordings_manager_activity);
        listContainer = findViewById(R.id.list_container);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        loadRecordings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }

    // ------------------------------------------------------------------
    // Load & display
    // ------------------------------------------------------------------

    private void loadRecordings() {
        listContainer.removeAllViews();

        List<RecordingEntry> entries = collectEntries();
        Map<String, RecordingGroup> groups = groupByBaseName(entries);
        List<RecordingGroup> sorted = new ArrayList<>(groups.values());
        // Sort descending by base name (date prefix ensures newest first)
        Collections.sort(sorted, (a, b) -> b.baseName.compareTo(a.baseName));

        if (sorted.isEmpty()) {
            TextView empty = makeLabel(getString(R.string.recordings_empty), 15, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            listContainer.addView(empty);
            return;
        }

        for (RecordingGroup group : sorted) {
            listContainer.addView(buildGroupCard(group));
        }
    }

    private List<RecordingEntry> collectEntries() {
        List<RecordingEntry> result = new ArrayList<>();

        // 1. Internal storage
        File internalDir = new File(getFilesDir(), INTERNAL_DIR);
        if (internalDir.exists()) {
            File[] files = internalDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String[] parts = splitNameExt(f.getName());
                    if (parts != null) result.add(new RecordingEntry(parts[0], parts[1], f));
                }
            }
        }

        // 2. SAF custom folder
        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriStr = prefs.getString(SettingsActivity.KEY_SAVE_FOLDER_URI, null);
        if (uriStr != null) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(uriStr));
                if (dir != null && dir.canRead()) {
                    DocumentFile[] files = dir.listFiles();
                    if (files != null) {
                        for (DocumentFile df : files) {
                            String name = df.getName();
                            if (name == null) continue;
                            String[] parts = splitNameExt(name);
                            if (parts != null) result.add(new RecordingEntry(parts[0], parts[1], df));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "SAF listing failed", e);
            }
        }

        return result;
    }

    private Map<String, RecordingGroup> groupByBaseName(List<RecordingEntry> entries) {
        Map<String, RecordingGroup> map = new LinkedHashMap<>();
        for (RecordingEntry e : entries) {
            RecordingGroup g = map.computeIfAbsent(e.baseName, RecordingGroup::new);
            if (e.isAudio() && g.audio == null) g.audio = e;
            if (e.isText()  && g.text  == null) g.text  = e;
        }
        return map;
    }

    // ------------------------------------------------------------------
    // Card builder
    // ------------------------------------------------------------------

    private View buildGroupCard(RecordingGroup group) {
        int space3 = dp(12); int space2 = dp(8);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_card));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Title (base name)
        TextView title = makeLabel(group.baseName, 15, true);
        title.setTextColor(getColor(R.color.cl_ink));
        title.setPadding(0, 0, 0, space2);
        card.addView(title);

        // Audio row
        if (group.audio != null) {
            card.addView(buildAudioRow(group, group.audio));
        }

        // Divider between audio and text
        if (group.audio != null && group.text != null) {
            View div = new View(this);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            dp.setMargins(0, space2, 0, space2);
            div.setLayoutParams(dp);
            div.setBackgroundColor(getColor(R.color.cl_border));
            card.addView(div);
        }

        // Text row
        if (group.text != null) {
            card.addView(buildTextRow(group, group.text));
        }

        return card;
    }

    private View buildAudioRow(RecordingGroup group, RecordingEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Badge
        row.addView(makeBadge("Audio"));

        // Play button
        Button playBtn = makeSmallButton(getString(R.string.recordings_play));
        playBtn.setOnClickListener(v -> togglePlayback(entry, playBtn));
        activePlayButton = null;
        row.addView(playBtn);

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);

        // Rename
        Button renameBtn = makeSmallButton(getString(R.string.recordings_rename));
        renameBtn.setOnClickListener(v -> showRenameDialog(group));
        row.addView(renameBtn);

        // Delete
        Button deleteBtn = makeSmallButton(getString(R.string.recordings_delete));
        deleteBtn.setTextColor(getColor(R.color.cl_red));
        deleteBtn.setOnClickListener(v -> showDeleteDialog(entry, group));
        row.addView(deleteBtn);

        return row;
    }

    private View buildTextRow(RecordingGroup group, RecordingEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(makeBadge("Text"));

        // Edit button
        Button editBtn = makeSmallButton(getString(R.string.recordings_edit));
        editBtn.setOnClickListener(v -> showEditDialog(entry));
        row.addView(editBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);

        Button renameBtn = makeSmallButton(getString(R.string.recordings_rename));
        renameBtn.setOnClickListener(v -> showRenameDialog(group));
        row.addView(renameBtn);

        Button deleteBtn = makeSmallButton(getString(R.string.recordings_delete));
        deleteBtn.setTextColor(getColor(R.color.cl_red));
        deleteBtn.setOnClickListener(v -> showDeleteDialog(entry, group));
        row.addView(deleteBtn);

        return row;
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private void togglePlayback(RecordingEntry entry, Button btn) {
        // If something else is playing, stop it first
        if (activePlayButton != null && activePlayButton != btn) {
            stopPlayback();
            activePlayButton.setText(getString(R.string.recordings_play));
        }

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            stopPlayback();
            btn.setText(getString(R.string.recordings_play));
            activePlayButton = null;
        } else {
            Uri playUri = resolveUri(entry);
            if (playUri == null) { toast("Datei nicht gefunden"); return; }
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(this, playUri);
                mediaPlayer.prepare();
                mediaPlayer.start();
                btn.setText(getString(R.string.recordings_pause));
                activePlayButton = btn;
                mediaPlayer.setOnCompletionListener(mp -> {
                    btn.setText(getString(R.string.recordings_play));
                    activePlayButton = null;
                    stopPlayback();
                });
            } catch (Exception e) {
                Log.e(TAG, "Playback error", e);
                toast("Wiedergabe fehlgeschlagen: " + e.getMessage());
                stopPlayback();
            }
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    // ------------------------------------------------------------------
    // Edit text (with copy / save / open / share action buttons)
    // ------------------------------------------------------------------

    private void showEditDialog(RecordingEntry entry) {
        String content = readText(entry);

        // Root container
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));

        // Editable text field
        EditText editText = new EditText(this);
        editText.setText(content);
        editText.setMinLines(8);
        editText.setBackground(getDrawable(R.drawable.bg_edit_text));
        editText.setPadding(dp(12), dp(12), dp(12), dp(12));
        editText.setTextColor(getColor(R.color.cl_ink));
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etParams.setMargins(0, 0, 0, dp(12));
        editText.setLayoutParams(etParams);
        root.addView(editText);

        // Action button row: Kopieren | Speichern | Oeffnen | Teilen
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        // -- Kopieren --
        Button btnCopy = makeActionButton(getString(R.string.transcript_copy));
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("transcript", editText.getText().toString()));
            toast(getString(R.string.toast_copied));
        });
        btnRow.addView(btnCopy);

        // -- Speichern --
        Button btnSave = makeActionButton(getString(R.string.transcript_save));
        btnSave.setBackgroundTintList(getColorStateList(R.color.cl_green));
        btnSave.setOnClickListener(v -> saveText(entry, editText.getText().toString()));
        btnRow.addView(btnSave);

        // -- Öffnen --
        Button btnOpen = makeActionButton(getString(R.string.transcript_open));
        btnOpen.setBackgroundTintList(getColorStateList(R.color.cl_accent2));
        btnOpen.setOnClickListener(v -> {
            Uri fileUri = resolveUri(entry);
            if (fileUri == null) { toast("Datei nicht gefunden"); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, "text/plain");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); }
            catch (Exception e) { toast("Keine App zum Öffnen gefunden"); }
        });
        btnRow.addView(btnOpen);

        // -- Teilen --
        Button btnShare = makeActionButton(getString(R.string.transcript_share));
        btnShare.setBackground(getDrawable(R.drawable.bg_button_secondary));
        btnShare.setTextColor(getColor(R.color.cl_ink));
        btnShare.setOnClickListener(v -> {
            Uri fileUri = resolveUri(entry);
            if (fileUri == null) { toast("Datei nicht gefunden"); return; }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.putExtra(Intent.EXTRA_TEXT, editText.getText().toString());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.transcript_share)));
        });
        btnRow.addView(btnShare);

        root.addView(btnRow);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(entry.baseName + ".txt")
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String readText(RecordingEntry entry) {
        try {
            InputStream is;
            if (entry.internalFile != null) {
                is = new FileInputStream(entry.internalFile);
            } else {
                is = getContentResolver().openInputStream(entry.uri);
            }
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) { sb.append(line).append('\n'); }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "readText", e); return "";
        }
    }

    private void saveText(RecordingEntry entry, String text) {
        new Thread(() -> {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                OutputStream os;
                if (entry.internalFile != null) {
                    os = new FileOutputStream(entry.internalFile);
                } else {
                    os = getContentResolver().openOutputStream(entry.uri, "wt");
                }
                if (os == null) throw new IOException("No output stream");
                try (OutputStream out = os) { out.write(bytes); }
                runOnUiThread(() -> toast("Gespeichert"));
            } catch (Exception e) {
                Log.e(TAG, "saveText", e);
                runOnUiThread(() -> toast("Speichern fehlgeschlagen: " + e.getMessage()));
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // Rename
    // ------------------------------------------------------------------

    private void showRenameDialog(RecordingGroup group) {
        EditText input = new EditText(this);
        input.setText(group.baseName);
        input.setSelectAllOnFocus(true);
        input.setBackground(getDrawable(R.drawable.bg_edit_text));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setTextColor(getColor(R.color.cl_ink));
        input.setHint(getString(R.string.recordings_rename_hint));

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(16), dp(8), dp(16), dp(0));
        wrapper.addView(input);

        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(getString(R.string.recordings_rename))
                .setView(wrapper)
                .setPositiveButton(getString(R.string.recordings_save), (d, w) -> {
                    String newBase = input.getText().toString().trim();
                    if (!newBase.isEmpty() && !newBase.equals(group.baseName))
                        doRename(group, newBase);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doRename(RecordingGroup group, String newBase) {
        new Thread(() -> {
            boolean ok = true;
            if (group.audio != null) ok &= renameEntry(group.audio, newBase);
            if (group.text  != null) ok &= renameEntry(group.text,  newBase);
            final boolean success = ok;
            runOnUiThread(() -> {
                toast(success ? "Umbenannt" : "Umbenennen teilweise fehlgeschlagen");
                loadRecordings();
            });
        }).start();
    }

    private boolean renameEntry(RecordingEntry entry, String newBase) {
        String newName = newBase + "." + entry.ext;
        try {
            if (entry.internalFile != null) {
                File dest = new File(entry.internalFile.getParentFile(), newName);
                return entry.internalFile.renameTo(dest);
            } else {
                return entry.safFile.renameTo(newName);
            }
        } catch (Exception e) {
            Log.e(TAG, "rename", e); return false;
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    private void showDeleteDialog(RecordingEntry entry, RecordingGroup group) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle(getString(R.string.recordings_confirm_delete))
                .setMessage(entry.baseName + "." + entry.ext)
                .setPositiveButton(getString(R.string.recordings_delete), (d, w) -> doDelete(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doDelete(RecordingEntry entry) {
        new Thread(() -> {
            boolean ok;
            try {
                if (entry.internalFile != null) ok = entry.internalFile.delete();
                else ok = entry.safFile.delete();
            } catch (Exception e) { Log.e(TAG, "delete", e); ok = false; }
            final boolean success = ok;
            runOnUiThread(() -> {
                toast(success ? "Gelöscht" : "Löschen fehlgeschlagen");
                loadRecordings();
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Uri resolveUri(RecordingEntry entry) {
        if (entry.uri != null) return entry.uri;
        if (entry.internalFile != null) {
            try {
                return FileProvider.getUriForFile(this, AUTHORITY, entry.internalFile);
            } catch (Exception e) { Log.e(TAG, "FileProvider", e); return null; }
        }
        return null;
    }

    /** Split "basename.ext" → ["basename", "ext"], only for ogg/m4a/txt. */
    private static String[] splitNameExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 1) return null;
        String ext = name.substring(dot + 1).toLowerCase();
        if (!ext.equals("ogg") && !ext.equals("m4a") && !ext.equals("txt")) return null;
        return new String[]{name.substring(0, dot), ext};
    }

    private TextView makeLabel(String text, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(R.color.cl_ink_soft));
        if (bold) { tv.setTypeface(null, Typeface.BOLD); tv.setTextColor(getColor(R.color.cl_ink)); }
        return tv;
    }

    private View makeBadge(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(getColor(R.color.cl_accent));
        tv.setBackground(getDrawable(R.drawable.bg_edit_text));
        tv.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(8), 0);
        tv.setLayoutParams(p);
        return tv;
    }

    private Button makeSmallButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(getColor(R.color.cl_ink));
        btn.setBackground(getDrawable(R.drawable.bg_button_secondary));
        btn.setAllCaps(false);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(6), 0);
        btn.setLayoutParams(p);
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        return btn;
    }

    /** Full-width action button for the edit dialog (matches TranscribeFileActivity style). */
    private Button makeActionButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(getColor(android.R.color.white));
        btn.setBackground(getDrawable(R.drawable.bg_button_primary));
        btn.setAllCaps(false);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(0, 0, dp(3), 0);
        btn.setLayoutParams(p);
        btn.setPadding(dp(4), dp(8), dp(4), dp(8));
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        return btn;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
