package sk.lubeno.riffy;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HighPassTest {
    /** Špička sínusu frekvencie f po filtri (v ustálenom stave). */
    static double peakAfter(double f) {
        int rate = 48000, n = rate;
        int[] buf = new int[n];
        for (int i = 0; i < n; i++) buf[i] = (int) (Math.sin(2 * Math.PI * f * i / rate) * 1e9);
        new HighPass(60, rate, 1).process(buf, n);
        double peak = 0;
        for (int i = n / 2; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
        return peak / 1e9;
    }

    @Test
    public void removesRumbleKeepsGuitar() {
        assertTrue(peakAfter(20) < 0.15);
        assertTrue(peakAfter(82.4) > 0.85);
        assertTrue(peakAfter(330) > 0.97);
    }
}
