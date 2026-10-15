package sk.lubeno.riffy;

import java.util.ArrayList;
import java.util.List;

/** Stupnice, tónina z nahrávky a polohy na hmatníku. */
final class Scales {
    /** Názvy tónov; H = anglické B (aby sa nepomýlilo so slovenským B = A#). */
    static final String[] ROOTS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "H"};
    static final String[] NAMES = {"Pentatonika mol", "Pentatonika dur", "Blues", "Prirodzená mol", "Dur",
            "Dórska", "Mixolydická", "Harmonická mol", "Frygická"};
    static final int[][] STEPS = {
            {0, 3, 5, 7, 10},
            {0, 2, 4, 7, 9},
            {0, 3, 5, 6, 7, 10},
            {0, 2, 3, 5, 7, 8, 10},
            {0, 2, 4, 5, 7, 9, 11},
            {0, 2, 3, 5, 7, 9, 10},
            {0, 2, 4, 5, 7, 9, 10},
            {0, 2, 3, 5, 7, 8, 11},
            {0, 1, 3, 5, 7, 8, 10},
    };
    /** Stupnica, ktorá sa ponúkne k tónine: mol → pentatonika mol, dur → pentatonika dur. */
    static final int MINOR_PENTA = 0, MAJOR_PENTA = 1;
    static final int FRETS = 15;

    private Scales() {
    }

    static String name(int pc) {
        return ROOTS[Math.floorMod(pc, 12)];
    }

    /** Tónina ako text: „Em“ (mol) alebo „G“ (dur). */
    static String key(int root, boolean minor) {
        return name(root) + (minor ? "m" : "");
    }

    /** Opačný smer: „Em“ → {4, 1}; „G“ → {7, 0}; „Bb“ → {10, 0}; null, ak sa nedá prečítať. */
    static int[] parseKey(String text) {
        if (text == null) return null;
        String t = text.trim().replace(" ", "").replace("mol", "m").replace("dur", "");
        if (t.isEmpty()) return null;
        int base = "C D EF G A H".indexOf(Character.toUpperCase(t.charAt(0)));
        if (Character.toUpperCase(t.charAt(0)) == 'B') base = 11;
        if (base < 0) return null;
        int i = 1;
        if (i < t.length() && t.charAt(i) == '#') {
            base++;
            i++;
        } else if (i < t.length() && t.charAt(i) == 'b') {
            base--;
            i++;
        }
        String rest = t.substring(i);
        boolean minor = rest.startsWith("m") && !rest.startsWith("maj");
        return new int[]{Math.floorMod(base, 12), minor ? 1 : 0};
    }

    /** Tóny stupnice (triedy tónov 0–11). */
    static boolean[] notes(int root, int scale) {
        boolean[] in = new boolean[12];
        for (int s : STEPS[Math.max(0, Math.min(scale, STEPS.length - 1))]) in[Math.floorMod(root + s, 12)] = true;
        return in;
    }

    static String list(int root, int scale) {
        StringBuilder b = new StringBuilder();
        for (int s : STEPS[scale]) b.append(b.length() == 0 ? "" : " ").append(name(root + s));
        return b.toString();
    }

    /**
     * Polohy: od každého tónu stupnice na najhrubšej strune (pražce 0–11) okno 5 pražcov.
     * Vráti začiatočné pražce vzostupne.
     */
    static List<Integer> positions(int[] strings, int root, int scale) {
        boolean[] in = notes(root, scale);
        List<Integer> out = new ArrayList<>();
        for (int f = 0; f < 12; f++) if (in[Math.floorMod(strings[0] + f, 12)]) out.add(f);
        return out;
    }

    // ---- Tónina z nahrávky (profily Krumhansla) ----

    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    /**
     * Koľko je v nahrávke ktorého tónu: v každom úseku len zreteľné vrcholy spektra 70–800 Hz
     * (skreslený zvuk má veľa vyšších harmonických, ktoré by tóninu pomýlili).
     */
    static double[] chroma(float[] x, int rate) {
        int win = 4096, hop = 2048;
        double[] c = new double[12];
        double[] re = new double[win], im = new double[win], mag = new double[win / 2];
        int lo = (int) Math.ceil(70.0 * win / rate), hi = (int) Math.min(win / 2 - 2, Math.floor(800.0 * win / rate));
        for (int start = 0; start + win <= x.length; start += hop) {
            double energy = 0;
            for (int i = 0; i < win; i++) {
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / win);
                re[i] = x[start + i] * w;
                im[i] = 0;
                energy += re[i] * re[i];
            }
            if (energy / win < 1e-7) continue;
            Beat.fft(re, im);
            double max = 0;
            for (int b = lo - 1; b <= hi + 1; b++) {
                mag[b] = Math.hypot(re[b], im[b]);
                if (b >= lo && b <= hi) max = Math.max(max, mag[b]);
            }
            for (int b = lo; b <= hi; b++) {
                if (mag[b] <= mag[b - 1] || mag[b] <= mag[b + 1] || mag[b] < max * 0.1) continue;
                double f = b * rate / (double) win;
                int pc = Math.floorMod((int) Math.round(69 + 12 * Math.log(f / 440) / Math.log(2)), 12);
                c[pc] += mag[b];
            }
        }
        return c;
    }

    /** Najpravdepodobnejšia tónina: {základný tón 0–11, 1 = mol / 0 = dur}; null pri tichu. */
    static int[] detectKey(double[] chroma) {
        double sum = 0;
        for (double v : chroma) sum += v;
        if (sum <= 0) return null;
        double best = -2;
        int[] key = null;
        for (int root = 0; root < 12; root++) {
            for (int m = 0; m < 2; m++) {
                double[] prof = m == 1 ? MINOR : MAJOR;
                double r = correlation(chroma, prof, root);
                if (r > best) {
                    best = r;
                    key = new int[]{root, m};
                }
            }
        }
        return key;
    }

    private static double correlation(double[] c, double[] prof, int root) {
        double mc = 0, mp = 0;
        for (int i = 0; i < 12; i++) {
            mc += c[i];
            mp += prof[i];
        }
        mc /= 12;
        mp /= 12;
        double num = 0, dc = 0, dp = 0;
        for (int i = 0; i < 12; i++) {
            double a = c[Math.floorMod(root + i, 12)] - mc, b = prof[i] - mp;
            num += a * b;
            dc += a * a;
            dp += b * b;
        }
        return num / Math.sqrt(dc * dp + 1e-12);
    }
}
