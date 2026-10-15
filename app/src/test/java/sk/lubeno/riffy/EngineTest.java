package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class EngineTest {
    /** Vstup, ktorý pri prvom otvorení posiela len nuly (zariadenie sa ešte zapína). */
    static class Fake extends Source {
        final boolean silent;

        Fake(boolean silent) {
            this.silent = silent;
            channels = 2;
            rate = 48000;
        }

        @Override
        int read(int[] out) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            for (int i = 0; i < 1920; i++) out[i] = silent ? 0 : 1 << 20;
            return 1920;
        }

        @Override
        boolean dead() {
            return silent;
        }

        @Override
        void close() {
        }
    }

    @Test
    public void tichyVstupSaOtvoriZnova() throws Exception {
        Engine e = new Engine();
        AtomicInteger opens = new AtomicInteger();
        java.util.List<Integer> attempts = new java.util.ArrayList<>();
        e.start("a", attempt -> {
            attempts.add(attempt);
            return new Fake(opens.incrementAndGet() == 1);
        });
        Thread.sleep(Source.SETTLE_MS + 2500);
        assertEquals(2, opens.get());
        // Druhý pokus vie, že je druhý (USB vstup pri ňom skúsi iné hodiny).
        assertEquals(java.util.Arrays.asList(0, 1), attempts);
        assertTrue(e.running());
        java.io.File f = java.io.File.createTempFile("spatne", ".wav");
        Engine.Recorded r = e.saveHistory(f, 30);
        assertTrue(r.frames > 0);
        e.stop();
    }

    @Test
    public void ziveZariadenieSaNeotvaraZnova() throws Exception {
        Engine e = new Engine();
        AtomicInteger opens = new AtomicInteger();
        e.start("a", attempt -> new Fake(false) {
            { opens.incrementAndGet(); }
        });
        Thread.sleep(Source.SETTLE_MS + 1500);
        assertEquals(1, opens.get());
        e.stop();
    }
}
