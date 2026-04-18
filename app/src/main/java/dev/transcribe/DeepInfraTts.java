package dev.transcribe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DeepInfraTts {

    public interface StatusListener {
        void onStatus(String message);
    }

    public interface CompletionCallback {
        void onDone();
    }

    private static final String TAG = "DeepInfraTts";
    private static final String TTS_URL =
        "https://api.deepinfra.com/v1/inference/ResembleAI/chatterbox-multilingual";

    private static final int MAX_CHUNK_CHARS = 150;
    private static final int MAX_ATTEMPTS = 3;

    private final Context context;
    private final StatusListener statusListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DeepInfraTts(Context context, StatusListener statusListener) {
        this.context = context.getApplicationContext();
        this.statusListener = statusListener;
    }

    private void postStatus(String message) {
        if (statusListener != null) {
            mainHandler.post(() -> statusListener.onStatus(message));
        }
    }

    public void speak(
            String text,
            String apiKey,
            String voiceId,
            String language,
            CompletionCallback onDone) {
        new Thread(() -> {
            try {
                List<String> chunks = splitIntoChunks(text, language);
                for (int i = 0; i < chunks.size(); i++) {
                    postStatus("Sprachausgabe " + (i + 1) + "/" + chunks.size() + " …");
                    byte[] wav = synthesizeWithRetry(chunks.get(i), apiKey, voiceId, language);
                    playWav(wav);
                }
            } catch (Exception e) {
                Log.e(TAG, "speak failed", e);
                postStatus("Sprachausgabe fehlgeschlagen: " + e.getMessage());
            } finally {
                mainHandler.post(onDone::onDone);
            }
        }).start();
    }

    private byte[] synthesizeWithRetry(
            String text,
            String apiKey,
            String voiceId,
            String language) throws Exception {
        double minDurationSeconds = Math.max(1.0, text.length() * 0.04);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            byte[] wav = synthesize(text, apiKey, voiceId, language);
            double duration = wavDurationSeconds(wav);
            if (duration >= minDurationSeconds) {
                return wav;
            }
            Log.w(TAG, "TTS audio too short on attempt " + attempt
                + ": " + duration + "s for " + text.length() + " chars");
            postStatus("Sprachausgabe wiederholt Chunk " + attempt + "/" + MAX_ATTEMPTS + " …");
        }
        throw new Exception("TTS nach " + MAX_ATTEMPTS + " Versuchen zu kurz");
    }

    private byte[] synthesize(
            String text,
            String apiKey,
            String voiceId,
            String language) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("text", text);
        payload.put("voice_id", voiceId);
        payload.put("language", sanitizeLanguage(language));
        payload.put("response_format", "wav");
        payload.put("service_tier", "priority");
        payload.put("exaggeration", 0.4);
        payload.put("cfg", 0.3);
        payload.put("temperature", 0.7);
        payload.put("top_p", 0.95);
        payload.put("min_p", 0.0);
        payload.put("repetition_penalty", 1.2);
        payload.put("top_k", 1000);

        String responseText = httpPost(TTS_URL, apiKey, payload.toString());
        String audioField = new JSONObject(responseText).getString("audio");
        int comma = audioField.indexOf(',');
        String encoded = comma >= 0 ? audioField.substring(comma + 1) : audioField;
        return Base64.decode(encoded, Base64.DEFAULT);
    }

    private String httpPost(String urlStr, String apiKey, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(180_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + readStream(conn.getErrorStream()));
        }
        return readStream(conn.getInputStream());
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        return sb.toString();
    }

    private List<String> splitIntoChunks(String text, String language) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        List<String> chunks = new ArrayList<>();
        if (normalized.isEmpty()) return chunks;

        BreakIterator iterator = BreakIterator.getSentenceInstance(localeForLanguage(language));
        iterator.setText(normalized);
        StringBuilder current = new StringBuilder();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String sentence = normalized.substring(start, end).trim();
            if (sentence.isEmpty()) continue;
            if (sentence.length() > MAX_CHUNK_CHARS) {
                flushChunk(chunks, current);
                splitLongText(chunks, sentence);
            } else if (current.length() == 0) {
                current.append(sentence);
            } else if (current.length() + 1 + sentence.length() <= MAX_CHUNK_CHARS) {
                current.append(' ').append(sentence);
            } else {
                flushChunk(chunks, current);
                current.append(sentence);
            }
        }
        flushChunk(chunks, current);
        if (chunks.isEmpty()) splitLongText(chunks, normalized);
        return chunks;
    }

    private void flushChunk(List<String> chunks, StringBuilder current) {
        if (current.length() == 0) return;
        chunks.add(current.toString());
        current.setLength(0);
    }

    private void splitLongText(List<String> chunks, String text) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
            if (end < text.length()) {
                int whitespace = text.lastIndexOf(' ', end);
                if (whitespace > start + 40) end = whitespace;
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
    }

    private String sanitizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) return "de";
        String lang = language.trim().toLowerCase(Locale.ROOT);
        int dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash);
        return lang;
    }

    private Locale localeForLanguage(String language) {
        String lang = sanitizeLanguage(language);
        if ("de".equals(lang)) return Locale.GERMAN;
        if ("en".equals(lang)) return Locale.ENGLISH;
        if ("fr".equals(lang)) return Locale.FRENCH;
        if ("it".equals(lang)) return Locale.ITALIAN;
        return Locale.forLanguageTag(lang);
    }

    private double wavDurationSeconds(byte[] wav) throws Exception {
        WavInfo info = parseWavInfo(wav);
        int bytesPerSampleFrame = info.channels * (info.bitsPerSample / 8);
        if (info.sampleRate <= 0 || bytesPerSampleFrame <= 0) return 0.0;
        return (double) info.dataSize / (double) (info.sampleRate * bytesPerSampleFrame);
    }

    private void playWav(byte[] wav) throws Exception {
        WavInfo info = parseWavInfo(wav);
        if (info.audioFormat != 1 || info.bitsPerSample != 16) {
            throw new Exception("Nicht unterstütztes WAV-Format");
        }

        int channelMask = info.channels == 1
            ? AudioFormat.CHANNEL_OUT_MONO
            : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(
            info.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, 16384);

        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(info.sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new Exception("AudioTrack konnte nicht initialisiert werden");
        }

        track.play();
        int offset = 0;
        while (offset < info.dataSize) {
            int chunk = Math.min(4096, info.dataSize - offset);
            int written = track.write(wav, info.dataOffset + offset, chunk);
            if (written < 0) {
                track.release();
                throw new Exception("AudioTrack.write Fehler: " + written);
            }
            offset += written;
        }

        try {
            long durationMs = (long) (wavDurationSeconds(wav) * 1000.0) + 200;
            Thread.sleep(durationMs);
        } catch (InterruptedException ignored) {}

        track.stop();
        track.release();
    }

    private WavInfo parseWavInfo(byte[] wav) throws Exception {
        if (wav.length < 44
                || !"RIFF".equals(ascii(wav, 0, 4))
                || !"WAVE".equals(ascii(wav, 8, 4))) {
            throw new Exception("Keine WAV-Audiodaten");
        }

        WavInfo info = new WavInfo();
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, pos, 4);
            int size = le32(wav, pos + 4);
            int dataStart = pos + 8;
            if (size < 0 || dataStart + size > wav.length) {
                throw new Exception("Ungültiger WAV-Chunk");
            }

            if ("fmt ".equals(id)) {
                if (size < 16) throw new Exception("Ungültiger WAV-fmt-Chunk");
                info.audioFormat = le16(wav, dataStart);
                info.channels = le16(wav, dataStart + 2);
                info.sampleRate = le32(wav, dataStart + 4);
                info.bitsPerSample = le16(wav, dataStart + 14);
            } else if ("data".equals(id)) {
                info.dataOffset = dataStart;
                info.dataSize = size;
            }

            pos = dataStart + size + (size % 2);
        }

        if (info.sampleRate <= 0 || info.channels <= 0 || info.dataSize <= 0) {
            throw new Exception("Unvollständige WAV-Audiodaten");
        }
        return info;
    }

    private String ascii(byte[] data, int offset, int len) {
        return new String(data, offset, len, StandardCharsets.US_ASCII);
    }

    private int le16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private int le32(byte[] data, int offset) {
        return (data[offset] & 0xff)
            | ((data[offset + 1] & 0xff) << 8)
            | ((data[offset + 2] & 0xff) << 16)
            | ((data[offset + 3] & 0xff) << 24);
    }

    public void release() {
        // No persistent model or network resource to release.
    }

    private static class WavInfo {
        int audioFormat;
        int channels;
        int sampleRate;
        int bitsPerSample;
        int dataOffset;
        int dataSize;
    }
}
