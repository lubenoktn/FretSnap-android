package sk.lubeno.riffy;

import java.util.ArrayList;
import java.util.List;

/**
 * Looper, ktorý ustráži načasovanie: slučka má pevný počet taktov, nahrávanie sa samo začne
 * a skončí na hranici taktu (pred prvou slučkou je odpočet) a každá ďalšia vrstva sa nahrá
 * presne na jedno kolo. Slučka hrá v prehrávači rytmu ({@link Drums}), takže sedí s bicími
 * aj metronómom.
 *
 * Aby vrstvy sedeli, každá vzorka zo vstupu sa priradí k tomu, čo v tej chvíli znelo
 * (posun medzi vstupom a výstupom sa priebežne meria). Hrá sa k tomu, čo človek počuje,
 * nie k tomu, čo aplikácia práve posiela.
 */
final class LoopStation implements Engine.Tap {
    static final int EMPTY = 0, COUNTING = 1, RECORDING = 2, PLAYING = 3, ARMED = 4, OVERDUB = 5;
    static final int RATE = Drums.RATE;

    final Drums drums;
    int bars = 2;
    private int state = EMPTY;
    private final List<float[]> layers = new ArrayList<>();
    private long winStart, winEnd;

    // Zachytený vstup (mono) a jeho poloha vo vstupnom prúde.
    private float[] cap = new float[0];
    private int capLen;
    private long capStartIn, inCount;
    private int inRate;
    private boolean capturing;
    /** Rámec prehrávača, ktorý znel, keď prišla vstupná vzorka 0 (priemer). */
    private double offset;
    private boolean haveOffset;

    /** Zavolá sa (z vlákna vstupu), keď sa nahrávanie začne alebo skončí. */
    Runnable changed;

    LoopStation(Drums drums) {
        this.drums = drums;
    }

    synchronized int state() {
        return state;
    }

    synchronized int layers() {
        return layers.size();
    }

    synchronized boolean hasLoop() {
        return !layers.isEmpty();
    }

    /** Dĺžka slučky v rámcoch pri tempe bpm. */
    long length(double bpm, int beatsPerBar) {
        return Math.round(bars * beatsPerBar * 60.0 / bpm * RATE);
    }

    /**
     * Začne nahrávať prvú slučku od najbližšieho taktu, pred ktorým zostanú aspoň dve doby.
     * Prehrávač rytmu už musí hrať.
     */
    synchronized boolean recordFirst() {
        long h = drums.heardFrame();
        if (h < 0 || !layers.isEmpty()) return false;
        int bpb = drums.beatsPerBar();
        double b = drums.beatAt(h);
        double start = Math.ceil(b / bpb - 1e-6) * bpb;
        if (start - b < 2) start += bpb;
        winStart = drums.frameAt(start);
        winEnd = drums.frameAt(start + bars * bpb);
        state = COUNTING;
        beginCapture();
        return true;
    }

    /** Ďalšia vrstva od najbližšieho začiatku slučky (aspoň pol sekundy dopredu), na jedno kolo. */
    synchronized boolean recordLayer() {
        float[] lp = drums.loop;
        long h = drums.heardFrame();
        if (state != PLAYING || lp == null || h < 0) return false;
        long len = lp.length, start = drums.loopStart;
        long k = (long) Math.ceil((h - start) / (double) len);
        long at = start + Math.max(0, k) * len;
        if (at - h < RATE / 2) at += len;
        winStart = at;
        winEnd = at + len;
        state = ARMED;
        beginCapture();
        return true;
    }

    /** Zruší čakajúce alebo prebiehajúce nahrávanie. */
    synchronized void cancel() {
        capturing = false;
        state = layers.isEmpty() ? EMPTY : PLAYING;
    }

    /** Odoberie poslednú vrstvu. */
    synchronized void undo() {
        if (state != PLAYING || layers.isEmpty()) return;
        layers.remove(layers.size() - 1);
        publish();
    }

    synchronized void clear() {
        capturing = false;
        layers.clear();
        state = EMPTY;
        drums.loop = null;
    }

    /** Celá slučka (všetky vrstvy zmiešané), alebo null. */
    synchronized float[] mixdown() {
        if (layers.isEmpty()) return null;
        float[] m = new float[layers.get(0).length];
        for (float[] l : layers) for (int i = 0; i < m.length; i++) m[i] += l[i];
        float peak = 0;
        for (float v : m) peak = Math.max(peak, Math.abs(v));
        if (peak > 0.95f) for (int i = 0; i < m.length; i++) m[i] *= 0.95f / peak;
        return m;
    }

    /** Kde v slučke práve je to, čo znie (0–1); -1, ak slučka nie je. */
    double position() {
        float[] lp = drums.loop;
        long h = drums.heardFrame();
        if (lp == null || h < 0) return -1;
        return Math.floorMod(h - drums.loopStart, lp.length) / (double) lp.length;
    }

    /** O koľko dôb sa začne nahrávanie (pri odpočte a čakaní na začiatok slučky); 0 = už sa nahráva. */
    synchronized double beatsToStart() {
        long h = drums.heardFrame();
        if (h < 0 || (state != COUNTING && state != ARMED)) return 0;
        return Math.max(0, drums.beatAt(winStart) - drums.beatAt(h));
    }

    /** Ako ďaleko je nahrávanie (0–1). */
    synchronized double recorded() {
        long h = drums.heardFrame();
        if (h < 0 || (state != RECORDING && state != OVERDUB)) return 0;
        return Math.max(0, Math.min(1, (h - winStart) / (double) (winEnd - winStart)));
    }

    private void beginCapture() {
        capturing = true;
        capLen = 0;
        capStartIn = inCount;
    }

    @Override
    public void onAudio(int[] buf, int n, int channels, int rate) {
        Runnable notify = null;
        synchronized (this) {
            int frames = n / channels;
            if (rate != inRate) {
                inRate = rate;
                haveOffset = false;
            }
            long h = drums.heardFrame();
            long endIn = inCount + frames;
            if (h >= 0) {
                // Čo práve prišlo zo vstupu, sa hralo k tomu, čo práve znie.
                double o = h - endIn * (double) RATE / rate;
                // Priemer cez krátke výkyvy; po skoku (prehrávač začal odznova) hneď nová hodnota.
                offset = haveOffset && Math.abs(o - offset) < RATE / 10.0 ? offset * 0.95 + o * 0.05 : o;
                haveOffset = true;
            } else {
                haveOffset = false;
            }
            if (capturing) {
                if (cap.length < capLen + frames) cap = java.util.Arrays.copyOf(cap, Math.max(capLen + frames, cap.length * 2 + rate));
                for (int f = 0; f < frames; f++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) sum += buf[f * channels + c];
                    cap[capLen++] = sum / channels / 2147483648f;
                }
            }
            inCount = endIn;
            if (capturing && haveOffset) {
                double heardEnd = offset + endIn * (double) RATE / rate;
                if (state == COUNTING && heardEnd >= winStart) {
                    state = RECORDING;
                    notify = changed;
                } else if (state == ARMED && heardEnd >= winStart) {
                    state = OVERDUB;
                    notify = changed;
                }
                if ((state == RECORDING || state == OVERDUB) && heardEnd >= winEnd + RATE / 20) {
                    layers.add(build());
                    capturing = false;
                    state = PLAYING;
                    publish();
                    notify = changed;
                }
            }
        }
        if (notify != null) notify.run();
    }

    /** Vrstva na rámce [winStart, winEnd): ku každému rámcu vzorka vstupu, ktorá sa k nemu hrala. */
    private float[] build() {
        int len = (int) (winEnd - winStart);
        float[] out = new float[len];
        double step = inRate / (double) RATE;
        for (int j = 0; j < len; j++) {
            double in = (winStart + j - offset) * step - capStartIn;
            int i = (int) Math.floor(in);
            if (i < 0 || i + 1 >= capLen) continue;
            float f = (float) (in - i);
            out[j] = cap[i] + (cap[i + 1] - cap[i]) * f;
        }
        // Na švíku slučky dobeh 3 ms a len nepatrný nábeh, aby nelúplo, ale úder na prvej dobe ostal celý.
        int fadeOut = Math.min(len / 4, RATE * 3 / 1000), fadeIn = Math.min(len / 4, 16);
        for (int j = 0; j < fadeOut; j++) out[len - 1 - j] *= j / (float) fadeOut;
        for (int j = 0; j < fadeIn; j++) out[j] *= j / (float) fadeIn;
        return out;
    }

    /** Pošle zmiešanú slučku prehrávaču (prvá vrstva určí, kde slučka začína). */
    private void publish() {
        if (layers.isEmpty()) {
            drums.loop = null;
            state = EMPTY;
            return;
        }
        if (layers.size() == 1 && drums.loop == null) drums.loopStart = winStart;
        float[] m = new float[layers.get(0).length];
        for (float[] l : layers) for (int i = 0; i < m.length; i++) m[i] += l[i];
        float peak = 0;
        for (float v : m) peak = Math.max(peak, Math.abs(v));
        drums.loopGain = peak > 0.9f ? 0.9f / peak : 1f;
        drums.loop = m;
    }
}
