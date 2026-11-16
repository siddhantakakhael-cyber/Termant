package com.termant.app;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The language of the app's own texts, picked in Settings: Hindi written in
 * Roman letters ("hi", the default) or English ("en"). It does not follow the
 * phone's language, because most phones here are left on English.
 */
final class Lang {
    private Lang() {
    }

    static final String HINDI = "hi", ENGLISH = "en";

    private static Context cached;
    private static String cachedFor;

    static String current(Context c) {
        String saved = c.getSharedPreferences("termant", Context.MODE_PRIVATE).getString("lang", HINDI);
        return ENGLISH.equals(saved) ? ENGLISH : HINDI;
    }

    static void set(Context c, String lang) {
        c.getSharedPreferences("termant", Context.MODE_PRIVATE).edit().putString("lang", lang).commit();
    }

    /** The language to listen for when the user dictates a message. */
    static String speechTag(Context c) {
        return HINDI.equals(current(c)) ? "hi-IN" : "en-IN";
    }

    /** A context whose texts are in the chosen language; for an activity's attachBaseContext. */
    static Context wrap(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(new Locale(current(base)));
        return base.createConfigurationContext(config);
    }

    /**
     * Texts in the chosen language for code that outlives a screen (the
     * service, the chat, rules), so a change in Settings reaches it at once.
     */
    static synchronized Context of(Context c) {
        String lang = current(c);
        if (cached == null || !lang.equals(cachedFor)) {
            cached = wrap(c.getApplicationContext());
            cachedFor = lang;
        }
        return cached;
    }
}
