package sk.lubeno.riffy;


/** Metronóm: krátke tóny, prvá doba taktu je vyššia. Hrá do {@link Output} (telefón alebo USB zariadenie). */
final class Metronome {
    static final int RATE = 48000;

    private volatile boolean running;
    private Thread thread;
    volatile int bpm = 100;
    volatile int beatsPerBar = 4;

    boolean running() {
        return running;
    }

    void start(Output out) {
        stop();
        running = true;
        thread = new Thread(() -> loop(out), "metronom");
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

    static short[] click(double freq) {
        short[] s = new short[RATE * 30 / 1000];
        for (int i = 0; i < s.length; i++) {
            double t = i / (double) RATE;
            s[i] = (short) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * 120) * 0.7 * 32767);
        }
        return s;
    }

    private void loop(Output track) {
        short[] high = click(1760), low = click(1100);
        short[] chunk = new short[RATE / 100];
        short[] sound = null;
        int soundPos = 0, beat = 0;
        long pos = 0, nextBeat = 0;
        try {
            while (running) {
                long perBeat = Math.round(RATE * 60.0 / Math.max(20, bpm));
                for (int i = 0; i < chunk.length; i++, pos++) {
                    if (pos >= nextBeat) {
                        sound = beat % Math.max(1, beatsPerBar) == 0 ? high : low;
                        soundPos = 0;
                        beat++;
                        nextBeat += perBeat;
                    }
                    chunk[i] = sound != null && soundPos < sound.length ? sound[soundPos++] : 0;
                }
                track.write(chunk, chunk.length);
            }
        } finally {
            track.close();
        }
    }
}
