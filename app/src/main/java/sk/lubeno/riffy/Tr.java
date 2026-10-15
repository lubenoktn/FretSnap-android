package sk.lubeno.riffy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Jazyk aplikácie (podľa telefónu, slovenčina, angličtina) a texty z res/values*: angličtina je
 * predvolená, slovenčina v values-sk. Triedy bez Contextu berú texty cez {@link #s}.
 */
final class Tr {
    static final String[] LANGS = {"auto", "sk", "en"};
    /** Texty v zvolenom jazyku; v testoch (bez Androidu) null. */
    static volatile Resources res;
    /** Angličtina: tón H sa volá B. */
    static volatile boolean en;

    private Tr() {
    }

    /** Jazyk podľa voľby v nastaveniach (0 = podľa telefónu); slovenský a český telefón dostane slovenčinu. */
    static Locale locale(SharedPreferences prefs) {
        int choice = prefs.getInt("lang", 0);
        if (choice == 1) return new Locale("sk");
        if (choice == 2) return Locale.ENGLISH;
        String phone = Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage();
        return phone.equals("sk") || phone.equals("cs") ? new Locale("sk") : Locale.ENGLISH;
    }

    /** Context v zvolenom jazyku (pre attachBaseContext). */
    static Context wrap(Context base) {
        Locale l = locale(base.getSharedPreferences("nastavenia", Context.MODE_PRIVATE));
        Locale.setDefault(l);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(l);
        Context ctx = base.createConfigurationContext(c);
        init(ctx.getResources(), l.getLanguage().equals("en"));
        return ctx;
    }

    static void init(Resources r, boolean english) {
        res = r;
        en = english;
        String h = english ? "B" : "H";
        Scales.ROOTS[11] = h;
        Tuner.NAMES[11] = h;
        Scales.NAMES = r.getStringArray(R.array.scale_names);
        Tunings.NAMES = r.getStringArray(R.array.tuning_names);
        Backing.PRESETS = r.getStringArray(R.array.backing_presets);
        Drums.FILLS = r.getStringArray(R.array.drum_fills);
        Drums.NAMES = r.getStringArray(R.array.drum_styles);
        Look.THEMES = r.getStringArray(R.array.themes);
        Look.ACCENTS = r.getStringArray(R.array.accents);
        MainActivity.SOURCE_NAMES = r.getStringArray(R.array.sources);
        MainActivity.CHANNEL_NAMES = r.getStringArray(R.array.channel_names);
    }

    /** Text; v testoch bez Androidu prázdny. */
    static String s(int id, Object... args) {
        Resources r = res;
        if (r == null) return "";
        return args.length == 0 ? r.getString(id) : r.getString(id, args);
    }
}
