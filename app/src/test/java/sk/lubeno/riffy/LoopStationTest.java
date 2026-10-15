package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoopStationTest {
    /** Výstup, pri ktorom test určuje, čo práve znie. */
    static final class FakeOut extends Output {
        long heard;

        void write(short[] pcm, int n) {
        }

        long played() {
            return heard;
        }

        double latencyMs() {
            return 0;
        }

        void close() {
        }
    }

    static final int IN_RATE = 44100, LAG = 5000;

    /**
     * Gitarista hrá údery presne vtedy, keď počuje doby (+ posun hits v rámcoch 48 kHz);
     * vstup prichádza po 50 ms. Vráti, po ktorý vstupný rámec sa došlo.
     */
    static long play(LoopStation l, FakeOut out, long fromIn, long untilHeard, long hitsOffset) {
        long in = fromIn;
        int block = IN_RATE / 20;
        while (true) {
            int[] buf = new int[block * 2];
            for (int f = 0; f < block; f++) {
                long heard = Math.round((in + f) * 48000.0 / IN_RATE) + LAG;
                long rel = Math.floorMod(heard - hitsOffset, 24000);
                int v = rel < 48 ? 1 << 30 : 0;
                buf[2 * f] = v;
                buf[2 * f + 1] = v;
            }
            in += block;
            out.heard = Math.round(in * 48000.0 / IN_RATE) + LAG;
            l.onAudio(buf, block * 2, 2, IN_RATE);
            if (out.heard > untilHeard) return in;
        }
    }

    static void assertHitsAt(float[] loop, long first) {
        for (int k = 0; k < loop.length / 24000; k++) {
            int at = (int) (first + k * 24000L);
            // Začiatok úderu: prvá vzorka nad polovicou (pri začiatku slučky je nábeh 16 vzoriek).
            int where = -1;
            for (int j = Math.max(0, at - 200); j < Math.min(loop.length, at + 200) && where < 0; j++) {
                if (loop[j] > 0.3f) where = j;
            }
            if (at == 0 && where >= 0 && where <= 16) where = 0;
            assertTrue("úder " + k + " chýba", where >= 0);
            assertTrue("úder " + k + " je na " + where + ", nie na " + at, Math.abs(where - at) <= 3);
        }
    }

    @Test
    public void firstLoopStartsOnBarAndLayersFit() {
        Drums d = new Drums();
        FakeOut out = new FakeOut();
        d.attachForTest(out, 120, 4);
        LoopStation l = new LoopStation(d);
        l.bars = 1;
        long in = play(l, out, 0, 10000, 0);
        assertTrue(l.recordFirst());
        assertEquals(LoopStation.COUNTING, l.state());
        in = play(l, out, in, 96000 + 96000 + 5000, 0);
        assertEquals(LoopStation.PLAYING, l.state());
        assertEquals(1, l.layers());
        float[] loop = d.loop;
        assertNotNull(loop);
        assertEquals(96000, loop.length);
        assertEquals(96000, d.loopStart);
        assertHitsAt(loop, 0);

        // Druhá vrstva: údery o osminu neskôr, musia padnúť medzi prvé.
        assertTrue(l.recordLayer());
        assertEquals(LoopStation.ARMED, l.state());
        play(l, out, in, 288000 + 96000 + 5000, 12000);
        assertEquals(2, l.layers());
        float[] mix = l.mixdown();
        assertHitsAt(mix, 0);
        assertHitsAt(mix, 12000);
        l.undo();
        assertEquals(1, l.layers());
    }
}
