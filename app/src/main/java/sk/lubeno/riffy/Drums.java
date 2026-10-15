package sk.lubeno.riffy;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sprievodné bicie: hotové rytmy (rock, metal, reggae, balada) v 4/4, prechod každých
 * niekoľko taktov a činel po ňom. Hrajú buď samé (k hraniu a nahrávaniu), alebo idú
 * za prehrávanou nahrávkou ({@link Clock}), aby sedeli s riffom aj pri slučke a spomalení.
 */
final class Drums {
    static final int RATE = 48000;
    static final String[] STYLES = {"Rock", "Metal", "Reggae", "Balada"};
    static final String[] FILLS = {"každé 4 takty", "každých 8 taktov", "bez prechodov"};
    static final int[] FILL_BARS = {4, 8, 0};

    static final int KICK = 0, SNARE = 1, RIM = 2, HAT = 3, OPEN = 4, CRASH = 5, RIDE = 6,
            TOM1 = 7, TOM2 = 8, TOM3 = 9, STICK = 10, SOUNDS = 11;
    private static final int[] RAW = {R.raw.d_kick, R.raw.d_snare, R.raw.d_rim, R.raw.d_hat, R.raw.d_open,
            R.raw.d_crash, R.raw.d_ride, R.raw.d_tom1, R.raw.d_tom2, R.raw.d_tom3, R.raw.d_stick};

    /** Kde je prehrávaná nahrávka (ms v súbore) a či hrá. */
    interface Clock {
        boolean playing();

        double positionMs();

        float speed();
    }

    /** Jeden takt: hlasitosť úderu (0 = nič) pre každý nástroj a každú šestnástinu. */
    static final class Bar {
        final float[][] hits = new float[SOUNDS][16];

        /** Zápis „K X.......x.X.....“: písmeno nástroja, potom 16 znakov; X silno, x stredne, o jemne. */
        static Bar of(String... lines) {
            Bar b = new Bar();
            for (String line : lines) {
                int sound = "KSRHOCDABFP".indexOf(line.charAt(0));
                String steps = line.substring(2);
                for (int i = 0; i < 16 && i < steps.length(); i++) {
                    char c = steps.charAt(i);
                    b.hits[sound][i] = c == 'X' ? 1f : c == 'x' ? 0.72f : c == 'o' ? 0.42f : 0f;
                }
            }
            return b;
        }
    }

    static final class Style {
        final Bar[] grooves;
        final Bar fill;
        /** Oneskorenie párnych osmín (0 = rovno, 0,67 = triolový pocit). */
        final float swing;

        Style(float swing, Bar fill, Bar... grooves) {
            this.swing = swing;
            this.fill = fill;
            this.grooves = grooves;
        }
    }

    // Nástroje v zápise: K kopák, S rytmičák, R obruč, H hi-hat, O otvorený hi-hat, C činel,
    // D ride, A/B/F tomy (vysoký, stredný, kotol), P paličky.
    static final Style[] PATTERNS = {
            new Style(0,
                    Bar.of("H X.x.x.x.", "S ....X...X.x.....", "B ............Xx..", "F ..............XX",
                            "K X.......X......."),
                    Bar.of("H X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.......X.X....."),
                    Bar.of("H X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.......X.X...x.")),
            new Style(0,
                    Bar.of("D X...X...", "S ....X...XxXx....", "A ............Xx..", "F ..............XX",
                            "K xxxxxxxxxxxxxxxx"),
                    Bar.of("D X.x.X.x.X.x.X.x.", "S ....X.......X...", "K xxxxxxxxxxxxxxxx")),
            new Style(0.67f,
                    Bar.of("H x.x.x.x.", "K ........X.......", "S ........X.x.....", "A ............X...",
                            "F ..............X."),
                    Bar.of("H x.X.x.X.x.X.x.X.", "R ........X.......", "K ........X.......", "O ..............o."),
                    Bar.of("H x.X.x.X.x.X.x.X.", "R ........X.......", "K ........X.......")),
            new Style(0,
                    Bar.of("H o.o.o.o.", "K X.......X.......", "S ........o.x.....", "A ............x.x.",
                            "F ..............xx"),
                    Bar.of("H o.o.o.o.o.o.o.o.", "R ....x.......x...", "K X.....o.X......."),
                    Bar.of("H o.o.o.o.o.o.o.o.", "R ....x.......x...", "K X.....o.X.....o.")),
    };

    private static final Bar COUNT_IN = Bar.of("P X...x...x...x...");
    private static final float[] LEVEL = {1f, 0.9f, 0.8f, 0.55f, 0.5f, 0.6f, 0.45f, 0.85f, 0.85f, 0.9f, 0.8f};

    private static short[][] sounds;

    static synchronized void load(Context c) {
        if (sounds != null) return;
        short[][] s = new short[SOUNDS][];
        for (int i = 0; i < SOUNDS; i++) {
            try (InputStream in = c.getResources().openRawResource(RAW[i])) {
                byte[] b = new byte[64 * 1024];
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(b)) > 0) all.write(b, 0, n);
                s[i] = toShorts(all.toByteArray());
            } catch (IOException e) {
                s[i] = new short[0];
            }
        }
        sounds = s;
    }

    static short[] toShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | b[2 * i + 1] << 8);
        return s;
    }

    /**
     * Skladá zvuk: posúva sa po dobách a spúšťa údery. Bez Androidu, aby sa dal vyskúšať v testoch.
     */
    static final class Sequencer {
        final short[][] kit;
        volatile int style, fillBars = 8;
        volatile float bpm = 100, volume = 0.8f;
        /** Poloha v dobách (0 = prvá doba prvého taktu; záporné = odpočet). */
        double beat;
        boolean countIn;
        private long next;
        private final short[][] voice = new short[24][];
        private final int[] voicePos = new int[24], voiceSound = new int[24];
        private final float[] voiceGain = new float[24];
        /** Pri doznievaní (utlmenie novým úderom) ubúda hlasitosť o toľko za vzorku; 0 = doznieva prirodzene. */
        private final float[] voiceFade = new float[24];

        Sequencer(short[][] kit) {
            this.kit = kit;
        }

        /** Skok na danú dobu (začiatok, odpočet, posun prehrávania). */
        void seek(double toBeat) {
            beat = toBeat;
            Style s = PATTERNS[style];
            long n = (long) Math.floor(toBeat * 4);
            while (time(n, s) < toBeat - 1e-9) n++;
            next = n;
        }

        /** Kedy (v dobách) zaznie šestnástina n; párne osminy sa pri swingu oneskoria. */
        static double time(long n, Style s) {
            return n / 4.0 + (Math.floorMod(n, 4) == 2 ? s.swing * 0.25 : 0);
        }

        Bar barFor(long bar) {
            Style s = PATTERNS[style];
            if (bar < 0) return countIn ? COUNT_IN : s.grooves[0];
            int every = fillBars;
            if (every > 0 && bar % every == every - 1) return s.fill;
            return s.grooves[(int) ((bar / 2) % s.grooves.length)];
        }

        private void trigger(long n) {
            long bar = Math.floorDiv(n, 16);
            int step = (int) Math.floorMod(n, 16);
            Bar b = barFor(bar);
            for (int i = 0; i < SOUNDS; i++) {
                float v = b.hits[i][step];
                if (i == CRASH && step == 0 && bar >= 0 && (bar == 0 || (fillBars > 0 && bar % fillBars == 0))) {
                    v = Math.max(v, 0.9f);
                }
                if (v > 0) play(i, v);
            }
        }

        private void play(int sound, float v) {
            // Nový úder na ten istý bubon (alebo zatvorený hi-hat na otvorený) stlmí predošlý, ako na skutočnej súprave.
            boolean damp = sound != CRASH && sound != RIDE && sound != OPEN;
            for (int k = 0; k < voice.length; k++) {
                if (voice[k] == null) continue;
                if ((damp && voiceSound[k] == sound) || (sound == HAT && voiceSound[k] == OPEN)) {
                    voiceFade[k] = voiceGain[k] / (RATE * 0.004f);
                }
            }
            int free = 0;
            for (int k = 0; k < voice.length; k++) {
                if (voice[k] == null) {
                    free = k;
                    break;
                }
                if (voicePos[k] > voicePos[free]) free = k;
            }
            voice[free] = kit[sound];
            voicePos[free] = 0;
            voiceSound[free] = sound;
            voiceGain[free] = v * LEVEL[sound];
            voiceFade[free] = 0;
        }

        /** Vyplní out ďalšími vzorkami; rate = koľko dôb pribudne za vzorku je bpm·rýchlosť/60/RATE. */
        void render(short[] out, int n, float speed) {
            Style s = PATTERNS[style];
            double perSample = bpm * speed / 60.0 / RATE;
            for (int i = 0; i < n; i++) {
                while (time(next, s) <= beat + 1e-9) {
                    trigger(next);
                    next++;
                }
                beat += perSample;
                float mix = 0;
                for (int k = 0; k < voice.length; k++) {
                    short[] v = voice[k];
                    if (v == null) continue;
                    mix += v[voicePos[k]++] * voiceGain[k];
                    if (voiceFade[k] > 0) voiceGain[k] -= voiceFade[k];
                    if (voicePos[k] >= v.length || voiceGain[k] <= 0) voice[k] = null;
                }
                mix *= volume / 32768f;
                // Mäkké obmedzenie, aby súčet úderov neprebudil
                float x = Math.max(-1.5f, Math.min(1.5f, mix));
                float y = x - x * x * x / 6.75f;
                out[i] = (short) (y * 30000);
            }
        }
    }

    private volatile boolean running;
    private Thread thread;
    private volatile Sequencer seq;
    private volatile Output track;
    private volatile Clock clock;
    private volatile double firstBeatMs;
    // Ktorej dobe zodpovedá ktorá vzorka poslaná do zvuku (na zistenie, kde v takte práve je).
    private long anchorFrame;
    private double anchorBeat, beatsPerFrame;

    volatile int style, fillBars = 8;
    volatile float bpm = 100, volume = 0.8f;

    boolean running() {
        return running;
    }

    /** Hrá samo; s odpočtom paličkami (jeden takt) pred prvou dobou. */
    void start(Context c, Output out, boolean countIn) {
        stop();
        load(c);
        clock = null;
        Sequencer s = new Sequencer(sounds);
        s.countIn = countIn;
        configure(s);
        s.seek(countIn ? -4 : 0);
        begin(s, out);
    }

    /** Ide za prehrávanou nahrávkou; firstBeatMs = kde v nej je prvá doba taktu. */
    void follow(Context c, Output out, Clock source, double firstBeatMs) {
        stop();
        load(c);
        clock = source;
        this.firstBeatMs = firstBeatMs;
        Sequencer s = new Sequencer(sounds);
        configure(s);
        s.seek(target(source));
        begin(s, out);
    }

    boolean following() {
        return running && clock != null;
    }

    private void configure(Sequencer s) {
        s.style = Math.max(0, Math.min(style, PATTERNS.length - 1));
        s.fillBars = fillBars;
        s.bpm = bpm;
        s.volume = volume;
    }

    private double target(Clock c) {
        return (c.positionMs() - firstBeatMs) * bpm / 60000.0;
    }

    private void begin(Sequencer s, Output out) {
        seq = s;
        running = true;
        thread = new Thread(() -> loop(s, out), "bicie");
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Na ktorej dobe (od prvej doby po štarte) práve je to, čo z telefónu znie; NaN, ak bicie nehrajú.
     * Podľa toho sa k nahrávke uloží, kde v nej začína takt.
     */
    synchronized double audibleBeat() {
        Output t = track;
        if (!running || t == null || clock != null) return Double.NaN;
        long head = t.played();
        return anchorBeat + (head - anchorFrame) * beatsPerFrame;
    }

    private void loop(Sequencer s, Output t) {
        track = t;
        short[] chunk = new short[RATE / 100];
        long written = 0;
        float lastBpm = -1;
        try {
            while (running) {
                // Zmeny z obrazovky (tempo, štýl, hlasitosť) platia hneď.
                s.style = style;
                s.fillBars = fillBars;
                s.volume = volume;
                Clock c = clock;
                float speed = 1;
                if (c != null) {
                    if (!c.playing()) {
                        java.util.Arrays.fill(chunk, (short) 0);
                        t.write(chunk, chunk.length);
                        written += chunk.length;
                        continue;
                    }
                    speed = c.speed();
                    double want = target(c);
                    // Viac než ~35 ms vedľa (skok, slučka, začiatok): preskočiť tam.
                    if (Math.abs(want - s.beat) * 60000.0 / bpm > 35) s.seek(want);
                }
                if (bpm != lastBpm) {
                    s.bpm = bpm;
                    lastBpm = bpm;
                    synchronized (this) {
                        anchorFrame = written;
                        anchorBeat = s.beat;
                        beatsPerFrame = bpm / 60.0 / RATE;
                    }
                }
                s.render(chunk, chunk.length, speed);
                t.write(chunk, chunk.length);
                written += chunk.length;
            }
        } finally {
            track = null;
            t.close();
        }
    }
}
