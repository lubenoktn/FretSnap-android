package sk.lubeno.riffy;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * Prehrávač rytmu: metronóm, sprievodné bicie (rock, metal, reggae, balada, blues shuffle, funk,
 * punk, 6/8, 3/4) a slučka loopera v jednom zvuku a na jednej časovej osi, aby k sebe sedeli.
 * Hrá buď sám (k hraniu, nahrávaniu a looperu), alebo ide za prehrávanou nahrávkou
 * ({@link Clock}), aby bicie sedeli s riffom aj pri slučke a spomalení.
 *
 * Zvuky (res/raw/d_*.pcm, mono 48 kHz 16 bit) sú zo súpravy Virtuosity Drums od Versilian Studios
 * (CC0, github.com/sfzinstruments/virtuosity_drums): blízke mikrofóny a mikrofóny nad súpravou
 * zmiešané do jedného úderu. Paličky na odpočet (d_stick) a cvaknutia metronómu sú vypočítané.
 */
final class Drums {
    static final int RATE = 48000;
    static final String[] FILLS = {"každé 4 takty", "každých 8 taktov", "bez prechodov"};
    static final int[] FILL_BARS = {4, 8, 0};
    /** Čo hrá: nič (len slučka), metronóm, bicie. */
    static final int OFF = 0, CLICK = 1, DRUMS = 2;

    static final int KICK = 0, SNARE = 1, RIM = 2, HAT = 3, OPEN = 4, CRASH = 5, RIDE = 6,
            TOM1 = 7, TOM2 = 8, TOM3 = 9, STICK = 10, CLICK_HI = 11, CLICK_LO = 12, SOUNDS = 13;
    private static final int[] RAW = {R.raw.d_kick, R.raw.d_snare, R.raw.d_rim, R.raw.d_hat, R.raw.d_open,
            R.raw.d_crash, R.raw.d_ride, R.raw.d_tom1, R.raw.d_tom2, R.raw.d_tom3, R.raw.d_stick};

    /** Kde je prehrávaná nahrávka (ms v súbore) a či hrá. */
    interface Clock {
        boolean playing();

        double positionMs();

        float speed();
    }

    /** Jeden takt: hlasitosť úderu (0 = nič) pre každý nástroj a každý krok taktu. */
    static final class Bar {
        final float[][] hits;

        Bar(int steps) {
            hits = new float[SOUNDS][steps];
        }

        /** Zápis „K X.......x.X.....“: písmeno nástroja, potom kroky; X silno, x stredne, o jemne. */
        static Bar of(int steps, String... lines) {
            Bar b = new Bar(steps);
            for (String line : lines) {
                int sound = "KSRHOCDABFP".indexOf(line.charAt(0));
                String s = line.substring(2);
                for (int i = 0; i < steps && i < s.length(); i++) {
                    char c = s.charAt(i);
                    b.hits[sound][i] = c == 'X' ? 1f : c == 'x' ? 0.72f : c == 'o' ? 0.42f : 0f;
                }
            }
            return b;
        }
    }

    static final class Style {
        final String name;
        /** Doby v takte a kroky na dobu (4 = šestnástiny; pri 6/8 je doba bodkovaná štvrťová a krok osmina). */
        final int beats, perBeat;
        /** Oneskorenie párnych osmín (0 = rovno, 0,67 = triolový pocit). */
        final float swing;
        /** Hlasitosť štýlu, aby boli štýly rovnako hlasné. */
        final float gain;
        final Bar fill;
        final Bar[] grooves;

        Style(String name, int beats, int perBeat, float swing, float gain, String[] fill, String[]... grooves) {
            this.name = name;
            this.beats = beats;
            this.perBeat = perBeat;
            this.swing = swing;
            this.gain = gain;
            this.fill = Bar.of(beats * perBeat, fill);
            this.grooves = new Bar[grooves.length];
            for (int i = 0; i < grooves.length; i++) this.grooves[i] = Bar.of(beats * perBeat, grooves[i]);
        }

        int steps() {
            return beats * perBeat;
        }
    }

    static String[] l(String... s) {
        return s;
    }

    // Nástroje v zápise: K kopák, S rytmičák, R obruč, H hi-hat, O otvorený hi-hat, C činel,
    // D ride, A/B/F tomy (vysoký, stredný, kotol), P paličky.
    static final Style[] PATTERNS = {
            new Style("Rock", 4, 4, 0, 1f,
                    l("H X.x.x.x.", "S ....X...X.x.....", "B ............Xx..", "F ..............XX", "K X.......X......."),
                    l("H X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.......X.X....."),
                    l("H X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.......X.X...x.")),
            new Style("Metal", 4, 4, 0, 0.7f,
                    l("D X...X...", "S ....X...XxXx....", "A ............Xx..", "F ..............XX", "K xxxxxxxxxxxxxxxx"),
                    l("D X.x.X.x.X.x.X.x.", "S ....X.......X...", "K xxxxxxxxxxxxxxxx")),
            new Style("Reggae", 4, 4, 0.67f, 1.8f,
                    l("H x.x.x.x.", "K ........X.......", "S ........X.x.....", "A ............X...", "F ..............X."),
                    l("H x.X.x.X.x.X.x.X.", "R ........X.......", "K ........X.......", "O ..............o."),
                    l("H x.X.x.X.x.X.x.X.", "R ........X.......", "K ........X.......")),
            new Style("Balada", 4, 4, 0, 1.45f,
                    l("H o.o.o.o.", "K X.......X.......", "S ........o.x.....", "A ............x.x.", "F ..............xx"),
                    l("H o.o.o.o.o.o.o.o.", "R ....x.......x...", "K X.....o.X......."),
                    l("H o.o.o.o.o.o.o.o.", "R ....x.......x...", "K X.....o.X.....o.")),
            new Style("Blues shuffle", 4, 4, 0.67f, 1.25f,
                    l("D X.x.X.x.", "S ....X...X.x.X.XX", "K X.......X.......", "F ..............X."),
                    l("D X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.......X.....x."),
                    l("H X.x.X.x.X.x.X.x.", "S ....X.......X...", "K X.....x.X.......")),
            new Style("Funk", 4, 4, 0, 1.1f,
                    l("H x.x.x.x.", "S ....X...XoXoXxXX", "K X.....x.........", "A ............X...", "F ..............X."),
                    l("H xoxoxoxoxoxoxoxo", "S ....X..o.o..X..o", "K X.....X...X..X.."),
                    l("H xoxoxoxoxoxoxoxo", "S ....X..o.o..X...", "K X.X...X.....X...")),
            new Style("Punk", 4, 4, 0, 0.8f,
                    l("D x.x.x.x.", "S ........XxXxXxXx", "K X...X...X.......", "F ..............X."),
                    l("D x.x.x.x.x.x.x.x.", "S ..X...X...X...X.", "K X...X...X...X...")),
            new Style("6/8", 2, 3, 0, 1.3f,
                    l("H x..", "S ..xxX.", "K X.....", "F .....X"),
                    l("H XxxXxx", "S ...X..", "K X....."),
                    l("H XxxXxx", "S ...X..", "K X...x.")),
            new Style("3/4", 3, 4, 0, 1.3f,
                    l("H X.x.", "S ....X.X.XXXX", "K X...........", "F ..........XX"),
                    l("H X.x.x.x.x.x.", "S ....X...X...", "K X..........."),
                    l("H X.x.x.x.x.x.", "S ....X...X...", "K X.......x...")),
    };

    static final String[] STYLES = new String[PATTERNS.length];

    static {
        for (int i = 0; i < PATTERNS.length; i++) STYLES[i] = PATTERNS[i].name;
    }

    static int styleIndex(String name) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(name)) return i;
        return -1;
    }

    private static final float[] LEVEL = {1f, 0.9f, 0.8f, 0.55f, 0.5f, 0.6f, 0.45f, 0.85f, 0.85f, 0.9f, 0.8f, 0.5f, 0.4f};

    private static short[][] sounds;

    static synchronized short[][] load(Context c) {
        if (sounds != null) return sounds;
        short[][] s = new short[SOUNDS][];
        for (int i = 0; i < RAW.length; i++) {
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
        Backing.load(c);
        s[CLICK_HI] = click(1760);
        s[CLICK_LO] = click(1100);
        sounds = s;
        return s;
    }

    static short[] click(double freq) {
        short[] s = new short[RATE * 30 / 1000];
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            s[i] = (short) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * 120) * 0.9 * 32767);
        }
        return s;
    }

    static short[] toShorts(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | b[2 * i + 1] << 8);
        return s;
    }

    /** Mäkké obmedzenie (súčet zvukov neprebudí); vstup aj výstup okolo ±1. */
    static short soft(float mix) {
        float x = Math.max(-1.5f, Math.min(1.5f, mix));
        return (short) ((x - x * x * x / 6.75f) * 30000);
    }

    /**
     * Skladá zvuk: posúva sa po dobách a spúšťa údery. Bez Androidu, aby sa dal vyskúšať v testoch.
     */
    static final class Sequencer {
        final short[][] kit;
        volatile int style, fillBars = 8, mode = DRUMS;
        /** Doby v takte metronómu. */
        volatile int clickBeats = 4;
        volatile float bpm = 100, volume = 0.8f;
        /** Sprievod (basa, klávesy), alebo null. */
        volatile Backing backing;
        static final int BASS_TAG = 100, KEYS_TAG = 101;
        /** Poloha v dobách (0 = prvá doba prvého taktu; záporné = odpočet). */
        double beat;
        boolean countIn;
        private long next;
        private int lastStyle = -1, lastMode = -1;
        private final short[][] voice = new short[24][];
        private final int[] voicePos = new int[24], voiceSound = new int[24];
        private final float[] voiceGain = new float[24];
        /** Pri doznievaní (utlmenie novým úderom) ubúda hlasitosť o toľko za vzorku; 0 = doznieva prirodzene. */
        private final float[] voiceFade = new float[24];

        Sequencer(short[][] kit) {
            this.kit = kit;
        }

        Style current() {
            return PATTERNS[Math.max(0, Math.min(style, PATTERNS.length - 1))];
        }

        /** Doby v takte toho, čo práve hrá (metronóm má vlastné). */
        int beatsPerBar() {
            return mode == DRUMS ? current().beats : Math.max(1, clickBeats);
        }

        int perBeat() {
            return mode == DRUMS ? current().perBeat : 1;
        }

        /** Skok na danú dobu (začiatok, odpočet, posun prehrávania). */
        void seek(double toBeat) {
            beat = toBeat;
            lastStyle = style;
            lastMode = mode;
            int p = perBeat();
            long n = (long) Math.floor(toBeat * p);
            while (time(n) < toBeat - 1e-9) n++;
            next = n;
        }

        /** Kedy (v dobách) zaznie krok n; párne osminy sa pri swingu oneskoria. */
        double time(long n) {
            int p = perBeat();
            float swing = mode == DRUMS && p == 4 ? current().swing : 0;
            return n / (double) p + (p == 4 && Math.floorMod(n, 4) == 2 ? swing * 0.25 : 0);
        }

        /** Na ktorej dobe začína takt, v ktorom je doba b (pri odpočte takty idú od -1). */
        double barStart(double b) {
            int bpb = beatsPerBar();
            return Math.floor(b / bpb) * bpb;
        }

        Bar barFor(long bar) {
            Style s = current();
            if (bar < 0) return countIn ? null : s.grooves[0];
            int every = fillBars;
            if (every > 0 && bar % every == every - 1) return s.fill;
            return s.grooves[(int) ((bar / 2) % s.grooves.length)];
        }

        private void trigger(long n) {
            int p = perBeat(), bpb = beatsPerBar(), steps = bpb * p;
            long bar = Math.floorDiv(n, steps);
            int step = (int) Math.floorMod(n, steps);
            if (bar < 0 && countIn) {
                // Odpočet: paličky na každú dobu (metronóm cvaká).
                if (step % p == 0) {
                    if (mode == CLICK) play(step == 0 ? CLICK_HI : CLICK_LO, 1f);
                    else play(STICK, step == 0 ? 1f : 0.72f);
                }
                return;
            }
            Backing bk = backing;
            if (mode == OFF || mode == CLICK) {
                if (mode == CLICK) play(step == 0 ? CLICK_HI : CLICK_LO, 1f);
                if (bk != null) accompany(bk, bar, step, p, false, false);
                return;
            }
            Bar b = barFor(bar);
            for (int i = 0; i < STICK; i++) {
                float v = b.hits[i][step];
                if (i == CRASH && step == 0 && bar > 0 && fillBars > 0 && bar % fillBars == 0) {
                    v = Math.max(v, 0.9f);
                }
                if (v > 0) play(i, v);
            }
            if (bk != null) accompany(bk, bar, step, p, true, b.hits[KICK][step] > 0);
        }

        /**
         * Basa hrá základný tón akordu spolu s kopákom (bez bicích na 1. a 3. dobu), klávesy celý
         * akord na začiatku taktu.
         */
        private void accompany(Backing bk, long bar, int step, int p, boolean drums, boolean kick) {
            int ci = bk.chordAt(bar);
            if (ci < 0) return;
            int root = bk.roots[ci], kind = bk.kinds[ci];
            boolean bassHit = drums ? kick || step == 0 : step % p == 0 && (step / p) % 2 == 0;
            if (bk.bass && bassHit) play(Backing.bass(Backing.bassMidi(root)), 0.5f, BASS_TAG, true);
            if (bk.keys && step == 0) {
                for (int k = 0; k < voice.length; k++) {
                    if (voice[k] != null && voiceSound[k] == KEYS_TAG) voiceFade[k] = voiceGain[k] / (RATE * 0.03f);
                }
                for (int m : Backing.voicing(root, kind)) play(Backing.key(m), 0.15f, KEYS_TAG, false);
            }
        }

        private void play(int sound, float v) {
            // Nový úder na ten istý bubon (alebo zatvorený hi-hat na otvorený) stlmí predošlý, ako na skutočnej súprave.
            boolean damp = sound != CRASH && sound != RIDE && sound != OPEN;
            if (sound == HAT) {
                for (int k = 0; k < voice.length; k++) {
                    if (voice[k] != null && voiceSound[k] == OPEN) voiceFade[k] = voiceGain[k] / (RATE * 0.004f);
                }
            }
            play(kit[sound], v * LEVEL[sound], sound, damp);
        }

        /** Spustí zvuk; damp = predošlý zvuk s tou istou značkou sa stlmí. */
        private void play(short[] sample, float gain, int tag, boolean damp) {
            if (sample == null || sample.length == 0) return;
            for (int k = 0; k < voice.length; k++) {
                if (voice[k] != null && damp && voiceSound[k] == tag) voiceFade[k] = voiceGain[k] / (RATE * 0.004f);
            }
            int free = 0;
            for (int k = 0; k < voice.length; k++) {
                if (voice[k] == null) {
                    free = k;
                    break;
                }
                if (voicePos[k] > voicePos[free]) free = k;
            }
            voice[free] = sample;
            voicePos[free] = 0;
            voiceSound[free] = tag;
            voiceGain[free] = gain;
            voiceFade[free] = 0;
        }

        /** Ďalšie vzorky (pred obmedzením, okolo ±1) pripočíta k out. */
        void mix(float[] out, int n, float speed) {
            if (style != lastStyle || mode != lastMode) seek(beat);
            float g = volume * 1.9f * (mode == DRUMS ? current().gain : 1f) / 32768f;
            double perSample = bpm * speed / 60.0 / RATE;
            for (int i = 0; i < n; i++) {
                while (time(next) <= beat + 1e-9) {
                    trigger(next);
                    next++;
                }
                beat += perSample;
                float m = 0;
                for (int k = 0; k < voice.length; k++) {
                    short[] v = voice[k];
                    if (v == null) continue;
                    m += v[voicePos[k]++] * voiceGain[k];
                    if (voiceFade[k] > 0) voiceGain[k] -= voiceFade[k];
                    if (voicePos[k] >= v.length || voiceGain[k] <= 0) voice[k] = null;
                }
                out[i] += m * g;
            }
        }

        /** Vyplní out ďalšími vzorkami. */
        void render(short[] out, int n, float speed) {
            float[] f = new float[n];
            mix(f, n, speed);
            for (int i = 0; i < n; i++) out[i] = soft(f[i]);
        }
    }

    // ---- Prehrávač ----

    private volatile boolean running;
    private Thread thread;
    private volatile Sequencer seq;
    private volatile Output track;
    private volatile Clock clock;
    private volatile double firstBeatMs;
    /** Koľko rámcov (48 kHz) už prehrávač poslal; časová os loopera. */
    private volatile long written;
    // Ktorej dobe zodpovedá ktorý rámec (pri zmene tempa sa posunie).
    private long anchorFrame;
    private double anchorBeat, beatsPerFrame;

    volatile int style, fillBars = 8, mode = DRUMS, clickBeats = 4;
    /** Sprievod k rytmu (len keď prehrávač hrá sám), alebo null. */
    volatile Backing backing;
    volatile float bpm = 100, volume = 0.8f;

    /** Slučka loopera: mono 48 kHz, index 0 zaznie na rámci loopStart; null = žiadna. */
    volatile float[] loop;
    volatile long loopStart;
    volatile float loopGain = 1f;

    boolean running() {
        return running;
    }

    /** Hrá samo (metronóm, bicie alebo len slučka); s odpočtom jedného taktu pred prvou dobou. */
    void start(Context c, Output out, int mode, boolean countIn) {
        stop();
        clock = null;
        this.mode = mode;
        Sequencer s = new Sequencer(load(c));
        s.countIn = countIn;
        configure(s);
        s.seek(countIn ? -s.beatsPerBar() : 0);
        // Slučka loopera začína na prvej dobe novej časovej osi.
        if (loop != null) loopStart = countIn ? Math.round(s.beatsPerBar() * 60.0 / bpm * RATE) : 0;
        begin(s, out);
    }

    /** Ide za prehrávanou nahrávkou; firstBeatMs = kde v nej je prvá doba taktu. */
    void follow(Context c, Output out, Clock source, double firstBeatMs) {
        stop();
        clock = source;
        this.firstBeatMs = firstBeatMs;
        mode = DRUMS;
        Sequencer s = new Sequencer(load(c));
        configure(s);
        s.seek(target(source, 0));
        begin(s, out);
    }

    boolean following() {
        return running && clock != null;
    }

    /** Hrá sám (nie za nahrávkou) a niečo počuť (metronóm alebo bicie). */
    boolean playing(int what) {
        return running && clock == null && mode == what;
    }

    private void configure(Sequencer s) {
        s.style = Math.max(0, Math.min(style, PATTERNS.length - 1));
        s.fillBars = fillBars;
        s.bpm = bpm;
        s.volume = volume;
        s.mode = mode;
        s.clickBeats = clickBeats;
    }

    /** Doba v nahrávke o aheadMs neskôr (keď zaznie to, čo sa práve posiela). */
    private double target(Clock c, double aheadMs) {
        return (c.positionMs() + aheadMs * c.speed() - firstBeatMs) * bpm / 60000.0;
    }

    /** Doladená prvá doba prehrávaného riffu. */
    void setFirstBeat(double ms) {
        firstBeatMs = ms;
    }

    private void begin(Sequencer s, Output out) {
        seq = s;
        written = 0;
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

    /** Pre testy: prehrávač „hrá“ do daného výstupu v tempe bpm bez vlastného vlákna. */
    void attachForTest(Output out, float bpm, int clickBeats) {
        this.bpm = bpm;
        this.clickBeats = clickBeats;
        mode = CLICK;
        Sequencer s = new Sequencer(new short[SOUNDS][0]);
        configure(s);
        seq = s;
        track = out;
        running = true;
        synchronized (this) {
            anchorFrame = 0;
            anchorBeat = 0;
            beatsPerFrame = bpm / 60.0 / RATE;
        }
    }

    /** Rámec (48 kHz na osi prehrávača), ktorý práve znie; -1, ak nehrá. */
    long heardFrame() {
        Output t = track;
        return running && t != null ? t.played() : -1;
    }

    /** Ktorá doba zaznie na rámci f. */
    synchronized double beatAt(long f) {
        return anchorBeat + (f - anchorFrame) * beatsPerFrame;
    }

    /** Na ktorom rámci zaznie doba b. */
    synchronized long frameAt(double b) {
        return anchorFrame + Math.round((b - anchorBeat) / beatsPerFrame);
    }

    /** Ktorý akord sprievodu práve znie; -1, ak nehrá. */
    int chordNow() {
        Backing b = backing;
        double beat = audibleBeat();
        if (b == null || Double.isNaN(beat)) return -1;
        return b.chordAt((long) Math.floor(beat / beatsPerBar()));
    }

    int beatsPerBar() {
        Sequencer s = seq;
        return s == null ? 4 : s.beatsPerBar();
    }

    /**
     * Na ktorej dobe (od prvej doby po štarte) práve je to, čo znie; NaN, ak prehrávač nehrá sám.
     * Podľa toho sa k nahrávke uloží, kde v nej začína takt.
     */
    double audibleBeat() {
        if (!running || clock != null) return Double.NaN;
        long f = heardFrame();
        return f < 0 ? Double.NaN : beatAt(f);
    }

    private void loop(Sequencer s, Output t) {
        track = t;
        int n = RATE / 100;
        short[] chunk = new short[n];
        float[] mix = new float[n];
        float lastBpm = -1;
        try {
            while (running) {
                // Zmeny z obrazovky (tempo, štýl, hlasitosť) platia hneď.
                s.style = style;
                s.fillBars = fillBars;
                s.volume = volume;
                s.mode = mode;
                s.clickBeats = clickBeats;
                Clock c = clock;
                s.backing = c == null ? backing : null;
                float speed = 1;
                if (c != null) {
                    if (!c.playing()) {
                        java.util.Arrays.fill(chunk, (short) 0);
                        t.write(chunk, n);
                        written += n;
                        continue;
                    }
                    speed = c.speed();
                    // Bicie zaznejú až o oneskorenie výstupu neskôr; hrá sa to, čo bude v nahrávke vtedy.
                    double want = target(c, t.latencyMs());
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
                java.util.Arrays.fill(mix, 0f);
                s.mix(mix, n, speed);
                float[] lp = loop;
                if (lp != null && lp.length > 0 && c == null) {
                    float g = loopGain;
                    long start = loopStart;
                    for (int i = 0; i < n; i++) {
                        long f = written + i;
                        if (f >= start) mix[i] += lp[(int) ((f - start) % lp.length)] * g;
                    }
                }
                for (int i = 0; i < n; i++) chunk[i] = soft(mix[i]);
                t.write(chunk, n);
                written += n;
            }
        } finally {
            track = null;
            t.close();
        }
    }
}
