package sk.lubeno.riffy;

import java.util.Locale;

/**
 * Ladička: z prúdu vzoriek odhadne výšku tónu (algoritmus YIN) a vypočíta najbližšiu notu
 * a odchýlku v centoch. Rozsah od 50 Hz (podladená gitara) po 1500 Hz.
 */
final class Tuner {
    static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    static final int WINDOW = 2048;

    /** Výsledok jedného merania. */
    static final class Pitch {
        final double hz, cents;
        final int midi;

        Pitch(double hz) {
            this.hz = hz;
            double m = 69 + 12 * Math.log(hz / 440.0) / Math.log(2);
            midi = (int) Math.round(m);
            cents = (m - midi) * 100;
        }

        String note() {
            return NAMES[Math.floorMod(midi, 12)] + (midi / 12 - 1);
        }

        String describe() {
            return String.format(Locale.ROOT, "%s   %+.0f c   %.1f Hz", note(), cents, hz);
        }
    }

    interface Listener {
        /** pitch je null, keď nie je počuť jasný tón. */
        void onPitch(Pitch pitch);
    }

    private final float[] buf = new float[WINDOW * 2];
    private int filled;
    volatile Listener listener;

    /** Prijme blok vzoriek zo vstupu (32-bitové, kanály striedavo). */
    void feed(int[] samples, int n, int channels, int rate) {
        for (int i = 0; i + channels <= n; i += channels) {
            long sum = 0;
            for (int c = 0; c < channels; c++) sum += samples[i + c];
            buf[filled++] = (float) (sum / (double) channels / 2147483648.0);
            if (filled == buf.length) {
                Listener l = listener;
                if (l != null) l.onPitch(detect(buf, rate));
                // Polovica okna sa ponechá, merania sa prekrývajú.
                System.arraycopy(buf, WINDOW, buf, 0, WINDOW);
                filled = WINDOW;
            }
        }
    }

    /** YIN nad dvomi oknami vzoriek; null pri tichu alebo nejasnom tóne. */
    static Pitch detect(float[] x, int rate) {
        int w = x.length / 2;
        double energy = 0;
        for (int i = 0; i < w; i++) energy += x[i] * x[i];
        if (Math.sqrt(energy / w) < 0.003) return null;  // asi -50 dB: ticho

        int minTau = Math.max(2, rate / 1500), maxTau = Math.min(w - 1, rate / 50);
        double[] d = new double[maxTau + 1];
        for (int tau = 1; tau <= maxTau; tau++) {
            double s = 0;
            for (int j = 0; j < w; j++) {
                double diff = x[j] - x[j + tau];
                s += diff * diff;
            }
            d[tau] = s;
        }
        // Normalizovaný rozdiel (cumulative mean normalized difference).
        double running = 0;
        double[] cm = new double[maxTau + 1];
        cm[0] = 1;
        for (int tau = 1; tau <= maxTau; tau++) {
            running += d[tau];
            cm[tau] = running == 0 ? 1 : d[tau] * tau / running;
        }
        int best = -1;
        for (int tau = minTau; tau <= maxTau; tau++) {
            if (cm[tau] < 0.15) {
                while (tau + 1 <= maxTau && cm[tau + 1] < cm[tau]) tau++;
                best = tau;
                break;
            }
        }
        if (best < 0) return null;
        // Spresnenie medzi susednými hodnotami (parabola).
        double t = best;
        if (best > 1 && best < maxTau) {
            double a = cm[best - 1], b = cm[best], c = cm[best + 1];
            double den = a - 2 * b + c;
            if (den != 0) t = best + (a - c) / (2 * den);
        }
        return new Pitch(rate / t);
    }
}
