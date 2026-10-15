package sk.lubeno.riffy;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;

/** Vzhľad (podľa telefónu, tmavý, svetlý) a farba zvýraznenia zvolené v Nastaveniach. */
final class Look {
    static final String[] THEMES = {"podľa telefónu", "tmavý", "svetlý"};
    static final String[] ACCENTS = {"modrá", "zelená", "oranžová", "fialová", "ružová", "podľa tapety"};
    private static final int[] ACCENT_STYLES = {R.style.Accent_Blue, R.style.Accent_Green, R.style.Accent_Orange,
            R.style.Accent_Purple, R.style.Accent_Pink, 0};

    private Look() {
    }

    /** Volať pred setContentView. */
    static void apply(Activity a, SharedPreferences prefs) {
        int theme = prefs.getInt("theme", 0);
        a.setTheme(theme == 1 ? R.style.Riffy_Dark : theme == 2 ? R.style.Riffy_Light : R.style.Riffy);
        int accent = ACCENT_STYLES[Math.max(0, Math.min(prefs.getInt("accent", 0), ACCENT_STYLES.length - 1))];
        if (accent != 0) a.getTheme().applyStyle(accent, true);
    }

    /** Farba z aktuálneho vzhľadu, napr. android.R.attr.colorAccent. */
    static int color(Context c, int attr) {
        TypedArray t = c.obtainStyledAttributes(new int[]{attr});
        try {
            android.content.res.ColorStateList list = t.getColorStateList(0);
            return list == null ? 0xFF808080 : list.getDefaultColor();
        } finally {
            t.recycle();
        }
    }

    static int accent(Context c) {
        return color(c, android.R.attr.colorAccent);
    }

    static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
