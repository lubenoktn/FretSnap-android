package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class WavesTest {
    /** 24-bit stereo: prvá polovica hlasná (v pravom kanáli), druhá tichá. */
    @Test
    public void loudThenSilent() throws Exception {
        int frames = 1000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Wav.header(48000, 2, 24, frames * 6L));
        for (int i = 0; i < frames; i++) {
            int v = i < frames / 2 ? (i % 2 == 0 ? 4_000_000 : -4_000_000) : 0;
            out.write(new byte[]{0, 0, 0});
            out.write(new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16)});
        }
        byte[] p = Waves.compute(new ByteArrayInputStream(out.toByteArray()), 4);
        assertEquals(255, p[0] & 0xff);
        assertEquals(255, p[1] & 0xff);
        assertEquals(0, p[2] & 0xff);
        assertEquals(0, p[3] & 0xff);
    }

    @Test
    public void quietPartsAreLifted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Wav.header(44100, 1, 16, 4 * 2));
        for (int v : new int[]{-32000, 3200, 0, 0}) out.write(new byte[]{(byte) v, (byte) (v >> 8)});
        byte[] p = Waves.compute(new ByteArrayInputStream(out.toByteArray()), 2);
        assertEquals(255, p[0] & 0xff);
        assertEquals(0, p[1] & 0xff);
        byte[] q = Waves.compute(new ByteArrayInputStream(out.toByteArray()), 4);
        // desatina hlasitosti sa ukáže vyššie než desatinou (mocnina 0,7)
        assertTrue((q[1] & 0xff) > 40);
    }
}
