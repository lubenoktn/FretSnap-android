package sk.lubeno.riffy;

import java.io.File;
import java.io.IOException;

/**
 * Stále čítanie zo vstupu. Z jedného prúdu dát sa kreslí ukazovateľ úrovne, pamätá sa
 * posledných {@link #HISTORY_SECONDS} sekúnd (dajú sa uložiť aj spätne) a zapisuje nahrávka.
 * Je jedno na celú aplikáciu, aby nahrávanie prežilo aj zatvorenie obrazovky.
 */
final class Engine {
    static final int HISTORY_SECONDS = 30;

    /** Volá sa z vlákna vstupu; poslucháč si sám prepne na hlavné vlákno. */
    interface Listener {
        void onStatus(String text);

        void onLevel(int[] peak);

        /** Vstup skončil chybou (napr. odpojené zariadenie); prípadná nahrávka sa má uložiť. */
        void onFailed(String text);

        /** Vstup sa otvoril a číta sa z neho. */
        default void onOpened(Source c) {
        }

        /** Zo vstupu nič nejde, skúša sa otvoriť znova. */
        default void onRetry() {
        }

        /** Vstup sa nedá otvoriť. */
        default void onOpenFailed(String text) {
        }
    }

    /** Hotová nahrávka. */
    static final class Recorded {
        final File file;
        final int rate;
        final long frames;

        Recorded(File file, int rate, long frames) {
            this.file = file;
            this.rate = rate;
            this.frames = frames;
        }
    }

    private static Engine instance;

    static synchronized Engine get() {
        if (instance == null) instance = new Engine();
        return instance;
    }

    volatile Listener listener;
    /** Ladička dostáva ten istý prúd vzoriek; null, keď je vypnutá. */
    volatile Tuner tuner;
    private volatile float gain = 1f;
    private volatile boolean running;
    private Thread thread;
    private String key;

    private Source source;
    private int[] history;
    private int historyPos, channels;
    private long historyFrames;
    private Wav.Writer writer;
    private long recordingSince;

    synchronized boolean running() {
        return running;
    }

    synchronized boolean recording() {
        return writer != null;
    }

    synchronized long recordingSince() {
        return recordingSince;
    }

    void setGain(float g) {
        gain = g;
        synchronized (this) {
            if (source != null) source.gain = g;
        }
    }

    /** Spustí čítanie; ak už beží s tým istým kľúčom (vstup a nastavenia), nerobí nič. */
    void start(String key, Source.Opener opener) {
        synchronized (this) {
            if (running && key.equals(this.key)) return;
        }
        stop();
        Thread t = new Thread(() -> loop(opener), "vstup");
        synchronized (this) {
            this.key = key;
            running = true;
            thread = t;
        }
        t.start();
    }

    /** Zastaví čítanie (nie počas nahrávania; to treba najprv ukončiť). */
    void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            key = null;
            t = thread;
            thread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /** Koľkokrát sa vstup, z ktorého ide len ticho, skúsi otvoriť znova. */
    static final int RETRIES = 3;

    private void loop(Source.Opener opener) {
        for (int attempt = 0; ; attempt++) {
            if (!runOnce(opener, attempt, attempt < RETRIES)) break;
            Listener l = listener;
            if (l != null) {
                l.onStatus("Zariadenie sa ešte zapína, skúšam ho otvoriť znova…");
                l.onRetry();
            }
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                break;
            }
            synchronized (this) {
                if (!running || thread != Thread.currentThread()) break;
            }
        }
    }

    /**
     * Otvorí vstup a číta, kým sa nemá skončiť. Vráti true, ak treba vstup otvoriť znova:
     * zariadenie po zapojení ešte nebolo pripravené a posiela len nuly (smie len mimo nahrávania).
     */
    private boolean runOnce(Source.Opener opener, int attempt, boolean mayRetry) {
        Source c;
        try {
            c = opener.open(attempt);
        } catch (Exception e) {
            synchronized (this) {
                if (thread == Thread.currentThread()) running = false;
            }
            Listener l = listener;
            if (l != null) {
                l.onStatus(e.getMessage());
                l.onOpenFailed(e.getMessage());
            }
            return false;
        }
        c.gain = gain;
        int capacity = Math.max(c.rate, 48000);
        synchronized (this) {
            source = c;
            channels = c.channels;
            if (history == null || history.length != capacity * HISTORY_SECONDS * c.channels) {
                history = new int[capacity * HISTORY_SECONDS * c.channels];
            }
            historyPos = 0;
            historyFrames = 0;
        }
        Listener l = listener;
        if (l != null) {
            l.onStatus("Signál ide z: " + c.where);
            l.onOpened(c);
        }

        int[] buf = new int[capacity / 20 * c.channels];
        int[] peak = new int[c.channels];
        long opened = System.currentTimeMillis(), last = 0;
        boolean verified = false, retry = false;
        String failure = null;
        try {
            while (true) {
                synchronized (this) {
                    if (!running || thread != Thread.currentThread()) break;
                }
                int n = c.read(buf);
                if (n < 0) {
                    failure = c.error != null ? c.error : "Čítanie zo vstupu zlyhalo.";
                    break;
                }
                long now = System.currentTimeMillis();
                if (!verified && now - opened > Source.SETTLE_MS) {
                    verified = true;
                    synchronized (this) {
                        if (mayRetry && writer == null && c.dead()) retry = true;
                    }
                    if (retry) break;
                }
                if (n == 0) continue;
                synchronized (this) {
                    remember(buf, n);
                    if (writer != null) {
                        try {
                            writer.write(buf, 0, n);
                        } catch (IOException e) {
                            failure = "Nahrávka sa nedá zapisovať: " + e.getMessage();
                            break;
                        }
                    }
                }
                Source.peaks(buf, n, c.channels, peak);
                Tuner t = tuner;
                if (t != null) t.feed(buf, n, c.channels, c.rate);
                if (now - last > 80) {
                    last = now;
                    l = listener;
                    if (l != null) l.onLevel(peak);
                    peak = new int[c.channels];
                }
            }
        } finally {
            synchronized (this) {
                if (writer != null) writer.rate = c.finalRate();
                source = null;
                if (!retry && thread == Thread.currentThread()) running = false;
            }
            c.close();
        }
        l = listener;
        if (failure != null && l != null) l.onFailed(failure);
        return retry;
    }

    private void remember(int[] buf, int n) {
        int len = history.length;
        int first = Math.min(n, len - historyPos);
        System.arraycopy(buf, 0, history, historyPos, first);
        if (first < n) System.arraycopy(buf, first, history, 0, n - first);
        historyPos = (historyPos + n) % len;
        historyFrames += n / channels;
    }

    /** Začne zapisovať prúd do súboru. */
    synchronized void startRecording(File f) throws IOException {
        if (source == null) throw new IOException("Vstup ešte nebeží.");
        writer = new Wav.Writer(f, source.channels);
        recordingSince = System.currentTimeMillis();
    }

    /** Ukončí zápis a doplní hlavičku; vráti null, ak sa nenahrávalo. */
    Recorded stopRecording() throws IOException {
        Wav.Writer w;
        int rate;
        synchronized (this) {
            w = writer;
            writer = null;
            if (w == null) return null;
            rate = source != null ? source.finalRate() : w.rate;
        }
        w.finish(rate);
        return new Recorded(w.file, rate, w.frames);
    }

    /** Uloží posledné sekundy do súboru; vráti null, ak ešte nič neprišlo. */
    Recorded saveHistory(File f, int seconds) throws IOException {
        int[] copy;
        int ch, rate;
        long frames;
        synchronized (this) {
            if (history == null || historyFrames == 0 || source == null) return null;
            ch = channels;
            rate = source.finalRate();
            frames = Math.min(Math.min(historyFrames, history.length / ch), (long) rate * seconds);
            int samples = (int) (frames * ch);
            copy = new int[samples];
            int start = Math.floorMod(historyPos - samples, history.length);
            int first = Math.min(samples, history.length - start);
            System.arraycopy(history, start, copy, 0, first);
            if (first < samples) System.arraycopy(history, 0, copy, first, samples - first);
        }
        Wav.Writer w = new Wav.Writer(f, ch);
        w.write(copy, 0, copy.length);
        w.finish(rate);
        return new Recorded(f, rate, frames);
    }
}
