package sk.lubeno.riffy;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vlastný ovládač USB zvuku. Zvukový systém Androidu sa obíde: aplikácia prevezme zvukové
 * rozhrania zariadenia a číta izochrónny prenos sama (natívna časť v jni/usbaudio.c).
 * Podporuje USB Audio Class 1 aj 2 so vzorkami PCM.
 */
public class UsbAudio {
    /** Chyba pri načítaní natívnej časti, inak null. */
    static String loadError = "nenačítané";

    static void load() {
        if (loadError == null) return;
        try {
            System.loadLibrary("usbaudio");
            loadError = null;
        } catch (Throwable t) {
            loadError = String.valueOf(t.getMessage());
        }
    }

    /** Vráti adresu otvoreného prenosu, alebo -errno. Android 16 značkuje adresy v hornom bajte, takže aj platná adresa môže byť záporné číslo. */
    static native long nativeOpen(int fd, int endpoint, int packetSize, int packets, int asap, int frameBytes,
                                  int rate, int packetsPerSecond);
    static native long[] nativeStats(long handle);

    static boolean failed(long handle) {
        return handle < 0 && handle >= -4095;
    }
    static native int nativeRead(long handle, byte[] out);
    static native void nativeClose(long handle);

    static final int[] RATES = {44100, 48000, 88200, 96000, 176400, 192000};

    /** Jedno nastavenie vstupného rozhrania: formát a koncový bod, z ktorého ide zvuk. */
    static class Alt {
        int iface, alt, uac = 1;
        int endpoint, maxPacket, packetSize, interval, attributes;
        int channels, subslot, bits;
        final List<Integer> rates = new ArrayList<>();

        boolean usable() {
            return endpoint != 0 && channels > 0 && subslot >= 2 && subslot <= 4;
        }

        String format() {
            return channels + " kan., " + bits + " bit" + (rates.isEmpty() ? "" : ", " + rates + " Hz");
        }

        String line() {
            return String.format(Locale.ROOT, "rozhranie %d nastavenie %d, UAC%d: %s; bod 0x%02x, typ 0x%02x, "
                            + "paket %d (max 0x%04x), interval %d, vzorka %d B",
                    iface, alt, uac, format(), endpoint, attributes, packetSize, maxPacket, interval, subslot);
        }
    }

    /** Čo sa podarilo vyčítať z popisu zariadenia. */
    static class Info {
        int controlIface = -1, uac = 1;
        final List<Integer> clocks = new ArrayList<>();
        final List<Alt> alts = new ArrayList<>();

        /** Najvhodnejší vstup: stereo pred ostatnými, potom väčšia bitová hĺbka. */
        Alt best() {
            Alt best = null;
            for (Alt a : alts) {
                if (!a.usable()) continue;
                if (best == null || score(a) > score(best)) best = a;
            }
            return best;
        }

        static int score(Alt a) {
            return (a.channels == 2 ? 1000 : a.channels == 1 ? 500 : 0) + a.bits;
        }
    }

    static int u8(byte[] b, int i) {
        return i < b.length ? b[i] & 0xff : 0;
    }

    static int u16(byte[] b, int i) {
        return u8(b, i) | u8(b, i + 1) << 8;
    }

    static int u24(byte[] b, int i) {
        return u16(b, i) | u8(b, i + 2) << 16;
    }

    /** Rozoberie popisovače zariadenia (getRawDescriptors) a nájde zvukové vstupy. */
    static Info parse(byte[] raw) {
        Info info = new Info();
        int cls = 0, sub = 0;
        Alt cur = null;
        for (int i = 0; i + 1 < raw.length; ) {
            int len = u8(raw, i), type = u8(raw, i + 1);
            if (len < 2 || i + len > raw.length) break;
            if (type == 4) {
                // Rozhranie
                cls = u8(raw, i + 5);
                sub = u8(raw, i + 6);
                int proto = u8(raw, i + 7);
                cur = null;
                if (cls == 1 && sub == 1) {
                    info.controlIface = u8(raw, i + 2);
                    info.uac = proto == 0x20 ? 2 : 1;
                } else if (cls == 1 && sub == 2) {
                    cur = new Alt();
                    cur.iface = u8(raw, i + 2);
                    cur.alt = u8(raw, i + 3);
                    cur.uac = proto == 0x20 ? 2 : 1;
                }
            } else if (type == 0x24) {
                // Zvukový popisovač triedy
                int subtype = u8(raw, i + 2);
                if (cls == 1 && sub == 1 && info.uac == 2 && subtype == 0x0A) info.clocks.add(u8(raw, i + 3));
                if (cur != null && subtype == 1 && cur.uac == 2) cur.channels = u8(raw, i + 10);
                if (cur != null && subtype == 2) {
                    if (cur.uac == 2) {
                        cur.subslot = u8(raw, i + 4);
                        cur.bits = u8(raw, i + 5);
                    } else {
                        cur.channels = u8(raw, i + 4);
                        cur.subslot = u8(raw, i + 5);
                        cur.bits = u8(raw, i + 6);
                        int n = u8(raw, i + 7);
                        if (n == 0) {
                            cur.rates.add(u24(raw, i + 8));
                            cur.rates.add(u24(raw, i + 11));
                        }
                        for (int k = 0; k < n; k++) cur.rates.add(u24(raw, i + 8 + 3 * k));
                    }
                }
            } else if (type == 5 && cur != null) {
                // Koncový bod: hľadáme izochrónny vstup, ktorý nie je len spätná väzba
                int addr = u8(raw, i + 2), attr = u8(raw, i + 3), w = u16(raw, i + 4);
                if ((attr & 3) == 1 && (addr & 0x80) != 0 && ((attr >> 4) & 3) != 1 && cur.endpoint == 0) {
                    cur.endpoint = addr;
                    cur.attributes = attr;
                    cur.maxPacket = w;
                    cur.packetSize = (w & 0x7ff) * (1 + ((w >> 11) & 3));
                    cur.interval = u8(raw, i + 6);
                    info.alts.add(cur);
                }
            }
            i += len;
        }
        return info;
    }

    /** Má zariadenie zvukový vstup? Dá sa zistiť aj bez povolenia. */
    static boolean hasAudioInput(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() != UsbConstants.USB_CLASS_AUDIO || f.getInterfaceSubclass() != 2) continue;
            for (int e = 0; e < f.getEndpointCount(); e++) {
                UsbEndpoint ep = f.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.getDirection() == UsbConstants.USB_DIR_IN) return true;
            }
        }
        return false;
    }

    static String name(UsbDevice d) {
        String n = d.getProductName();
        return n == null || n.trim().isEmpty() ? String.format("USB %04x:%04x", d.getVendorId(), d.getProductId()) : n.trim();
    }

    static UsbInterface find(UsbDevice d, int id, int alt) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getId() == id && f.getAlternateSetting() == alt) return f;
        }
        return null;
    }

    static byte[] le(int v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (v >> (8 * i));
        return b;
    }

    /**
     * Koľko paketov za sekundu zariadenie posiela. Rýchlosť USB Android neprezradí; keď by sa
     * zvuk do paketov po 1 ms nezmestil, ide o vysokorýchlostné USB s paketmi po 125 µs.
     */
    static int packetsPerSecond(Alt alt, int rate) {
        int interval = Math.max(1, Math.min(alt.interval, 16));
        boolean high = alt.packetSize * 1000L < (long) rate * alt.channels * alt.subslot;
        return high ? 8000 >> (interval - 1) : 1000 >> Math.min(interval - 1, 9);
    }

    /** Skúšobný prenos s tromi nastaveniami hodín; počas neho treba hrať. */
    static String selfTest(UsbManager um, UsbDevice d) {
        StringBuilder s = new StringBuilder();
        for (int want : new int[]{0, 44100, 48000}) {
            s.append("Skúška, hodiny ").append(want == 0 ? "bez zmeny" : want + " Hz").append(": ");
            Source c;
            try {
                c = open(um, d, want);
            } catch (Exception e) {
                s.append(e.getMessage()).append("\n");
                continue;
            }
            int[] buf = new int[c.rate / 20 * c.channels];
            int[] peak = new int[c.channels];
            long begin = System.currentTimeMillis();
            try {
                while (System.currentTimeMillis() - begin < 1500) {
                    int n = c.read(buf);
                    if (n < 0) {
                        s.append(c.error).append("; ");
                        break;
                    }
                    Source.peaks(buf, n, c.channels, peak);
                }
                Direct dc = (Direct) c;
                long[] st = dc.stats();
                s.append(String.format(Locale.ROOT, "hlási %d Hz, prijaté %.0f Hz, po vyradení výplne %d Hz, nulové pakety %d z %d, vyradené %d, stratené %d B, chybné %d, úroveň %s\n",
                        c.rate, st[0] / (double) dc.frameBytes / ((System.nanoTime() - dc.began) / 1e9), c.finalRate(),
                        st[3], st[4], st[5], st[1], st[2], Source.levels(peak)));
            } finally {
                c.close();
            }
        }
        return s.toString();
    }

    /** Technický popis zariadenia, ktorý si používateľ môže poslať. */
    static String report(UsbManager um, UsbDevice d) {
        StringBuilder s = new StringBuilder();
        s.append(name(d)).append(String.format(Locale.ROOT, "  (%04x:%04x, %s)\n",
                d.getVendorId(), d.getProductId(), d.getManufacturerName()));
        UsbDeviceConnection conn = um.openDevice(d);
        if (conn == null) return s.append("Zariadenie sa nedá otvoriť.").toString();
        try {
            byte[] raw = conn.getRawDescriptors();
            Info info = parse(raw);
            s.append("UAC").append(info.uac).append(", riadiace rozhranie ").append(info.controlIface)
                    .append(", hodiny ").append(info.clocks).append("\n");
            for (Alt a : info.alts) s.append("Vstup: ").append(a.line()).append("\n");
            if (info.alts.isEmpty()) s.append("Žiadny zvukový vstup sa nenašiel.\n");
            if (info.uac == 2 && info.controlIface >= 0) {
                // Prevzatie rozhrania je potrebné, aby jadro pustilo požiadavku na hodiny.
                UsbInterface ac = find(d, info.controlIface, 0);
                if (ac != null) conn.claimInterface(ac, true);
                for (int clock : info.clocks) {
                    s.append("Hodiny ").append(clock).append(": ").append(readClock(conn, info, clock)).append(" Hz\n");
                }
            }
            s.append("\nPopisovače:\n");
            for (int i = 0; i < raw.length; i++) s.append(String.format("%02x", raw[i])).append(i % 32 == 31 ? "\n" : " ");
        } finally {
            conn.close();
        }
        return s.toString();
    }

    static int readClock(UsbDeviceConnection conn, Info info, int clock) {
        byte[] b = new byte[4];
        int r = conn.controlTransfer(0xA1, 0x01, 0x0100, clock << 8 | info.controlIface, b, 4, 500);
        return r == 4 ? u16(b, 0) | u16(b, 2) << 16 : -1;
    }

    /**
     * Prevedie vzorku (2, 3 alebo 4 bajty, little endian) na 32-bitové číslo so zvukom hore,
     * so zosilnením. 24 bitov v štyroch bajtoch je podľa štandardu USB zarovnaných hore.
     */
    static int convert(byte[] raw, int at, int subslot, float gain) {
        int v;
        if (subslot == 4) v = (raw[at] & 0xff) | (raw[at + 1] & 0xff) << 8 | (raw[at + 2] & 0xff) << 16 | raw[at + 3] << 24;
        else if (subslot == 3) v = (raw[at] & 0xff) << 8 | (raw[at + 1] & 0xff) << 16 | raw[at + 2] << 24;
        else v = (raw[at] & 0xff) << 16 | raw[at + 1] << 24;
        return gain == 1f ? v : Source.clamp((double) v * gain);
    }

    /** Otvorené priame nahrávanie. */
    static class Direct extends Source {
        UsbDeviceConnection conn;
        UsbInterface stream;
        Alt alt;
        long handle;
        byte[] raw;
        long began;
        int frameBytes;

        @Override
        int read(int[] out) {
            // Zo zásoby sa berie najviac toľko, koľko sa zmestí do out; zvyšok by sa stratil.
            int fit = out.length / channels * frameBytes;
            if (raw == null || raw.length != fit) raw = new byte[fit];
            int n = nativeRead(handle, raw);
            if (n < 0) {
                error = "Prenos z USB sa prerušil (" + errno(n) + ").";
                return -1;
            }
            int count = Math.min(n / frameBytes, out.length / channels), k = 0;
            float g = gain;
            for (int f = 0; f < count; f++) {
                int base = f * frameBytes;
                for (int c = 0; c < channels; c++) out[k++] = convert(raw, base + c * alt.subslot, alt.subslot, g);
            }
            return k;
        }

        /** Nič neprišlo, alebo len pakety samých núl (ToneX aj v tichu posiela slabý šum). */
        @Override
        boolean dead() {
            long[] st = stats();
            return st[0] == 0 || st[3] >= st[4];
        }

        long[] stats() {
            return handle == 0 ? new long[7] : nativeStats(handle);
        }

        /** Skutočné vzorkovanie podľa toho, koľko dát zo zariadenia naozaj prišlo. */
        @Override
        int finalRate() {
            double secs = (System.nanoTime() - began) / 1e9;
            if (secs < 0.5) return rate;
            double measured = stats()[6] / (double) frameBytes / secs;
            int best = rate;
            for (int r : RATES) if (Math.abs(r - measured) < Math.abs(best - measured)) best = r;
            return Math.abs(best - measured) / best < 0.05 ? best : rate;
        }

        @Override
        void close() {
            if (handle != 0) {
                rate = finalRate();
                nativeClose(handle);
            }
            handle = 0;
            UsbInterface zero = stream == null ? null : find(dev, stream.getId(), 0);
            if (zero != null) conn.setInterface(zero);
            if (stream != null) conn.releaseInterface(stream);
            conn.close();
        }

        UsbDevice dev;
    }

    static String errno(long code) {
        switch ((int) -code) {
            case 1: return "EPERM";
            case 2: return "ENOENT";
            case 5: return "EIO";
            case 12: return "ENOMEM";
            case 13: return "EACCES";
            case 16: return "EBUSY";
            case 18: return "EXDEV";
            case 19: return "ENODEV";
            case 22: return "EINVAL";
            case 25: return "ENOTTY";
            case 27: return "EFBIG";
            case 28: return "ENOSPC";
            case 32: return "EPIPE";
            case 71: return "EPROTO";
            case 110: return "ETIMEDOUT";
            default: return "chyba " + (-code);
        }
    }

    /**
     * Vzorkovanie. want = 0 nechá zariadeniu jeho vlastné (ToneX Plug po prepnutí na 48 kHz
     * naďalej posielal 44,1 kHz a chýbajúce miesto dopĺňal nulami).
     */
    static int setRate(UsbDeviceConnection conn, Info info, Alt alt, int want) {
        if (info.uac == 2) {
            for (int clock : info.clocks) {
                int now = readClock(conn, info, clock);
                // Hodiny sa nastavia vždy, aj na tú istú hodnotu: ToneX Plug po zapojení bez tohto
                // pokynu prenos nespustí (posiela len ticho).
                int set = want > 0 ? want : now;
                if (set > 0) {
                    conn.controlTransfer(0x21, 0x01, 0x0100, clock << 8 | info.controlIface, le(set, 4), 4, 500);
                    now = readClock(conn, info, clock);
                }
                if (now > 0) return now;
            }
            return want > 0 ? want : 48000;
        }
        if (alt.rates.isEmpty()) return want > 0 ? want : 48000;
        int rate = want > 0 && alt.rates.contains(want) ? want : alt.rates.contains(48000) ? 48000 : alt.rates.get(0);
        if (alt.rates.size() > 1) conn.controlTransfer(0x22, 0x01, 0x0100, alt.endpoint, le(rate, 3), 3, 500);
        return rate;
    }

    /**
     * Otvorí priame nahrávanie. Telefóny sa líšia v tom, aké dávky izochrónneho prenosu jadro prijme,
     * preto sa skúšajú postupne; ak nevyjde nič, chyba vypíše odpoveď na každý pokus.
     */
    static Source open(UsbManager um, UsbDevice d) throws IOException {
        return open(um, d, 0);
    }

    /**
     * Hodiny pri pokuse o otvorenie: najprv vlastné vzorkovanie zariadenia, ak by z neho nič nešlo,
     * ešte 48 a 44,1 kHz (tak ho prebudil aj skúšobný prenos v popise USB).
     */
    static int wantFor(int attempt) {
        switch (attempt) {
            case 1: return 48000;
            case 2: return 44100;
            default: return 0;
        }
    }

    static Source open(UsbManager um, UsbDevice d, int want) throws IOException {
        load();
        if (loadError != null) throw new IOException("Ovládač sa nedá načítať: " + loadError);
        UsbDeviceConnection conn = um.openDevice(d);
        if (conn == null) throw new IOException("USB zariadenie sa nedá otvoriť.");
        Direct s = new Direct();
        s.conn = conn;
        s.dev = d;
        StringBuilder log = new StringBuilder();
        try {
            Info info = parse(conn.getRawDescriptors());
            List<Alt> order = new ArrayList<>();
            for (Alt a : info.alts) if (a.usable()) order.add(a);
            order.sort((x, y) -> Integer.compare(Info.score(y), Info.score(x)));
            if (order.isEmpty()) throw new IOException("V zariadení sa nenašiel zvukový vstup, ktorý by aplikácia vedela čítať.");
            UsbInterface ac = info.controlIface < 0 ? null : find(d, info.controlIface, 0);
            if (ac != null && !conn.claimInterface(ac, true)) log.append("Riadiace rozhranie sa nedá prevziať.\n");

            for (Alt alt : order) {
                log.append(alt.iface).append("/").append(alt.alt).append(" (").append(alt.format()).append("): ");
                UsbInterface stream = find(d, alt.iface, alt.alt);
                if (stream == null) {
                    log.append("rozhranie sa nenašlo\n");
                    continue;
                }
                if (!conn.claimInterface(stream, true)) {
                    log.append("nedá sa prevziať\n");
                    continue;
                }
                s.stream = stream;
                int rate = setRate(conn, info, alt, want);
                if (!conn.setInterface(stream)) {
                    log.append("zariadenie neprijalo nastavenie\n");
                    continue;
                }
                // Linux nastavuje hodiny aj po výbere rozhrania; niektoré zariadenia až potom začnú posielať.
                if (info.uac == 2) rate = setRate(conn, info, alt, want > 0 ? want : rate);
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }

                int base = alt.maxPacket & 0x7ff;
                int[] sizes = base == alt.packetSize ? new int[]{alt.packetSize} : new int[]{alt.packetSize, base};
                for (int size : sizes) {
                    for (int packets : new int[]{32, 8, 1}) {
                        for (int asap = 1; asap >= 0; asap--) {
                            long h = nativeOpen(conn.getFileDescriptor(), alt.endpoint, size, packets, asap,
                                    alt.channels * alt.subslot, rate, packetsPerSecond(alt, rate));
                            if (h != 0 && !failed(h)) {
                                s.handle = h;
                                s.began = System.nanoTime();
                                s.alt = alt;
                                s.rate = rate;
                                s.channels = Math.min(alt.channels, 2);
                                s.frameBytes = alt.channels * alt.subslot;
                                s.where = name(d) + " priamo cez USB (vlastný ovládač), " + alt.channels + " kan., "
                                        + alt.bits + " bit, hodiny " + rate + " Hz";
                                return s;
                            }
                            log.append(packets).append("×").append(size).append(asap == 1 ? "" : " bez ASAP")
                                    .append(": ").append(errno(h)).append("; ");
                        }
                    }
                }
                log.append("\n");
                UsbInterface zero = find(d, alt.iface, 0);
                if (zero != null) conn.setInterface(zero);
            }
            throw new IOException("Prenos z USB sa nedá spustiť.\n" + log);
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }
}
