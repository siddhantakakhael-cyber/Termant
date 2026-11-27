package com.termant.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Three palettes: dark (default), light and ember. Picked from Settings →
 * Theme, which goes dark, light, ember, dark... Screens read the colours
 * when they are built, so the activity rebuilds itself after a switch.
 */
final class Theme {
    private Theme() {
    }

    static final int[] DARK = {
            0xff0d1117, // BG
            0xff151b23, // SURFACE
            0xff212830, // SURFACE_HIGH
            0xffe6edf3, // TEXT
            0xff8b949e, // MUTED
            0xffff7a45, // ACCENT
            0xff1b0d05, // ON_ACCENT
            0xffffa198, // ERROR
            0xffc9d1d9, // KEY_TEXT
            0xff79c0ff, // CODE
            0x33ffffff, // RIPPLE
            0xff2b323c, // OUTLINE
            0x26ff7a45, // ACCENT_SOFT
    };
    static final int[] LIGHT = {
            0xfff6f8fa, // BG
            0xffffffff, // SURFACE
            0xffeaeef2, // SURFACE_HIGH
            0xff1f2328, // TEXT
            0xff656d76, // MUTED
            0xffd25a2e, // ACCENT
            0xffffffff, // ON_ACCENT
            0xffcb2431, // ERROR
            0xff57606a, // KEY_TEXT
            0xff0550ae, // CODE
            0x24000000, // RIPPLE
            0xffd8dee4, // OUTLINE
            0x1fd25a2e, // ACCENT_SOFT
    };
    // The one that is not quiet: a warm black that glows, for the user who wants
    // the phone to feel like it is on fire while it works.
    static final int[] EMBER = {
            0xff150d09, // BG
            0xff211510, // SURFACE
            0xff2e1d15, // SURFACE_HIGH
            0xfff6ede3, // TEXT
            0xffab927d, // MUTED
            0xffff5c33, // ACCENT
            0xff2b0d02, // ON_ACCENT
            0xffff8a76, // ERROR
            0xffead9c6, // KEY_TEXT
            0xff8fd6a4, // CODE
            0x33ffffff, // RIPPLE
            0xff402a1d, // OUTLINE
            0x2aff5c33, // ACCENT_SOFT
    };

    static final int IDX_BG = 0, IDX_SURFACE = 1, IDX_SURFACE_HIGH = 2,
            IDX_TEXT = 3, IDX_MUTED = 4, IDX_ACCENT = 5, IDX_ON_ACCENT = 6,
            IDX_ERROR = 7, IDX_KEY_TEXT = 8, IDX_CODE = 9, IDX_RIPPLE = 10, IDX_OUTLINE = 11,
            IDX_ACCENT_SOFT = 12;

    private static int[] current = DARK;

    /** Reads the saved theme preference; call once early (e.g. in onCreate). */
    static void apply(Context c) {
        SharedPreferences p = c.getSharedPreferences("termant", Context.MODE_PRIVATE);
        String theme = p.getString("theme", "dark");
        current = theme.equals("light") ? LIGHT : theme.equals("ember") ? EMBER : DARK;
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

    /** Hairlines: the edge of a card, the line between two rows. */
    static int outline() {
        return current[IDX_OUTLINE];
    }

    /** The accent colour thinned out, as a background for something that is chosen or important. */
    static int accentSoft() {
        return current[IDX_ACCENT_SOFT];
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
