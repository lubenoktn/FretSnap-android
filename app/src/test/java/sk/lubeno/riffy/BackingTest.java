package sk.lubeno.riffy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackingTest {
    @Test
    public void postupVTonine() {
        Backing b = new Backing(4, Backing.PRESET_CHORDS[4], 1, true, true);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.roots.length; i++) s.append(Backing.name(b.roots[i], b.kinds[i])).append(" ");
        assertEquals("Em C G D ", s.toString());
        assertEquals(0, b.chordAt(0));
        assertEquals(3, b.chordAt(3));
        assertEquals(0, b.chordAt(4));
        assertEquals(-1, b.chordAt(-1));
        Backing two = new Backing(4, Backing.PRESET_CHORDS[4], 2, true, true);
        assertEquals(0, two.chordAt(1));
        assertEquals(1, two.chordAt(2));
    }

    @Test
    public void ulozenie() {
        int[][] p = Backing.PRESET_CHORDS[3];
        assertArrayEquals(p, Backing.decode(Backing.encode(p)));
        assertTrue(Backing.tones(4, Backing.MINOR)[7]);
        assertTrue(Backing.tones(9, Backing.SEVENTH)[7]);
    }

    @Test
    public void basaHraSKopakom() {
        Drums.Sequencer s = new Drums.Sequencer(DrumsTest.kit());
        s.style = 0;
        s.fillBars = 0;
        s.bpm = 120;
        s.volume = 1;
        s.backing = new Backing(4, Backing.PRESET_CHORDS[4], 1, true, false);
        s.seek(0);
        short[] out = new short[24000 * 4];
        s.render(out, out.length, 1);
        // Medzi údermi bicích (testovacie zvuky trvajú 1 vzorku) znie basa
        int sounding = 0;
        for (int i = 100; i < 12000; i++) if (out[i] != 0) sounding++;
        assertTrue("basa nehrá", sounding > 10000);
    }
}
