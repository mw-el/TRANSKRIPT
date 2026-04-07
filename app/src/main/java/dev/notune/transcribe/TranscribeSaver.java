package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Saves a transcript (and optionally a WAV recording) to disk.
 *
 * Storage strategy:
 *  1. If the user has chosen a custom folder via SettingsActivity, write there
 *     using the Storage Access Framework (DocumentFile).
 *  2. Otherwise fall back to app-internal storage:
 *     {@code getFilesDir()/recordings/transkript/}
 *
 * Both the .txt transcript and the .wav recording share the same base name
 * produced by {@link FileNameHelper}.
 */
public final class TranscribeSaver {

    private static final String TAG = "TranscribeSaver";
    private static final String DEFAULT_SUBDIR = "recordings/transkript";

    private TranscribeSaver() {}

    /**
     * Saves {@code text} as a UTF-8 .txt file.
     *
     * @return the file's display name, or null on failure.
     */
    public static String saveTranscript(Context ctx, String text) {
        String baseName = FileNameHelper.buildBaseName(text);
        String fileName = baseName + ".txt";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        try {
            Uri folderUri = getCustomFolderUri(ctx);
            if (folderUri != null) {
                return writeToDocumentFolder(ctx, folderUri, fileName, "text/plain", bytes);
            } else {
                return writeToInternalStorage(ctx, fileName, bytes);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save transcript", e);
            return null;
        }
    }

    /**
     * Saves raw {@code wavBytes} as a .wav file with a name derived from
     * {@code transcriptText} (so transcript and audio share the same base name).
     *
     * @return the file's display name, or null on failure.
     */
    public static String saveRecording(Context ctx, String transcriptText, byte[] wavBytes) {
        String baseName = FileNameHelper.buildBaseName(transcriptText);
        String fileName = baseName + ".wav";

        try {
            Uri folderUri = getCustomFolderUri(ctx);
            if (folderUri != null) {
                return writeToDocumentFolder(ctx, folderUri, fileName, "audio/wav", wavBytes);
            } else {
                return writeToInternalStorage(ctx, fileName, wavBytes);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save recording", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static Uri getCustomFolderUri(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(SettingsActivity.KEY_SAVE_FOLDER_URI, null);
        if (uriString == null) return null;
        try {
            return Uri.parse(uriString);
        } catch (Exception e) {
            return null;
        }
    }

    /** Write to a user-chosen folder via Storage Access Framework. */
    private static String writeToDocumentFolder(
            Context ctx, Uri treeUri, String fileName, String mimeType, byte[] data)
            throws IOException {

        DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
        if (dir == null || !dir.canWrite()) {
            throw new IOException("Cannot write to chosen folder: " + treeUri);
        }

        // Delete existing file with the same name to avoid duplicates
        DocumentFile existing = dir.findFile(fileName);
        if (existing != null) existing.delete();

        DocumentFile newFile = dir.createFile(mimeType, fileName);
        if (newFile == null) throw new IOException("Could not create file: " + fileName);

        try (OutputStream out = ctx.getContentResolver().openOutputStream(newFile.getUri())) {
            if (out == null) throw new IOException("Could not open output stream");
            out.write(data);
        }

        return fileName;
    }

    /** Write to app-internal storage (recordings/transkript sub-directory). */
    private static String writeToInternalStorage(Context ctx, String fileName, byte[] data)
            throws IOException {

        File dir = new File(ctx.getFilesDir(), DEFAULT_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create directory: " + dir);
        }

        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }

        Log.i(TAG, "Saved internally: " + file.getAbsolutePath());
        return fileName;
    }
}
