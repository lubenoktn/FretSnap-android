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
            this.file = file;
            this.channels = channels;
            out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16);
            out.write(new byte[HEADER]);
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
            throw new IOException("Súbor nie je WAV.");
        }
        Info info = new Info();
        byte[] head = new byte[8];
        while (true) {
            try {
                in.readFully(head);
            } catch (EOFException e) {
                throw new IOException("Vo WAV chýbajú zvukové dáta.");
            }
            String id = new String(head, 0, 4, "US-ASCII");
            long size = le(head, 4, 4) & 0xffffffffL;
            if (id.equals("data")) {
                info.dataSize = size;
                if (info.channels == 0) throw new IOException("Vo WAV chýba popis formátu.");
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
}
