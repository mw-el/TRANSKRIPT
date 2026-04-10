package dev.transcribe;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

public class AudioFocusPauser {
    private AudioFocusRequest focusRequest = null;

    public void request(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            abandon(ctx);
            focusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).build();
            am.requestAudioFocus(focusRequest);
        } catch (Exception ignored) { }
    }

    public void abandon(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (focusRequest != null) {
                am.abandonAudioFocusRequest(focusRequest);
            }
            focusRequest = null;
        } catch (Exception ignored) { }
    }
}
