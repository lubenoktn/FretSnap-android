package sk.lubeno.riffy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Aktualizácie z verejných vydaní (Releases) na GitHube. Aplikácia si raz za deň pozrie
 * posledné vydanie; ak je novšie, ponúkne stiahnuť APK cez prehliadač.
 */
final class Updates {
    static final String LATEST = "https://api.github.com/repos/lubenoktn/Riffy-android/releases/latest";
    static final long DAY = 24 * 60 * 60 * 1000L;

    private Updates() {
    }

    /** Porovná verzie „1.10“ a „1.9“ po číslach; kladné, ak je a novšia. */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("\\."), y = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String current(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** quiet: pri spustení, najviac raz za deň a bez hlásení, keď nie je nič nové. */
    static void check(Activity a, SharedPreferences prefs, boolean quiet) {
        long now = System.currentTimeMillis();
        if (quiet && now - prefs.getLong("updateChecked", 0) < DAY) return;
        prefs.edit().putLong("updateChecked", now).apply();
        Handler ui = new Handler(Looper.getMainLooper());
        String mine = current(a);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                if (c.getResponseCode() != 200) throw new Exception("GitHub odpovedal " + c.getResponseCode());
                JSONObject r;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    r = new JSONObject(b.toString(StandardCharsets.UTF_8.name()));
                }
                String tag = r.optString("tag_name"), notes = r.optString("body");
                String apk = null;
                JSONArray assets = r.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    String url = assets.getJSONObject(i).optString("browser_download_url");
                    if (url.endsWith(".apk")) apk = url;
                }
                String download = apk;
                if (download != null && compare(tag, mine) > 0) {
                    ui.post(() -> {
                        if (a.isFinishing()) return;
                        new AlertDialog.Builder(a)
                                .setTitle("Nová verzia " + tag.replaceFirst("^[vV]", ""))
                                .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Máte verziu " + mine
                                        + ". Po stiahnutí APK otvorte a nainštalujte cez starú verziu; nahrávky ostanú.")
                                .setPositiveButton("Stiahnuť", (d, w) ->
                                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(download))))
                                .setNegativeButton("Neskôr", null)
                                .show();
                    });
                } else if (!quiet) {
                    ui.post(() -> Toast.makeText(a, "Máte najnovšiu verziu " + mine + ".", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                if (!quiet) {
                    String msg = e.getMessage();
                    ui.post(() -> Toast.makeText(a, "Aktualizácie sa nedajú overiť: " + msg, Toast.LENGTH_LONG).show());
                }
            }
        }, "aktualizacie").start();
    }
}
