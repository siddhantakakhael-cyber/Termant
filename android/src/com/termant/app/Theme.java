package com.termant.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Two palettes: dark (default) and light. Picked from Settings → Theme.
 * Screens read the colours when they are built, so the activity rebuilds
 * itself after a switch.
 */
final class Theme {
    private Theme() {
    }

    static final int[] DARK = {
            0xff0d1117, // BG
            0xff161b22, // SURFACE
            0xff262c36, // SURFACE_HIGH
            0xffe6edf3, // TEXT
            0xff8b949e, // MUTED
            0xffff7a45, // ACCENT
            0xff1b0d05, // ON_ACCENT
            0xffffa198, // ERROR
            0xffc9d1d9, // KEY_TEXT
            0xff79c0ff, // CODE
            0x40ffffff, // RIPPLE
    };
    static final int[] LIGHT = {
            0xfff6f8fa, // BG
            0xffffffff, // SURFACE
            0xffe1e4e8, // SURFACE_HIGH
            0xff24292e, // TEXT
            0xff6e7681, // MUTED
            0xffd25a2e, // ACCENT
            0xfffafafa, // ON_ACCENT
            0xffcb2431, // ERROR
            0xff57606a, // KEY_TEXT
            0xff0550ae, // CODE
            0x30000000, // RIPPLE
    };

    static final int IDX_BG = 0, IDX_SURFACE = 1, IDX_SURFACE_HIGH = 2,
            IDX_TEXT = 3, IDX_MUTED = 4, IDX_ACCENT = 5, IDX_ON_ACCENT = 6,
            IDX_ERROR = 7, IDX_KEY_TEXT = 8, IDX_CODE = 9, IDX_RIPPLE = 10;

    private static int[] current = DARK;

    /** Reads the saved theme preference; call once early (e.g. in onCreate). */
    static void apply(Context c) {
        SharedPreferences p = c.getSharedPreferences("termant", Context.MODE_PRIVATE);
        String theme = p.getString("theme", "dark");
        current = theme.equals("light") ? LIGHT : DARK;
    }

    static String currentName(Context c) {
        return c.getSharedPreferences("termant", Context.MODE_PRIVATE)
                .getString("theme", "dark");
    }

    static void set(Context c, String name) {
        c.getSharedPreferences("termant", Context.MODE_PRIVATE)
                .edit().putString("theme", name).apply();
        apply(c);
    }

    static boolean isLight() {
        return current == LIGHT;
    }

    /** The terminal's background: the app's background, in both themes. */
    static int terminalBg() {
        return current[IDX_BG];
    }

    static int code() {
        return current[IDX_CODE];
    }

    static int ripple() {
        return current[IDX_RIPPLE];
    }

    static int bg() {
        return current[IDX_BG];
    }

    static int surface() {
        return current[IDX_SURFACE];
    }

    static int surfaceHigh() {
        return current[IDX_SURFACE_HIGH];
    }

    static int text() {
        return current[IDX_TEXT];
    }

    static int muted() {
        return current[IDX_MUTED];
    }

    static int accent() {
        return current[IDX_ACCENT];
    }

    static int onAccent() {
        return current[IDX_ON_ACCENT];
    }

    static int error() {
        return current[IDX_ERROR];
    }

    static int keyText() {
        return current[IDX_KEY_TEXT];
    }
}
