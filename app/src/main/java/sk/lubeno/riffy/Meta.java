package sk.lubeno.riffy;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Poznámka, tempo, tónina a obľúbenosť riffov. Ukladá sa v súbore aplikácie podľa mena
 * nahrávky (id v MediaStore sa po preinštalovaní mení, meno nie).
 */
final class Meta {
    static final class Info {
        String note = "", key = "";
        int bpm;
        boolean favorite;
        /** Štýl bicích, ktoré hrali pri nahrávaní („Rock“…); prázdne = žiadne. */
        String drums = "";
        /** Kde v nahrávke (ms) je prvá doba taktu; -1 = nevie sa. */
        int beat0 = -1;
        /** Doba už doladená podľa úderov v nahrávke. */
        boolean aligned;
        /** Pieseň a časť (napr. „Sloha“), poradie v piesni. */
        String song = "", part = "";
        int order;
        /** Značky v nahrávke (ms), vzostupne. */
        java.util.List<Integer> markers = new java.util.ArrayList<>();

        boolean empty() {
            return note.isEmpty() && key.isEmpty() && bpm <= 0 && !favorite && drums.isEmpty() && song.isEmpty()
                    && markers.isEmpty();
        }
    }

    private final File file;
    private JSONObject all = new JSONObject();

    Meta(Context c) {
        file = new File(c.getFilesDir(), "riffy-udaje.json");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            all = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | JSONException ignored) {
        }
    }

    Info get(String name) {
        JSONObject o = all.optJSONObject(name);
        return o == null ? new Info() : read(o);
    }

    static Info read(JSONObject o) {
        Info i = new Info();
        i.note = o.optString("note");
        i.key = o.optString("key");
        i.bpm = o.optInt("bpm");
        i.favorite = o.optBoolean("favorite");
        i.drums = o.optString("drums");
        i.beat0 = o.optInt("beat0", -1);
        i.aligned = o.optBoolean("aligned");
        i.song = o.optString("song");
        i.part = o.optString("part");
        i.order = o.optInt("order");
        org.json.JSONArray m = o.optJSONArray("markers");
        if (m != null) for (int k = 0; k < m.length(); k++) i.markers.add(m.optInt(k));
        return i;
    }

    static void write(JSONObject o, Info i) throws JSONException {
        o.put("note", i.note);
        o.put("key", i.key);
        o.put("bpm", i.bpm);
        o.put("favorite", i.favorite);
        if (!i.drums.isEmpty()) o.put("drums", i.drums);
        if (i.beat0 >= 0) o.put("beat0", i.beat0);
        if (i.aligned) o.put("aligned", true);
        if (!i.song.isEmpty()) {
            o.put("song", i.song);
            o.put("part", i.part);
            o.put("order", i.order);
        }
        if (!i.markers.isEmpty()) o.put("markers", new org.json.JSONArray(i.markers));
    }

    void put(String name, Info i) {
        try {
            if (i.empty()) {
                all.remove(name);
            } else {
                JSONObject o = new JSONObject();
                write(o, i);
                all.put(name, o);
            }
        } catch (JSONException ignored) {
        }
        save();
    }

    boolean has(String name) {
        return all.has(name);
    }

    /** Údaje do súboru WAV (vlastný blok), aby prežili preinštalovanie aj prenos do počítača. */
    static byte[] toBytes(Info i) {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "Riffy");
            write(o, i);
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    /** Údaje zo súboru WAV; null, ak tam nie sú alebo nie sú od Riffy. */
    static Info fromBytes(byte[] b) {
        if (b == null) return null;
        try {
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            if (!"Riffy".equals(o.optString("app"))) return null;
            return read(o);
        } catch (JSONException e) {
            return null;
        }
    }

    void rename(String from, String to) {
        Info i = get(from);
        all.remove(from);
        put(to, i);
    }

    void remove(String name) {
        all.remove(name);
        save();
    }

    private void save() {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(all.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
