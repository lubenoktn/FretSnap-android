package sk.lubeno.riffy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ScalesTest {
    @Test
    public void tonina() {
        assertArrayEquals(new int[]{4, 1}, Scales.parseKey("Em"));
        assertArrayEquals(new int[]{7, 0}, Scales.parseKey("G"));
        assertArrayEquals(new int[]{10, 0}, Scales.parseKey("Bb"));
        assertArrayEquals(new int[]{11, 1}, Scales.parseKey("Hm"));
        assertArrayEquals(new int[]{6, 1}, Scales.parseKey("F# mol"));
        assertNull(Scales.parseKey(""));
        assertEquals("Em", Scales.key(4, true));
    }

    @Test
    public void pentatonikaEmol() {
        assertEquals("E G A H D", Scales.list(4, Scales.MINOR_PENTA));
        boolean[] n = Scales.notes(4, Scales.MINOR_PENTA);
        assertTrue(n[4] && n[7] && n[9] && n[11] && n[2]);
        // Polohy v štandarde: E na 0, G na 3, A na 5, H na 7, D na 10
        List<Integer> p = Scales.positions(Tunings.strings(0), 4, Scales.MINOR_PENTA);
        assertEquals(java.util.Arrays.asList(0, 3, 5, 7, 10), p);
    }

    @Test
    public void tonina_z_akordov() {
        // Em, Am, D, G: tóny akordov
        double[] c = new double[12];
        for (int[] chord : new int[][]{{4, 7, 11}, {9, 0, 4}, {2, 6, 9}, {7, 11, 2}}) {
            c[chord[0]] += 2;
            for (int pc : chord) c[pc] += 1;
        }
        int[] k = Scales.detectKey(c);
        assertTrue(Scales.key(k[0], k[1] == 1), (k[0] == 4 && k[1] == 1) || (k[0] == 7 && k[1] == 0));
    }
}
