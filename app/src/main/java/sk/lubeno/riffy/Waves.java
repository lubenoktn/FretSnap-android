package sk.lubeno.riffy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Náhľad priebehu nahrávok: {@link #COLUMNS} stĺpcov 0–255. Počíta sa raz na pozadí,
 * potom sa berie z pamäte alebo zo súboru v cache aplikácie.
 */
final class Waves {
    static final int COLUMNS = 160;

    interface Opener {
        InputStream open() throws IOException;
    }

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Map<String, byte[]> MEMORY = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();

    private Waves() {
    }

    /** Náhľad, ak už je; inak null a po výpočte zavolá done (na pozadí). */
    static synchronized byte[] get(File cacheDir, String key, Opener opener, Runnable done) {
        byte[] p = MEMORY.get(key);
        if (p != null || PENDING.contains(key)) return p;
        PENDING.add(key);
        WORK.execute(() -> {
            byte[] result = load(cacheDir, key);
            if (result == null) {
                try (InputStream in = opener.open()) {
                    result = compute(in, COLUMNS);
                    store(cacheDir, key, result);
                } catch (IOException | RuntimeException e) {
                    result = new byte[0];
                }
            }
            synchronized (Waves.class) {
                MEMORY.put(key, result);
                PENDING.remove(key);
            }
            if (done != null) done.run();
        });
        return null;
    }

    private static File file(File cacheDir, String key) {
        return new File(new File(cacheDir, "vlny"), key.replaceAll("[^A-Za-z0-9_-]", "_") + ".bin");
    }

    private static byte[] load(File cacheDir, String key) {
        File f = file(cacheDir, key);
        if (f.length() != COLUMNS) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[COLUMNS];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) return null;
                n += r;
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    private static void store(File cacheDir, String key, byte[] b) {
        File f = file(cacheDir, key);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(b);
        } catch (IOException ignored) {
        }
    }

    /**
     * Najväčšia výchylka v každom stĺpci, pomerne k najhlasnejšiemu miestu nahrávky
     * (mierne zosilnené tiché časti, aby bolo vidno doznievanie).
     */
    static byte[] compute(InputStream stream, int columns) throws IOException {
        InputStream in = new BufferedInputStream(stream, 1 << 16);
        Wav.Info info = Wav.read(in);
        int fb = info.frameBytes(), sb = info.bits / 8;
        long frames = info.frames();
        int[] peak = new int[columns];
        if (frames <= 0 || fb == 0 || sb == 0) return new byte[columns];
        byte[] buf = new byte[fb * 4096];
        long frame = 0;
        int shift = 32 - info.bits;
        while (frame < frames) {
            int want = (int) Math.min(buf.length / fb, frames - frame) * fb;
            int got = 0;
            while (got < want) {
                int r = in.read(buf, got, want - got);
                if (r < 0) break;
                got += r;
            }
            int n = got / fb;
            if (n == 0) break;
            for (int i = 0; i < n; i++, frame++) {
                int col = (int) (frame * columns / frames);
                for (int ch = 0; ch < info.channels; ch++) {
                    int at = i * fb + ch * sb;
                    int v;
                    if (sb == 2) v = (short) ((buf[at] & 0xff) | buf[at + 1] << 8);
                    else v = Wav.le(buf, at, sb) << shift >> shift;
                    if (v < 0) v = -v;
                    if (v > peak[col]) peak[col] = v;
                }
            }
            if (got < want) break;
        }
        int max = 1;
        for (int p : peak) max = Math.max(max, p);
        byte[] out = new byte[columns];
        for (int i = 0; i < columns; i++) {
            out[i] = (byte) Math.round(255 * Math.pow(peak[i] / (double) max, 0.7));
        }
        return out;
    }
}
