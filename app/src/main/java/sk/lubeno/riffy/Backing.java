package sk.lubeno.riffy;

import java.util.HashMap;
import java.util.Map;

/**
 * Sprievod: postup akordov v tónine, ktorý k bicím hrá basa (základný tón akordu v rytme kopáka)
 * a klavír (akord na začiatku každého taktu).
 */
final class Backing {
    static final int MAJOR = 0, MINOR = 1, SEVENTH = 2;
    static final String[] SUFFIX = {"", "m", "7"};

    /** Hotové postupy: názov a akordy ako {posun od tóniky, druh}; pri blues je akord na takt. */
    static String[] PRESETS = {"I–V–vi–IV (pop)", "vi–IV–I–V (balada)", "I–♭VII–IV (rock)",
            "12-taktový blues", "i–VI–III–VII (mol)", "i–iv–v (mol)", "i–VII–VI–V (andalúzska)"};
    static final int[][][] PRESET_CHORDS = {
            {{0, MAJOR}, {7, MAJOR}, {9, MINOR}, {5, MAJOR}},
            {{9, MINOR}, {5, MAJOR}, {0, MAJOR}, {7, MAJOR}},
            {{0, MAJOR}, {10, MAJOR}, {5, MAJOR}, {0, MAJOR}},
            {{0, SEVENTH}, {0, SEVENTH}, {0, SEVENTH}, {0, SEVENTH}, {5, SEVENTH}, {5, SEVENTH},
                    {0, SEVENTH}, {0, SEVENTH}, {7, SEVENTH}, {5, SEVENTH}, {0, SEVENTH}, {7, SEVENTH}},
            {{0, MINOR}, {8, MAJOR}, {3, MAJOR}, {10, MAJOR}},
            {{0, MINOR}, {5, MINOR}, {7, MINOR}, {0, MINOR}},
            {{0, MINOR}, {10, MAJOR}, {8, MAJOR}, {7, MAJOR}},
    };
    /** Pre ktorú tóninu je postup písaný (mol / dur); pri výbere sa podľa toho prepne tónina. */
    static final boolean[] PRESET_MINOR = {false, false, false, false, true, true, true};

    final int key;
    /** Základné tóny (triedy 0–11) a druhy akordov. */
    final int[] roots, kinds;
    final int barsPerChord;
    final boolean bass, keys;

    Backing(int key, int[][] chords, int barsPerChord, boolean bass, boolean keys) {
        this.key = key;
        roots = new int[chords.length];
        kinds = new int[chords.length];
        for (int i = 0; i < chords.length; i++) {
            roots[i] = Math.floorMod(key + chords[i][0], 12);
            kinds[i] = chords[i][1];
        }
        this.barsPerChord = Math.max(1, barsPerChord);
        this.bass = bass;
        this.keys = keys;
    }

    int chordAt(long bar) {
        if (roots.length == 0 || bar < 0) return -1;
        return (int) ((bar / barsPerChord) % roots.length);
    }

    static String name(int root, int kind) {
        return Scales.name(root) + SUFFIX[kind];
    }

    /** Tóny akordu (triedy). */
    static boolean[] tones(int root, int kind) {
        boolean[] t = new boolean[12];
        t[root] = true;
        t[Math.floorMod(root + (kind == MINOR ? 3 : 4), 12)] = true;
        t[Math.floorMod(root + 7, 12)] = true;
        if (kind == SEVENTH) t[Math.floorMod(root + 10, 12)] = true;
        return t;
    }

    /** Postup ako text na uloženie: „0M,7M,9m,5M“. */
    static String encode(int[][] chords) {
        StringBuilder b = new StringBuilder();
        for (int[] c : chords) b.append(b.length() == 0 ? "" : ",").append(c[0]).append("Mm7".charAt(c[1]));
        return b.toString();
    }

    static int[][] decode(String s) {
        if (s == null || s.trim().isEmpty()) return PRESET_CHORDS[0];
        String[] parts = s.split(",");
        int[][] out = new int[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            out[i] = new int[]{Integer.parseInt(p.substring(0, p.length() - 1)), "Mm7".indexOf(p.charAt(p.length() - 1))};
        }
        return out;
    }

    /** Akordy, ktoré do tóniny patria (na výber pri úprave postupu): {posun, druh}. */
    static int[][] diatonic(boolean minor) {
        return minor
                ? new int[][]{{0, MINOR}, {3, MAJOR}, {5, MINOR}, {7, MINOR}, {7, SEVENTH}, {8, MAJOR}, {10, MAJOR}}
                : new int[][]{{0, MAJOR}, {2, MINOR}, {4, MINOR}, {5, MAJOR}, {7, MAJOR}, {7, SEVENTH}, {9, MINOR}, {10, MAJOR}};
    }

    // ---- Zvuky ----
    // Basa: Growlybass od Karoryfer Samples (Squier Jazz Bass, prstami), klavír: Kawai z Versilian
    // Community Sample Library – obe CC0. Nahraté sú tóny po 2–3 poltónoch, ostatné sa dopočítajú
    // posunutím výšky (prehraním rýchlejšie alebo pomalšie).

    static final int[] BASS_NOTES = {25, 28, 30, 33, 36, 39};
    static final int[] PIANO_NOTES = {54, 58, 60, 62, 64, 66, 68, 70, 72, 75, 78};
    private static final int[] BASS_RAW = {R.raw.b25, R.raw.b28, R.raw.b30, R.raw.b33, R.raw.b36, R.raw.b39};
    private static final int[] PIANO_RAW = {R.raw.p54, R.raw.p58, R.raw.p60, R.raw.p62, R.raw.p64, R.raw.p66,
            R.raw.p68, R.raw.p70, R.raw.p72, R.raw.p75, R.raw.p78};
    private static short[][] bassSamples, pianoSamples;
    private static final Map<Integer, short[]> BASS = new HashMap<>(), KEYS = new HashMap<>();
    static final int RATE = Drums.RATE;

    /** Načíta nahraté tóny (volá sa spolu s bicími). */
    static synchronized void load(android.content.Context c) {
        if (bassSamples != null) return;
        bassSamples = read(c, BASS_RAW);
        pianoSamples = read(c, PIANO_RAW);
    }

    private static short[][] read(android.content.Context c, int[] ids) {
        short[][] out = new short[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            try (java.io.InputStream in = c.getResources().openRawResource(ids[i])) {
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) all.write(b, 0, n);
                out[i] = Drums.toShorts(all.toByteArray());
            } catch (java.io.IOException e) {
                out[i] = new short[0];
            }
        }
        return out;
    }

    /** Pre testy: nahraté tóny bez Androidu. */
    static synchronized void use(short[][] bass, short[][] piano) {
        bassSamples = bass;
        pianoSamples = piano;
        BASS.clear();
        KEYS.clear();
    }

    /** Tón midi z najbližšieho nahratého (notes/samples), posunutý prehraním inou rýchlosťou. */
    static short[] shifted(int midi, int[] notes, short[][] samples) {
        int best = 0;
        for (int i = 1; i < notes.length; i++) if (Math.abs(notes[i] - midi) < Math.abs(notes[best] - midi)) best = i;
        short[] src = samples[best];
        double step = Math.pow(2, (midi - notes[best]) / 12.0);
        int n = (int) ((src.length - 1) / step);
        short[] out = new short[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) {
            double at = i * step;
            int k = (int) at;
            double f = at - k;
            out[i] = (short) Math.round(src[k] + (src[k + 1] - src[k]) * f);
        }
        return out;
    }

    /** Basový tón okolo E1–D#2 pre triedu tónu pc. */
    static int bassMidi(int pc) {
        return 28 + Math.floorMod(pc - 28, 12);
    }

    static synchronized short[] bass(int midi) {
        short[] s = BASS.get(midi);
        if (s != null) return s;
        s = bassSamples != null ? shifted(midi, BASS_NOTES, bassSamples) : synthBass(midi);
        BASS.put(midi, s);
        return s;
    }

    static synchronized short[] key(int midi) {
        short[] s = KEYS.get(midi);
        if (s != null) return s;
        s = pianoSamples != null ? shifted(midi, PIANO_NOTES, pianoSamples) : synthKey(midi);
        KEYS.put(midi, s);
        return s;
    }

    /** Náhradný (vypočítaný) basový tón, keď nahraté nie sú k dispozícii (testy). */
    private static short[] synthBass(int midi) {
        double f = 440 * Math.pow(2, (midi - 69) / 12.0);
        int n = RATE;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            s[i] = (short) (Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 3) * 0.8 * 32767);
        }
        return s;
    }

    private static short[] synthKey(int midi) {
        double f = 440 * Math.pow(2, (midi - 69) / 12.0);
        int n = RATE;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            s[i] = (short) (Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 2) * 0.5 * 32767);
        }
        return s;
    }

    /** Tóny akordu pre klávesy okolo stredného C (C4 = 60). */
    static int[] voicing(int root, int kind) {
        int r = 55 + Math.floorMod(root - 55, 12);
        int third = r + (kind == MINOR ? 3 : 4), fifth = r + 7;
        return kind == SEVENTH ? new int[]{r, third, fifth, r + 10} : new int[]{r, third, fifth};
    }
}
