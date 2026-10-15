package sk.lubeno.riffy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/** Súbory WAV: zápis 24-bitových nahrávok, čítanie hlavičky a orezanie ticha. */
final class Wav {
    static final int HEADER = 44;

    private Wav() {
    }

    static byte[] header(int rate, int channels, int bits, long data) {
        byte[] h = new byte[HEADER];
        put(h, 0, "RIFF");
        int32(h, 4, 36 + data);
        put(h, 8, "WAVEfmt ");
        int32(h, 16, 16);
        h[20] = 1;
        h[22] = (byte) channels;
        int32(h, 24, rate);
        int32(h, 28, (long) rate * channels * bits / 8);
        h[32] = (byte) (channels * bits / 8);
        h[34] = (byte) bits;
        put(h, 36, "data");
        int32(h, 40, data);
        return h;
    }

    static void put(byte[] h, int at, String s) {
        for (int i = 0; i < s.length(); i++) h[at + i] = (byte) s.charAt(i);
    }

    static void int32(byte[] h, int at, long v) {
        for (int i = 0; i < 4; i++) h[at + i] = (byte) (v >> (8 * i));
    }

    /** Zapisuje 24-bitový WAV; hlavička sa doplní pri finish, keď je známe skutočné vzorkovanie. */
    static final class Writer implements Closeable {
        final File file;
        final int channels;
        final OutputStream out;
        long frames;
        /** Vzorkovanie, keď vstup skončí skôr než nahrávanie. */
        int rate;
        private byte[] bytes = new byte[0];

        Writer(File file, int channels) throws IOException {
            this(file, channels, 0);
        }

        /**
         * rate: predbežné vzorkovanie do hlavičky hneď na začiatku, aby sa dala nahrávka
         * dokončiť aj po páde aplikácie ({@link #repair}).
         */
        Writer(File file, int channels, int rate) throws IOException {
            this.file = file;
            this.channels = channels;
            this.rate = rate;
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(rate > 0 ? header(rate, channels, 24, 0) : new byte[HEADER]);
        }

        /** Zapíše n vzoriek (32-bitových, zvuk hore) ako 24 bitov. */
        void write(int[] s, int off, int n) throws IOException {
            if (bytes.length < n * 3) bytes = new byte[n * 3];
            for (int i = 0; i < n; i++) {
                int v = s[off + i];
                bytes[3 * i] = (byte) (v >> 8);
                bytes[3 * i + 1] = (byte) (v >> 16);
                bytes[3 * i + 2] = (byte) (v >> 24);
            }
            out.write(bytes, 0, n * 3);
            frames += n / channels;
        }

        void finish(int rate) throws IOException {
            out.close();
            try (RandomAccessFile w = new RandomAccessFile(file, "rw")) {
                w.seek(0);
                w.write(header(rate, channels, 24, frames * channels * 3));
            }
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** Formát a poloha zvukových dát v súbore. */
    static final class Info {
        int rate, channels, bits;
        long dataSize;

        int frameBytes() {
            return channels * bits / 8;
        }

        long frames() {
            return frameBytes() == 0 ? 0 : dataSize / frameBytes();
        }
    }

    static int le(byte[] b, int at, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) v |= (b[at + i] & 0xff) << (8 * i);
        return v;
    }

    /** Prečíta hlavičku; prúd potom stojí na začiatku zvukových dát. */
    static Info read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] b = new byte[12];
        in.readFully(b);
        if (!new String(b, 0, 4, "US-ASCII").equals("RIFF") || !new String(b, 8, 4, "US-ASCII").equals("WAVE")) {
            throw new IOException(Tr.s(R.string.not_wav));
        }
        Info info = new Info();
        byte[] head = new byte[8];
        while (true) {
            try {
                in.readFully(head);
            } catch (EOFException e) {
                throw new IOException(Tr.s(R.string.wav_no_data));
            }
            String id = new String(head, 0, 4, "US-ASCII");
            long size = le(head, 4, 4) & 0xffffffffL;
            if (id.equals("data")) {
                info.dataSize = size;
                if (info.channels == 0) throw new IOException(Tr.s(R.string.wav_no_format));
                return info;
            }
            if (id.equals("fmt ")) {
                byte[] f = new byte[(int) size];
                in.readFully(f);
                info.channels = le(f, 2, 2);
                info.rate = le(f, 4, 4);
                info.bits = le(f, 14, 2);
            } else {
                in.skipBytes((int) size);
            }
            if ((size & 1) == 1) in.skipBytes(1);
        }
    }

    /**
     * Čo z nahrávky ponechať po orezaní ticha: rámce [od, do). Ticho je všetko pod hranicou;
     * okolo zvuku ostane okraj. Ak je celá nahrávka tichá, ponechá sa celá.
     */
    static long[] trimRange(File f, double thresholdDb, double marginSeconds) throws IOException {
        Info info;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            info = read(in);
            int fb = info.frameBytes(), sb = info.bits / 8;
            long frames = info.frames();
            double limit = Math.pow(10, thresholdDb / 20) * Math.pow(2, info.bits - 1);
            long first = -1, last = -1;
            byte[] frame = new byte[fb];
            DataInputStream data = new DataInputStream(in);
            for (long i = 0; i < frames; i++) {
                data.readFully(frame);
                for (int c = 0; c < info.channels; c++) {
                    int v = le(frame, c * sb, sb) << (32 - info.bits) >> (32 - info.bits);
                    if (Math.abs(v) > limit) {
                        if (first < 0) first = i;
                        last = i;
                        break;
                    }
                }
            }
            if (first < 0) return new long[]{0, frames};
            long margin = Math.round(marginSeconds * info.rate);
            return new long[]{Math.max(0, first - margin), Math.min(frames, last + 1 + margin)};
        }
    }

    /** Skopíruje z nášho WAV rámce [from, to) so správnou hlavičkou. */
    static void copyRange(File f, long from, long to, OutputStream out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            Info info = read(in);
            int fb = info.frameBytes();
            out.write(header(info.rate, info.channels, info.bits, (to - from) * fb));
            long skip = from * fb;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) throw new EOFException();
                skip -= s;
            }
            long left = (to - from) * fb;
            byte[] buf = new byte[1 << 16];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    // ---- Údaje aplikácie v súbore: vlastný blok za zvukovými dátami ----

    static final String EXTRA = "RFYm";

    private static ByteBuffer readAt(FileChannel ch, long pos, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) throw new EOFException();
        }
        b.flip();
        return b;
    }

    private static int le(ByteBuffer b, int at) {
        return (b.get(at) & 0xff) | (b.get(at + 1) & 0xff) << 8 | (b.get(at + 2) & 0xff) << 16 | (b.get(at + 3) & 0xff) << 24;
    }

    /** Poloha bloku id (začiatok hlavičky bloku), alebo -1; pre "data" aj ďalej za ním. */
    private static long[] find(FileChannel ch, String id) throws IOException {
        long size = ch.size(), pos = 12, dataEnd = -1;
        while (pos + 8 <= size) {
            ByteBuffer h = readAt(ch, pos, 8);
            String name = new String(new byte[]{h.get(0), h.get(1), h.get(2), h.get(3)}, StandardCharsets.US_ASCII);
            long len = le(h, 4) & 0xffffffffL;
            long next = pos + 8 + len + (len & 1);
            if (name.equals("data")) dataEnd = Math.min(next, size);
            if (name.equals(id)) return new long[]{pos, len, dataEnd};
            pos = next;
        }
        return new long[]{-1, 0, dataEnd};
    }

    /** Prečíta vlastný blok aplikácie, alebo null, ak v súbore nie je. */
    static byte[] readExtra(FileChannel ch) throws IOException {
        long[] f = find(ch, EXTRA);
        if (f[0] < 0 || f[1] > (1 << 20)) return null;
        ByteBuffer b = readAt(ch, f[0] + 8, (int) f[1]);
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    /**
     * Zapíše vlastný blok za zvukové dáta (prípadný starší nahradí). Zvuk sa nemení;
     * prehrávače bloky, ktorým nerozumejú, preskakujú.
     */
    static void writeExtra(FileChannel read, FileChannel write, byte[] payload) throws IOException {
        long end = find(read, "data")[2];
        if (end < 0) throw new IOException(Tr.s(R.string.wav_no_data));
        write.truncate(end);
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length + (payload.length & 1));
        b.put(EXTRA.getBytes(StandardCharsets.US_ASCII));
        b.putInt(Integer.reverseBytes(payload.length));
        b.put(payload);
        // Blok nepárnej dĺžky dostane vyrovnávací nulový bajt (pravidlo formátu RIFF).
        b.position(b.capacity());
        b.flip();
        write.write(b, end);
        ByteBuffer riff = ByteBuffer.allocate(4);
        riff.putInt(Integer.reverseBytes((int) (end + b.limit() - 8)));
        riff.flip();
        write.write(riff, 4);
    }

    /**
     * Dokončí nahrávku prerušenú pádom: hlavička má vzorkovanie a kanály, chýba len dĺžka.
     * Vráti počet rámcov, alebo -1, ak súbor nie je použiteľný.
     */
    static long repair(File f) throws IOException {
        Info info;
        try (InputStream in = new FileInputStream(f)) {
            info = read(in);
        } catch (IOException e) {
            return -1;
        }
        if (info.rate <= 0 || info.channels <= 0 || info.bits != 24) return -1;
        long frames = (f.length() - HEADER) / info.frameBytes();
        try (RandomAccessFile w = new RandomAccessFile(f, "rw")) {
            w.setLength(HEADER + frames * info.frameBytes());
            w.seek(0);
            w.write(header(info.rate, info.channels, 24, frames * info.frameBytes()));
        }
        return frames;
    }

    // ---- Hlasitosť ----

    /** Najvyššia hodnota a RMS (0 až 1) rámcov [from, to). */
    static double[] analyze(File f, long from, long to) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            Info info = read(in);
            int fb = info.frameBytes(), sb = info.bits / 8;
            double full = Math.pow(2, info.bits - 1), peak = 0, sum = 0;
            long n = 0;
            skipFully(in, from * fb);
            byte[] frame = new byte[fb];
            DataInputStream data = new DataInputStream(in);
            for (long i = from; i < to; i++) {
                data.readFully(frame);
                for (int c = 0; c < info.channels; c++) {
                    double v = (le(frame, c * sb, sb) << (32 - info.bits) >> (32 - info.bits)) / full;
                    peak = Math.max(peak, Math.abs(v));
                    sum += v * v;
                    n++;
                }
            }
            return new double[]{peak, n == 0 ? 0 : Math.sqrt(sum / n)};
        }
    }

    /**
     * Zosilnenie na podobnú hlasitosť riffov: cieľ RMS -18 dB, špička najviac -1 dB,
     * zmena najviac o 24 dB hore a 12 dB dole.
     */
    static double loudnessGain(double peak, double rms) {
        if (peak <= 0 || rms <= 0) return 1;
        double g = Math.min(0.125 / rms, 0.891 / peak);
        return Math.max(0.25, Math.min(16, g));
    }

    /**
     * Skopíruje rámce [from, to) so zosilnením a jemným nábehom a dozvukom
     * (aby na začiatku a konci nelúplo), so správnou hlavičkou.
     */
    static void copyProcessed(File f, long from, long to, double gain, int fadeIn, int fadeOut, OutputStream out)
            throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            Info info = read(in);
            int fb = info.frameBytes(), sb = info.bits / 8, shift = 32 - info.bits;
            long max = (1L << (info.bits - 1)) - 1, min = -(1L << (info.bits - 1));
            out.write(header(info.rate, info.channels, info.bits, (to - from) * fb));
            skipFully(in, from * fb);
            long total = to - from;
            byte[] frame = new byte[fb];
            DataInputStream data = new DataInputStream(in);
            OutputStream buf = new BufferedOutputStream(out, 1 << 16);
            for (long i = 0; i < total; i++) {
                data.readFully(frame);
                double g = gain;
                if (i < fadeIn) g *= i / (double) fadeIn;
                if (total - 1 - i < fadeOut) g *= (total - 1 - i) / (double) fadeOut;
                for (int c = 0; c < info.channels; c++) {
                    int at = c * sb;
                    long v = Math.round((le(frame, at, sb) << shift >> shift) * g);
                    v = Math.max(min, Math.min(max, v));
                    for (int k = 0; k < sb; k++) frame[at + k] = (byte) (v >> (8 * k));
                }
                buf.write(frame);
            }
            buf.flush();
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) throw new EOFException();
            n -= s;
        }
    }
}
