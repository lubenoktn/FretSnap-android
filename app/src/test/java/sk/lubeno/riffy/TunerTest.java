package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TunerTest {
    /** Tón podobný gitare: základ a vyššie harmonické, ktoré sa často zamenia za oktávu. */
    static float[] tone(double hz, int rate, double level) {
        float[] x = new float[Tuner.WINDOW * 2];
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) rate, v = 0;
            for (int h = 1; h <= 6; h++) v += Math.sin(2 * Math.PI * hz * h * t) / h * (h == 2 ? 1.4 : 1);
            x[i] = (float) (v * level * 0.4);
        }
        return x;
    }

    static void expect(double hz, String note) {
        Tuner.Pitch p = Tuner.detect(tone(hz, 44100, 0.5), 44100);
        assertNotNull(p);
        assertEquals(note, p.note());
        assertTrue("odchýlka " + p.cents, Math.abs(p.cents) < 3);
    }

    @Test
    public void strunyGitary() {
        expect(82.41, "E2");
        expect(110.0, "A2");
        expect(146.83, "D3");
        expect(196.0, "G3");
        expect(246.94, "H3");
        expect(329.63, "E4");
    }

    @Test
    public void podladenaAVysoka() {
        expect(73.42, "D2");
        expect(880.0, "A5");
    }

    @Test
    public void odchylka() {
        Tuner.Pitch p = Tuner.detect(tone(440 * Math.pow(2, 20 / 1200.0), 48000, 0.5), 48000);
        assertEquals("A4", p.note());
        assertEquals(20, p.cents, 2);
    }

    @Test
    public void tichoNieJeTon() {
        assertNull(Tuner.detect(tone(110, 44100, 0.001), 44100));
    }

    @Test
    public void prudVzoriek() {
        Tuner t = new Tuner();
        Tuner.Pitch[] got = new Tuner.Pitch[1];
        t.listener = (p, db) -> got[0] = p;
        float[] x = tone(110, 44100, 0.5);
        int[] s = new int[x.length * 2];
        for (int i = 0; i < x.length; i++) s[2 * i] = s[2 * i + 1] = (int) (x[i] * Integer.MAX_VALUE);
        t.feed(s, s.length, 2, 44100);
        assertNotNull(got[0]);
        assertEquals("A2", got[0].note());
    }

    @Test
    public void strunaPodlaLadenia() {
        int[] dropD = Tunings.strings(1);
        // D2 o 10 centov nižšie: 6. struna v Drop D
        Tuner.Pitch p = new Tuner.Pitch(73.42 * Math.pow(2, -10 / 1200.0));
        assertEquals(0, Tunings.nearest(dropD, p.exact));
        assertEquals(-10, (p.exact - dropD[0]) * 100, 1);
        // V štandarde je E2 o 2 poltóny vyššie: stále 6. struna, ladička povie „pritiahnuť“
        assertEquals(0, Tunings.nearest(Tunings.strings(0), p.exact));
        assertEquals(-1, Tunings.nearest(Tunings.strings(0), 30));
    }

    @Test
    public void komorneA() {
        Tuner.Pitch p = new Tuner.Pitch(442, 442);
        assertEquals("A4", p.note());
        assertEquals(0, p.cents, 0.01);
        assertEquals(8, new Tuner.Pitch(442, 440).cents, 0.5);
    }
}
