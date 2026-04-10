package dev.transcribe;

import android.app.AlertDialog;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.view.WindowManager;
import android.widget.ArrayAdapter;

/**
 * Applies language-specific post-processing to transcribed text.
 *
 * Currently handles:
 *   - de-CH: replaces ß with ss (Swiss German orthography)
 */
public final class LanguagePostProcessor {

    public static final String LANG_DE_DE = "de-DE";
    public static final String LANG_DE_CH = "de-CH";
    public static final String LANG_EN    = "en";
    public static final String LANG_FR    = "fr";
    public static final String LANG_IT    = "it";

    public static final String DEFAULT_LANGUAGE = LANG_DE_CH;

    private LanguagePostProcessor() {}

    /** Reads the saved language code from SharedPreferences. */
    public static String getLanguage(Context ctx) {
        return ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                  .getString(SettingsActivity.KEY_LANGUAGE, DEFAULT_LANGUAGE);
    }

    /**
     * Applies post-processing rules for the given language code.
     * Returns the (possibly modified) text.
     */
    public static String process(String text, String languageCode) {
        if (text == null) return null;
        if (LANG_DE_CH.equals(languageCode)) {
            text = text.replace("ß", "ss").replace("ẞ", "SS");
        }
        return text;
    }

    /** Convenience: reads language from prefs and processes in one call. */
    public static String process(Context ctx, String text) {
        return process(text, getLanguage(ctx));
    }

    /**
     * Shows an AlertDialog that lets the user pick a language.
     * Saves the selection to SharedPreferences immediately.
     * {@code onChanged} is called (on the calling thread) after the dialog is dismissed
     * with a new selection — pass {@code null} if not needed.
     */
    public static void showPicker(Context ctx, Runnable onChanged) {
        String[] codes = ctx.getResources().getStringArray(R.array.language_codes);
        String[] names = ctx.getResources().getStringArray(R.array.language_display_names);

        String current = getLanguage(ctx);
        int selected = 1; // default to de-CH index
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) { selected = i; break; }
        }

        final int[] choice = {selected};

        AlertDialog dialog = new AlertDialog.Builder(ctx, R.style.AppDialogTheme)
                .setTitle(ctx.getString(R.string.settings_language_label))
                .setSingleChoiceItems(names, selected, (dlg, which) -> choice[0] = which)
                .setPositiveButton(android.R.string.ok, (dlg, which) -> {
                    ctx.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                       .edit().putString(SettingsActivity.KEY_LANGUAGE, codes[choice[0]]).apply();
                    if (onChanged != null) onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // When called from an InputMethodService the dialog window must use
        // TYPE_INPUT_METHOD_DIALOG so the system attaches it to the IME layer.
        if (ctx instanceof InputMethodService && dialog.getWindow() != null) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
        }
        dialog.show();
    }
}