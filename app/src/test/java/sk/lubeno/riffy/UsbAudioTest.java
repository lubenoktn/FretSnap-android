package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

public class UsbAudioTest {
    static byte[] desc(int... v) {
        byte[] b = new byte[v.length + 1];
        b[0] = (byte) (v.length + 1);
        for (int i = 0; i < v.length; i++) b[i + 1] = (byte) v[i];
        return b;
    }

    static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    /** Zariadenie s UAC2: výstup na slúchadlá a stereo vstup 24 bit (podobne ako gitarové prevodníky). */
    @Test
    public void uac2() {
        byte[] raw = join(
                desc(1, 0x00, 0x02, 0xEF, 0x02, 0x01, 0x40, 0x34, 0x12, 0x01, 0x00, 0, 0, 0, 0, 1), // zariadenie
                desc(4, 0, 0, 0, 1, 1, 0x20, 0),            // AudioControl, UAC2
                desc(0x24, 1, 0, 2, 8, 0, 0, 0),            // hlavička
                desc(0x24, 0x0A, 0x29, 3, 7, 0, 0),         // hodiny 0x29
                desc(4, 1, 0, 0, 1, 2, 0x20, 0),            // výstup, nastavenie 0
                desc(4, 1, 1, 1, 1, 2, 0x20, 0),            // výstup, nastavenie 1
                desc(0x24, 1, 2, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0),
                desc(0x24, 2, 1, 3, 24),
                desc(5, 0x01, 0x05, 0x20, 0x01, 1),          // OUT, nemá sa vybrať
                desc(4, 2, 0, 0, 1, 2, 0x20, 0),            // vstup, nastavenie 0
                desc(4, 2, 1, 1, 1, 2, 0x20, 0),            // vstup, nastavenie 1
                desc(0x24, 1, 5, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0),
                desc(0x24, 2, 1, 4, 24),
                desc(5, 0x82, 0x05, 0x40, 0x01, 1),
                desc(0x25, 1, 0, 0, 0, 0, 0));
        UsbAudio.Info info = UsbAudio.parse(raw);
        assertEquals(2, info.uac);
        assertEquals(0, info.controlIface);
        assertEquals(1, info.clocks.size());
        assertEquals(0x29, (int) info.clocks.get(0));
        UsbAudio.Alt a = info.best();
        assertNotNull(a);
        assertEquals(2, a.iface);
        assertEquals(1, a.alt);
        assertEquals(0x82, a.endpoint);
        assertEquals(2, a.channels);
        assertEquals(4, a.subslot);
        assertEquals(24, a.bits);
        assertEquals(0x140, a.packetSize);
    }

    /** UAC1 s viacerými vzorkovaniami a vysokorýchlostným paketom (násobok v horných bitoch). */
    @Test
    public void uac1() {
        byte[] raw = join(
                desc(4, 0, 0, 0, 1, 1, 0, 0),
                desc(4, 1, 0, 0, 1, 2, 0, 0),
                desc(4, 1, 1, 1, 1, 2, 0, 0),
                desc(0x24, 1, 2, 1, 1, 0),
                desc(0x24, 2, 1, 1, 2, 16, 2, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00),
                desc(5, 0x81, 0x0D, 0x00, 0x09, 1, 0, 0));
        UsbAudio.Info info = UsbAudio.parse(raw);
        assertEquals(1, info.uac);
        UsbAudio.Alt a = info.best();
        assertNotNull(a);
        assertEquals(1, a.channels);
        assertEquals(16, a.bits);
        assertEquals(2, a.rates.size());
        assertEquals(48000, (int) a.rates.get(1));
        assertEquals(0x100 * 2, a.packetSize);
    }

    @Test
    public void bezVstupu() {
        assertNull(UsbAudio.parse(desc(4, 0, 0, 0, 1, 1, 0, 0)).best());
    }

    static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    /** 24 bitov v štyroch bajtoch je zarovnaných hore; prevod ich nechá tak, ako sú. */
    @Test
    public void prevod32() {
        assertEquals(12345 << 16 | 0x2B00, UsbAudio.convert(le(12345L << 16 | 0x2B00, 4), 0, 4, 1f));
        assertEquals(-12345 << 16, UsbAudio.convert(le(-12345L << 16, 4), 0, 4, 1f));
    }

    @Test
    public void prevod24a16() {
        assertEquals(-200 << 8, UsbAudio.convert(le(-200, 3), 0, 3, 1f));
        assertEquals(1000 << 16, UsbAudio.convert(le(1000, 2), 0, 2, 1f));
    }

    @Test
    public void zosilnenie() {
        assertEquals(500 << 16, UsbAudio.convert(le(1000, 2), 0, 2, 0.5f));
        // Pri prebudení sa hodnota oreže, nepreteká do opačného znamienka.
        assertEquals(Integer.MAX_VALUE, UsbAudio.convert(le(20000, 2), 0, 2, 4f));
        assertEquals(Integer.MIN_VALUE, UsbAudio.convert(le(-20000, 2), 0, 2, 4f));
    }

    @Test
    public void spicky() {
        int[] p = Source.peaks(new int[]{1000 << 16, -2000 << 16, Integer.MIN_VALUE, 5}, 4, 2, new int[2]);
        assertEquals(32768, p[0]);
        assertEquals(2000, p[1]);
    }
}
