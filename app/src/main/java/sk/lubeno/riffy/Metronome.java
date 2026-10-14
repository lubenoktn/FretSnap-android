package sk.lubeno.riffy;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Metronóm: krátke tóny cez AudioTrack, prvá doba taktu je vyššia. */
final class Metronome {
    static final int RATE = 48000;

    private volatile boolean running;
    private Thread thread;
    volatile int bpm = 100;
    volatile int beatsPerBar = 4;

    boolean running() {
        return running;
    }

    void start(AudioDeviceInfo out) {
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

    private void loop(AudioDeviceInfo out) {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 20 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        if (out != null) track.setPreferredDevice(out);
        track.play();
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
                track.write(chunk, 0, chunk.length);
            }
        } finally {
            track.pause();
            track.flush();
            track.release();
        }
    }
}
