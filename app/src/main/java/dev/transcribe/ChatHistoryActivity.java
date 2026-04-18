package dev.transcribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChatHistoryActivity extends Activity {

    private static final String TAG       = "ChatHistory";
    private static final String AUTHORITY = "dev.transcribe.fileprovider";

    private static final class ChatEntry {
        final String baseName;
        final File   internalFile;
        final DocumentFile safFile;
        final Uri    uri;

        ChatEntry(String baseName, File f) {
            this.baseName = baseName;
            this.internalFile = f;
            this.safFile = null;
            this.uri = null;
        }
        ChatEntry(String baseName, DocumentFile df) {
            this.baseName = baseName;
            this.internalFile = null;
            this.safFile = df;
            this.uri = df.getUri();
        }
    }

    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat_history_activity);
        listContainer = findViewById(R.id.list_container);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        loadChats();
    }

    private void loadChats() {
        listContainer.removeAllViews();

        Map<String, ChatEntry> map = new LinkedHashMap<>();

        File internalDir = new File(getFilesDir(), ChatActivity.INTERNAL_CHAT_DIR);
        if (internalDir.exists()) {
            File[] files = internalDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (name.startsWith("chat_") && name.endsWith(".md")) {
                        String base = name.substring(0, name.length() - 3);
                        map.putIfAbsent(base, new ChatEntry(base, f));
                    }
                }
            }
        }

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
                            if (name != null && name.startsWith("chat_") && name.endsWith(".md")) {
                                String base = name.substring(0, name.length() - 3);
                                map.putIfAbsent(base, new ChatEntry(base, df));
                            }
                        }
                    }
                }
            } catch (Exception e) { Log.w(TAG, "SAF listing", e); }
        }

        List<ChatEntry> sorted = new ArrayList<>(map.values());
        Collections.sort(sorted, (a, b) -> b.baseName.compareTo(a.baseName));

        if (sorted.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Noch keine Chats gespeichert.");
            empty.setTextSize(15);
            empty.setTextColor(getColor(R.color.cl_ink_soft));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(48), 0, 0);
            listContainer.addView(empty);
            return;
        }

        for (ChatEntry e : sorted) {
            listContainer.addView(buildCard(e));
        }
    }

    private View buildCard(ChatEntry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_card));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(entry.baseName);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(getColor(R.color.cl_ink));
        title.setPadding(0, 0, 0, dp(6));
        card.addView(title);

        String preview = firstLines(readText(entry), 3);
        if (!preview.isEmpty()) {
            TextView prev = new TextView(this);
            prev.setText(preview);
            prev.setTextSize(13);
            prev.setTextColor(getColor(R.color.cl_ink_soft));
            prev.setMaxLines(3);
            prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            prev.setPadding(0, 0, 0, dp(8));
            card.addView(prev);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(iconBtn(R.drawable.ic_edit_doc, R.color.cl_ink,
            v -> showEditDialog(entry)));
        row.addView(iconBtn(R.drawable.ic_play, R.color.cl_accent,
            v -> continueChat(entry)));
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);
        row.addView(iconBtn(R.drawable.ic_share, R.color.cl_ink_soft,
            v -> shareEntry(entry)));
        row.addView(iconBtn(R.drawable.ic_delete, R.color.cl_red,
            v -> confirmDelete(entry)));
        card.addView(row);

        card.setOnClickListener(v -> showEditDialog(entry));
        return card;
    }

    private void continueChat(ChatEntry entry) {
        try {
            String baseName = ensureInternalMarkdown(entry);
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_CHAT_BASE_NAME, baseName);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "continueChat", e);
            toast("Chat kann nicht fortgesetzt werden");
        }
    }

    private String ensureInternalMarkdown(ChatEntry entry) throws Exception {
        if (entry.internalFile != null) return entry.baseName;

        File dir = new File(getFilesDir(), ChatActivity.INTERNAL_CHAT_DIR);
        if (!dir.exists()) dir.mkdirs();
        File copy = new File(dir, entry.baseName + ".md");
        try (FileOutputStream out = new FileOutputStream(copy)) {
            out.write(readText(entry).getBytes(StandardCharsets.UTF_8));
        }
        return entry.baseName;
    }

    private String firstLines(String s, int n) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String l : lines) {
            if (l.startsWith("#") || l.startsWith("**") || l.isEmpty() || l.equals("---")) continue;
            sb.append(l).append('\n');
            count++;
            if (count >= n) break;
        }
        return sb.toString().trim();
    }

    private String readText(ChatEntry entry) {
        try {
            InputStream is = entry.internalFile != null
                ? new FileInputStream(entry.internalFile)
                : getContentResolver().openInputStream(entry.uri);
            if (is == null) return "";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "readText", e); return "";
        }
    }

    private void saveText(ChatEntry entry, String text) {
        new Thread(() -> {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                OutputStream os = entry.internalFile != null
                    ? new FileOutputStream(entry.internalFile)
                    : getContentResolver().openOutputStream(entry.uri, "wt");
                if (os == null) throw new Exception("No output stream");
                try (OutputStream out = os) { out.write(bytes); }
                if (entry.internalFile != null) deleteInternalJsonSidecar(entry.baseName);
                runOnUiThread(() -> toast("Gespeichert"));
            } catch (Exception e) {
                Log.e(TAG, "saveText", e);
                runOnUiThread(() -> toast("Speichern fehlgeschlagen"));
            }
        }).start();
    }

    private void showEditDialog(ChatEntry entry) {
        String content = readText(entry);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, 0, 0, dp(12));
        header.setLayoutParams(headerParams);

        TextView titleView = new TextView(this);
        titleView.setText(entry.baseName);
        titleView.setTextSize(16);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(getColor(R.color.cl_ink));
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(titleView);

        ImageButton btnX = new ImageButton(this);
        btnX.setImageDrawable(getDrawable(android.R.drawable.ic_menu_close_clear_cancel));
        btnX.setColorFilter(getColor(R.color.cl_ink_soft));
        btnX.setBackground(null);
        btnX.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        header.addView(btnX);

        root.addView(header);

        AlertDialog[] dialog = {null};
        btnX.setOnClickListener(v -> { if (dialog[0] != null) dialog[0].dismiss(); });

        EditText editText = new EditText(this);
        editText.setText(content);
        editText.setMinLines(10);
        editText.setBackground(getDrawable(R.drawable.bg_edit_text));
        editText.setPadding(dp(12), dp(12), dp(12), dp(12));
        editText.setTextColor(getColor(R.color.cl_ink));
        editText.setTextSize(13);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etParams.setMargins(0, 0, 0, dp(12));
        editText.setLayoutParams(etParams);
        root.addView(editText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        root.addView(btnRow);

        ImageButton btnCopy = bigIconBtn(R.drawable.ic_copy, R.color.cl_accent, dp(12));
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("chat", editText.getText().toString()));
            toast("Kopiert");
        });
        btnRow.addView(btnCopy);

        ImageButton btnSave = bigIconBtn(R.drawable.ic_save, R.color.cl_accent2, dp(12));
        btnSave.setOnClickListener(v -> saveText(entry, editText.getText().toString()));
        btnRow.addView(btnSave);

        ImageButton btnShare = bigIconBtn(R.drawable.ic_share, R.color.cl_green, 0);
        btnShare.setOnClickListener(v -> {
            Uri fileUri = resolveUri(entry);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            if (fileUri != null) intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.putExtra(Intent.EXTRA_TEXT, editText.getText().toString());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Chat teilen"));
        });
        btnRow.addView(btnShare);

        dialog[0] = new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setView(root)
            .create();
        dialog[0].show();
    }

    private void shareEntry(ChatEntry entry) {
        try {
            String content = readText(entry);
            Uri fileUri = resolveUri(entry);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            if (fileUri != null) intent.putExtra(Intent.EXTRA_STREAM, fileUri);
            intent.putExtra(Intent.EXTRA_TEXT, content);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Chat teilen"));
        } catch (Exception e) { Log.e(TAG, "share", e); }
    }

    private Uri resolveUri(ChatEntry entry) {
        if (entry.uri != null) return entry.uri;
        if (entry.internalFile != null) {
            try {
                return FileProvider.getUriForFile(this, AUTHORITY, entry.internalFile);
            } catch (Exception e) { Log.e(TAG, "FileProvider", e); }
        }
        return null;
    }

    private void confirmDelete(ChatEntry entry) {
        new AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("L\u00f6schen best\u00e4tigen")
            .setMessage(entry.baseName)
            .setPositiveButton("L\u00f6schen", (d, w) -> {
                try {
                    if (entry.internalFile != null) {
                        entry.internalFile.delete();
                        deleteInternalJsonSidecar(entry.baseName);
                    } else {
                        entry.safFile.delete();
                    }
                } catch (Exception ignored) {}
                loadChats();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private ImageButton iconBtn(int iconRes, int colorRes, View.OnClickListener l) {
        ImageButton btn = new ImageButton(this);
        btn.setImageDrawable(getDrawable(iconRes));
        btn.setColorFilter(getColor(colorRes));
        int[] attrs = {android.R.attr.selectableItemBackgroundBorderless};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        btn.setBackground(ta.getDrawable(0));
        ta.recycle();
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
        p.setMargins(0, 0, dp(4), 0);
        btn.setLayoutParams(p);
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
        btn.setOnClickListener(l);
        return btn;
    }

    private ImageButton bigIconBtn(int iconRes, int colorRes, int marginEnd) {
        ImageButton btn = new ImageButton(this);
        btn.setImageDrawable(getDrawable(iconRes));
        btn.setColorFilter(getColor(R.color.white));
        btn.setBackground(getDrawable(R.drawable.bg_button_primary));
        btn.setBackgroundTintList(getColorStateList(colorRes));
        btn.setPadding(dp(14), dp(14), dp(14), dp(14));
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(56), dp(56));
        p.setMargins(0, 0, marginEnd, 0);
        btn.setLayoutParams(p);
        return btn;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void deleteInternalJsonSidecar(String baseName) {
        try {
            File dir = new File(getFilesDir(), ChatActivity.INTERNAL_CHAT_DIR);
            File json = new File(dir, baseName + ".json");
            if (json.exists()) json.delete();
        } catch (Exception e) {
            Log.w(TAG, "deleteInternalJsonSidecar", e);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
