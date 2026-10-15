package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

public class BeatTest {
    /** Brnknutia v osminách pri 100 BPM, o 30 ms neskôr než mriežka od 500 ms. */
    static float[] strums(int rate, double lateMs) {
        float[] x = new float[rate * 8];
        Random rnd = new Random(1);
        for (int k = 0; k < 26; k++) {
            int at = (int) ((0.5 + k * 0.3 + lateMs / 1000) * rate);
            double f = 110 * (1 + k % 3);
            for (int i = 0; i < rate / 4 && at + i < x.length; i++) {
                double env = Math.exp(-i / (rate * 0.08));
                x[at + i] += (float) (env * (0.5 * Math.sin(2 * Math.PI * f * i / rate) + 0.2 * (rnd.nextDouble() - 0.5)));
            }
        }
        return x;
    }

    @Test
    public void findsStrums() {
        double[] on = Beat.onsets(strums(44100, 30), 44100);
        assertTrue(on.length >= 10);
        for (double t : on) {
            double rel = (t - 0.53) / 0.3;
            assertTrue("úder mimo mriežky: " + t, Math.abs(rel - Math.round(rel)) * 300 < 25);
        }
    }

    @Test
    public void shiftsGridToPlaying() {
        double[] on = Beat.onsets(strums(48000, 30), 48000);
        int shift = Beat.shiftMs(on, 500, 100);
        assertTrue("posun " + shift, Math.abs(shift - 30) <= 12);
        assertEquals(0, Beat.shiftMs(new double[]{1, 2}, 0, 100));
    }
}
