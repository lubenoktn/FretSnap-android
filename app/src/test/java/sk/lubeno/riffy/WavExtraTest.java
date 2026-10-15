package sk.lubeno.riffy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class WavExtraTest {
    static File wav(int frames, int value) throws Exception {
        File f = File.createTempFile("riff", ".wav");
        Wav.Writer w = new Wav.Writer(f, 2);
        int[] s = new int[frames * 2];
        for (int i = 0; i < s.length; i++) s[i] = (i / 2) % 2 == 0 ? value : -value;
        w.write(s, 0, s.length);
        w.finish(1000);
        return f;
    }

    static byte[] read(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            return Wav.readExtra(r.getChannel());
        }
    }

    static void write(File f, String text) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            FileChannel ch = r.getChannel();
            Wav.writeExtra(ch, ch, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void udajeVSubore() throws Exception {
        File f = wav(100, 1 << 20);
        long before = f.length();
        assertNull(read(f));
        write(f, "{\"note\":\"sólo\"}");
        assertEquals("{\"note\":\"sólo\"}", new String(read(f), StandardCharsets.UTF_8));
        // Nepárna dĺžka, prepísanie kratším textom: starý blok zmizne.
        write(f, "abc");
        assertEquals("abc", new String(read(f), StandardCharsets.UTF_8));
        assertEquals(before + 8 + 4, f.length());
        // Zvuk ostal a hlavička RIFF sedí s dĺžkou súboru.
        try (FileInputStream in = new FileInputStream(f)) {
            Wav.Info i = Wav.read(in);
            assertEquals(100, i.frames());
        }
        byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
        assertEquals(f.length() - 8, Wav.le(all, 4, 4) & 0xffffffffL);
    }

    @Test
    public void zachranaPoPade() throws Exception {
        File f = File.createTempFile("pad", ".wav");
        Wav.Writer w = new Wav.Writer(f, 2, 44100);
        w.write(new int[2000], 0, 2000);
        w.close();   // bez finish: ako keby aplikácia spadla
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(new byte[5]);  // neúplný posledný rámec
        }
        assertEquals(1000, Wav.repair(f));
        try (FileInputStream in = new FileInputStream(f)) {
            Wav.Info i = Wav.read(in);
            assertEquals(44100, i.rate);
            assertEquals(1000, i.frames());
        }
        assertEquals(44 + 1000 * 6, f.length());
    }

    @Test
    public void bezHlavickySaNeopravuje() throws Exception {
        File f = File.createTempFile("stary", ".wav");
        Wav.Writer w = new Wav.Writer(f, 2);
        w.write(new int[200], 0, 200);
        w.close();
        assertEquals(-1, Wav.repair(f));
    }

    @Test
    public void hlasitost() throws Exception {
        File f = wav(1000, 0x10000000);   // 1/8 plnej úrovne = -18 dB
        double[] a = Wav.analyze(f, 0, 1000);
        assertEquals(0.125, a[0], 0.001);
        assertEquals(0.125, a[1], 0.001);
        assertEquals(1.0, Wav.loudnessGain(a[0], a[1]), 0.01);
        // Tichšia nahrávka sa zosilní, ale špička neprekročí -1 dB.
        assertEquals(0.891 / 0.5, Wav.loudnessGain(0.5, 0.05), 0.001);
        assertEquals(16, Wav.loudnessGain(0.001, 0.0005), 0.001);
        assertEquals(1, Wav.loudnessGain(0, 0), 0);
    }

    @Test
    public void zosilnenieANabeh() throws Exception {
        File f = wav(1000, 0x10000000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Wav.copyProcessed(f, 100, 900, 2.0, 10, 10, out);
        byte[] b = out.toByteArray();
        Wav.Info i = Wav.read(new ByteArrayInputStream(b));
        assertEquals(800, i.frames());
        int first = Wav.le(b, 44, 3) << 8 >> 8;
        int middle = Wav.le(b, 44 + 400 * 6, 3) << 8 >> 8;
        int last = Wav.le(b, b.length - 6, 3) << 8 >> 8;
        assertEquals(0, first);
        assertEquals(0, last);
        assertEquals(0x200000, Math.abs(middle));   // 0x10000000 >> 8 * 2
        assertTrue(Math.abs(Wav.le(b, 44 + 5 * 6, 3) << 8 >> 8) < 0x200000);
    }

    @Test
    public void orezanieAkoPredtym() throws Exception {
        File f = wav(10, 1 << 24);
        ByteArrayOutputStream a = new ByteArrayOutputStream(), b = new ByteArrayOutputStream();
        Wav.copyRange(f, 2, 8, a);
        Wav.copyProcessed(f, 2, 8, 1.0, 0, 0, b);
        assertArrayEquals(a.toByteArray(), b.toByteArray());
    }
}
