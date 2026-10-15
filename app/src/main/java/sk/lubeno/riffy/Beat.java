package sk.lubeno.riffy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Údery v nahrávke a ich poloha voči taktu. Podľa toho sa po nahratí doladí, kde v riffe
 * začína doba, aby bicie pri prehrávaní išli tak, ako sa hralo (nie podľa odhadu oneskorení).
 */
final class Beat {
    static final int WIN = 2048;

    private Beat() {
    }

    /** Mono z WAV v rozsahu rámcov [from, to). */
    static float[] mono(File f, long from, long to) throws IOException {
        return mono(new FileInputStream(f), from, to);
    }

    static int rateOf(InputStream stream) throws IOException {
        try (InputStream in = new BufferedInputStream(stream, 1024)) {
            return Wav.read(in).rate;
        }
    }

    static float[] mono(InputStream stream, long from, long to) throws IOException {
        try (InputStream in = new BufferedInputStream(stream, 1 << 16)) {
            Wav.Info info = Wav.read(in);
            int fb = info.frameBytes(), sb = info.bits / 8, shift = 32 - info.bits;
            Wav.skipFully(in, from * fb);
            int n = (int) Math.max(0, Math.min(to, info.frames()) - from);
            float[] out = new float[n];
            byte[] frame = new byte[fb];
            for (int i = 0; i < n; i++) {
                int got = 0;
                while (got < fb) {
                    int r = in.read(frame, got, fb - got);
                    if (r < 0) return Arrays.copyOf(out, i);
                    got += r;
                }
                float sum = 0;
                for (int c = 0; c < info.channels; c++) sum += (Wav.le(frame, c * sb, sb) << shift >> shift) / (float) (1L << (info.bits - 1));
                out[i] = sum / info.channels;
            }
            return out;
        }
    }

    /** Časy úderov (s): vrcholy spektrálneho toku (prírastok energie naprieč frekvenciami). */
    static double[] onsets(float[] x, int rate) {
        int hop = rate / 100;
        int frames = Math.max(0, (x.length - WIN) / hop);
        double[] flux = new double[frames];
        double[] win = new double[WIN];
        for (int i = 0; i < WIN; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / WIN);
        double[] re = new double[WIN], im = new double[WIN], prev = new double[WIN / 2 + 1];
        for (int k = 0; k < frames; k++) {
            for (int i = 0; i < WIN; i++) {
                re[i] = x[k * hop + i] * win[i];
                im[i] = 0;
            }
            fft(re, im);
            double sum = 0;
            for (int b = 0; b <= WIN / 2; b++) {
                double mag = Math.log1p(Math.hypot(re[b], im[b]) * 50);
                if (k > 0 && mag > prev[b]) sum += mag - prev[b];
                prev[b] = mag;
            }
            flux[k] = sum;
        }
        double mean = 0;
        for (double v : flux) mean += v;
        mean /= Math.max(1, frames);
        List<Integer> peaks = new ArrayList<>();
        int last = -100;
        double[] window = new double[51];
        for (int k = 2; k < frames - 2; k++) {
            int a = Math.max(0, k - 25), b = Math.min(frames, k + 25), m = b - a;
            System.arraycopy(flux, a, window, 0, m);
            Arrays.sort(window, 0, m);
            double thr = window[m / 2] * 1.5 + mean * 0.5;
            boolean peak = true;
            for (int j = k - 2; j <= k + 2; j++) if (flux[j] > flux[k]) peak = false;
            if (flux[k] > thr && peak && k - last > 7) {
                peaks.add(k);
                last = k;
            }
        }
        // Len zreteľné údery (silnejšie než 40 % ostatných); slabé bývajú doznievanie a ozveny.
        double[] strength = new double[peaks.size()];
        for (int i = 0; i < strength.length; i++) strength[i] = flux[peaks.get(i)];
        double[] sorted = strength.clone();
        Arrays.sort(sorted);
        double min = sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.4)];
        List<Double> out = new ArrayList<>();
        // Úder je v strede okna, v ktorom energia najviac pribudla, nie na jeho začiatku.
        for (int i = 0; i < strength.length; i++) {
            if (strength[i] >= min) out.add((peaks.get(i) * hop + WIN / 2 + rate * 7 / 1000) / (double) rate);
        }
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * O koľko ms posunúť mriežku osmín (od firstBeatMs, v tempe bpm), aby čo najlepšie sedela
     * s údermi; 0, ak údery nie sú dosť pravidelné na spoľahlivé posunutie.
     */
    static int shiftMs(double[] onsets, double firstBeatMs, int bpm) {
        if (onsets.length < 6 || bpm <= 0) return 0;
        double eighth = 30000.0 / bpm;
        double sx = 0, sy = 0;
        for (double t : onsets) {
            double ph = 2 * Math.PI * ((t * 1000 - firstBeatMs) / eighth);
            sx += Math.cos(ph);
            sy += Math.sin(ph);
        }
        double r = Math.hypot(sx, sy) / onsets.length;
        if (r < 0.35) return 0;
        return (int) Math.round(Math.atan2(sy, sx) / (2 * Math.PI) * eighth);
    }

    /** Ako pravidelne padajú údery na mriežku s periódou period (s), 0–1, a jej posun (s). */
    static double[] grid(double[] onsets, double period) {
        double sx = 0, sy = 0;
        for (double t : onsets) {
            double ph = 2 * Math.PI * t / period;
            sx += Math.cos(ph);
            sy += Math.sin(ph);
        }
        double r = Math.hypot(sx, sy) / Math.max(1, onsets.length);
        double shift = Math.atan2(sy, sx) / (2 * Math.PI) * period;
        return new double[]{r, Math.floorMod((long) Math.round(shift * 1e6), (long) Math.round(period * 1e6)) / 1e6};
    }

    /**
     * Tempo a prvá doba taktu z úderov: {bpm, ms prvej doby}, alebo null, ak sa nedá spoľahlivo určiť.
     * Skúša tempá 60–180 (štvrťové aj osminové údery), uprednostní bežné tempá okolo 110.
     * Prvá doba taktu = tá zo štyroch, na ktorú padá najviac úderov.
     */
    static int[] tempo(double[] onsets, int beatsPerBar) {
        if (onsets.length < 8) return null;
        double best = 0, bestBpm = 0;
        for (double bpm = 60; bpm <= 180; bpm += 0.5) {
            double score = score(onsets, bpm);
            if (score > best) {
                best = score;
                bestBpm = bpm;
            }
        }
        for (double bpm = bestBpm - 0.5; bpm <= bestBpm + 0.5; bpm += 0.05) {
            double score = score(onsets, bpm);
            if (score > best) {
                best = score;
                bestBpm = bpm;
            }
        }
        int bpm = (int) Math.round(bestBpm);
        double beat = 60.0 / bpm;
        double[] q = grid(onsets, beat);
        // Spoľahlivé, ak údery sedia na štvrťovú alebo osminovú mriežku (riffy bývajú na osminy).
        if (0.6 * q[0] + 0.4 * grid(onsets, beat / 2)[0] < 0.3) return null;
        lastScore = 0.6 * q[0] + 0.4 * grid(onsets, beat / 2)[0];
        // Poloha mriežky podľa zreteľnejšej z osmín a štvrtín; prvá doba taktu = osmina,
        // na ktorú (v takte) padne najviac úderov.
        double[] e = grid(onsets, beat / 2);
        double phase = e[0] >= q[0] ? e[1] : q[1];
        double bar = beat * beatsPerBar;
        int bestK = 0, bestCount = -1;
        for (int k = 0; k < beatsPerBar * 2; k++) {
            double first = phase + k * beat / 2;
            int count = 0;
            for (double t : onsets) {
                double rel = Math.floorMod((long) Math.round((t - first) * 1000), (long) Math.round(bar * 1000)) / 1000.0;
                if (rel < 0.06 || bar - rel < 0.06) count++;
            }
            // Pri rovnakom počte prednosť dobám pred osminami „a“
            if (count > bestCount || (count == bestCount && k % 2 == 0 && bestK % 2 == 1)) {
                bestCount = count;
                bestK = k;
            }
        }
        double firstMs = (phase + bestK * beat / 2) * 1000;
        firstMs = Math.floorMod((long) Math.round(firstMs), (long) Math.round(bar * 1000));
        return new int[]{bpm, (int) firstMs};
    }

    static double lastScore;

    private static double score(double[] onsets, double bpm) {
        double beat = 60 / bpm;
        double r4 = grid(onsets, beat)[0], r8 = grid(onsets, beat / 2)[0];
        double prior = Math.exp(-0.5 * Math.pow(Math.log(bpm / 110) / Math.log(2) / 0.6, 2));
        return (0.6 * r4 + 0.4 * r8) * prior;
    }

    /** Rýchla Fourierova transformácia na mieste (dĺžka = mocnina dvoch). */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
