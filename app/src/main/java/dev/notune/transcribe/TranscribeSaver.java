package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Saves a transcript (.txt) and/or a compressed audio recording (.ogg/Opus)
 * to disk, and provides a shareable content:// URI via FileProvider.
 *
 * Storage strategy:
 *  1. Custom folder chosen by the user (SAF / DocumentFile)
 *  2. App-internal fallback: getFilesDir()/recordings/transkript/
 *
 * Audio is encoded to Opus by OpusEncoder before writing.
 */
public final class TranscribeSaver {

    private static final String TAG         = "TranscribeSaver";
    private static final String DEFAULT_DIR = "recordings/transkript";
    private static final String AUTHORITY   = "dev.notune.transcribe.fileprovider";

    private TranscribeSaver() {}

    /**
     * Rich save result: display name, shareable content URI (for opening/sharing),
     * human-readable location string (for UI), and whether an error occurred.
     */
    public static final class SaveResult {
        public final String displayName;
        public final Uri    shareUri;
        public final String location;
        public final String errorMessage;

        private SaveResult(String displayName, Uri shareUri, String location, String err) {
            this.displayName  = displayName;
            this.shareUri     = shareUri;
            this.location     = location;
            this.errorMessage = err;
        }
        public static SaveResult ok(String name, Uri uri, String location) {
            return new SaveResult(name, uri, location, null);
        }
        public static SaveResult error(String msg) {
            return new SaveResult(null, null, null, msg);
        }
        public boolean isSuccess() { return errorMessage == null; }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Saves transcript text as UTF-8 .txt. Returns display name or null. */
    public static String saveTranscript(Context ctx, String text) {
        SaveResult r = saveTranscriptRich(ctx, text);
        return r.isSuccess() ? r.displayName : null;
    }

    /**
     * Saves transcript text as UTF-8 .txt, returning rich result
     * (display name, shareable URI, human-readable location, or error message).
     */
    public static SaveResult saveTranscriptRich(Context ctx, String text) {
        String baseName = FileNameHelper.buildBaseName(text);
        String fileName = baseName + ".txt";
        byte[] bytes    = text.getBytes(StandardCharsets.UTF_8);
        try {
            Uri folderUri = getCustomFolderUri(ctx);
            if (folderUri != null) {
                Uri docUri = writeToDocumentFolderUri(ctx, folderUri, fileName, "text/plain", bytes);
                String loc = "Ordner: " + folderUri.getLastPathSegment();
                return SaveResult.ok(fileName, docUri, loc);
            } else {
                File f = writeToInternalStorageFile(ctx, fileName, bytes);
                Uri shareUri = FileProvider.getUriForFile(ctx, AUTHORITY, f);
                String loc = "App-Speicher: " + f.getAbsolutePath();
                return SaveResult.ok(fileName, shareUri, loc);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save transcript", e);
            String msg = e.getMessage();
            if (msg == null) msg = e.getClass().getSimpleName();
            return SaveResult.error(msg);
        }
    }

    /**
     * Encodes raw PCM bytes to Opus (.ogg) and saves.
     * The base name is derived from transcriptText so .txt and .ogg share the same name.
     * Returns display name or null.
     */
    public static String saveRecording(Context ctx, String transcriptText, byte[] pcm16) {
        String baseName = FileNameHelper.buildBaseName(transcriptText);
        String fileName = baseName + ".ogg";

        // Encode PCM -> Opus in a temp file first
        File cacheDir  = ctx.getCacheDir();
        File opusFile  = OpusEncoder.encode(cacheDir, baseName, pcm16);
        if (opusFile == null) {
            Log.e(TAG, "Opus encoding failed, aborting save");
            return null;
        }

        try {
            byte[] opusBytes = readFile(opusFile);
            opusFile.delete();

            Uri folderUri = getCustomFolderUri(ctx);
            if (folderUri != null)
                return writeToDocumentFolder(ctx, folderUri, fileName, "audio/ogg", opusBytes);
            else
                return writeToInternalStorage(ctx, fileName, opusBytes);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save recording", e);
            return null;
        }
    }

    /**
     * Returns a shareable content:// URI for an internally saved file
     * (works with FileProvider declared in AndroidManifest).
     */
    public static Uri getShareableUri(Context ctx, String fileName) {
        File file = new File(new File(ctx.getFilesDir(), DEFAULT_DIR), fileName);
        if (!file.exists()) return null;
        return FileProvider.getUriForFile(ctx, AUTHORITY, file);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static Uri getCustomFolderUri(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(
                SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String s = prefs.getString(SettingsActivity.KEY_SAVE_FOLDER_URI, null);
        if (s == null) return null;
        try { return Uri.parse(s); } catch (Exception e) { return null; }
    }

    private static String writeToDocumentFolder(
            Context ctx, Uri treeUri, String fileName, String mime, byte[] data)
            throws IOException {
        writeToDocumentFolderUri(ctx, treeUri, fileName, mime, data);
        return fileName;
    }

    private static Uri writeToDocumentFolderUri(
            Context ctx, Uri treeUri, String fileName, String mime, byte[] data)
            throws IOException {
        DocumentFile dir = DocumentFile.fromTreeUri(ctx, treeUri);
        if (dir == null || !dir.canWrite())
            throw new IOException("Cannot write to folder: " + treeUri);
        DocumentFile existing = dir.findFile(fileName);
        if (existing != null) existing.delete();
        DocumentFile newFile = dir.createFile(mime, fileName);
        if (newFile == null) throw new IOException("Could not create: " + fileName);
        try (OutputStream out = ctx.getContentResolver().openOutputStream(newFile.getUri())) {
            if (out == null) throw new IOException("No output stream for: " + fileName);
            out.write(data);
        }
        return newFile.getUri();
    }

    private static String writeToInternalStorage(Context ctx, String fileName, byte[] data)
            throws IOException {
        writeToInternalStorageFile(ctx, fileName, data);
        return fileName;
    }

    private static File writeToInternalStorageFile(Context ctx, String fileName, byte[] data)
            throws IOException {
        File dir = new File(ctx.getFilesDir(), DEFAULT_DIR);
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Cannot create dir: " + dir);
        File f = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    private static byte[] readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return buf;
    }
}
