package sk.lubeno.riffy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;

import org.junit.Test;

public class WavTest {
    /** 1 s ticha, 0,5 s hlasného zvuku, 1 s ticha pri 1000 Hz, stereo. */
    static File sample() throws Exception {
        File f = File.createTempFile("riff", ".wav");
        Wav.Writer w = new Wav.Writer(f, 2);
        int[] buf = new int[2500 * 2];
        for (int i = 0; i < 2500; i++) {
            int v = i >= 1000 && i < 1500 ? (i % 2 == 0 ? 0x40000000 : -0x40000000) : 0;
            buf[2 * i] = v;
            buf[2 * i + 1] = -v;
        }
        w.write(buf, 0, buf.length);
        w.finish(1000);
        return f;
    }

    @Test
    public void hlavickaAZapis() throws Exception {
        File f = sample();
        assertEquals(44 + 2500 * 2 * 3, f.length());
        try (FileInputStream in = new FileInputStream(f)) {
            Wav.Info i = Wav.read(in);
            assertEquals(1000, i.rate);
            assertEquals(2, i.channels);
            assertEquals(24, i.bits);
            assertEquals(2500, i.frames());
        }
    }

    @Test
    public void orezanieTicha() throws Exception {
        File f = sample();
        long[] r = Wav.trimRange(f, -50, 0.1);
        assertArrayEquals(new long[]{900, 1600}, r);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Wav.copyRange(f, r[0], r[1], out);
        Wav.Info i = Wav.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(r[1] - r[0], i.frames());
        assertEquals(out.size(), 44 + (r[1] - r[0]) * 6);
    }

    @Test
    public void tichaNahravkaOstaneCela() throws Exception {
        File f = File.createTempFile("ticho", ".wav");
        Wav.Writer w = new Wav.Writer(f, 1);
        w.write(new int[300], 0, 300);
        w.finish(1000);
        assertArrayEquals(new long[]{0, 300}, Wav.trimRange(f, -50, 0.1));
        Files.delete(f.toPath());
    }

    /** Hlavička s ďalším blokom pred dátami (napr. LIST) sa prečíta správne. */
    @Test
    public void inyBlokPredDatami() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] h = Wav.header(44100, 2, 16, 8);
        b.write(h, 0, 36);
        b.write(new byte[]{'L', 'I', 'S', 'T', 3, 0, 0, 0, 1, 2, 3, 0});
        b.write(h, 36, 8);
        b.write(new byte[8]);
        Wav.Info i = Wav.read(new ByteArrayInputStream(b.toByteArray()));
        assertEquals(44100, i.rate);
        assertEquals(2, i.frames());
    }
}
