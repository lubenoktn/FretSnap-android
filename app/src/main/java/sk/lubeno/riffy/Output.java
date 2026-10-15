package sk.lubeno.riffy;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Kam idú metronóm a bicie: zvukový systém Androidu, alebo priamo do USB zariadenia. Mono, 16 bit, 48 kHz. */
abstract class Output {
    static final int RATE = 48000;

    /** Pošle zvuk; čaká, kým sa zmestí (tak drží tempo). */
    abstract void write(short[] pcm, int n);

    /** Koľko rámcov už zaznelo (približne). */
    abstract long played();

    /** O koľko ms neskôr zaznie, čo sa práve pošle. */
    abstract double latencyMs();

    abstract void close();

    /** Cez Android (reproduktor, slúchadlá v telefóne, Bluetooth). */
    static final class Track extends Output {
        private final AudioTrack track;
        private final android.media.AudioTimestamp stamp = new android.media.AudioTimestamp();
        private long written;

        Track(AudioDeviceInfo device) {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, RATE / 20 * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
            if (device != null) track.setPreferredDevice(device);
            track.play();
        }

        void write(short[] pcm, int n) {
            track.write(pcm, 0, n);
            written += n;
        }

        double latencyMs() {
            if (track.getTimestamp(stamp)) {
                double now = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * (RATE / 1e9);
                return Math.max(0, (written - now) * 1000.0 / RATE);
            }
            // Bez časovej značky: čo je v zásobe, plus odhad pre zvukový hardvér.
            return Math.max(0, written - played()) * 1000.0 / RATE + 20;
        }

        long played() {
            return track.getPlaybackHeadPosition() & 0xffffffffL;
        }

        void close() {
            track.pause();
            track.flush();
            track.release();
        }
    }

    /**
     * Priamo do USB zariadenia, z ktorého sa nahráva (Android ho vtedy nevidí). Keď zariadenie
     * práve nie je otvorené (zapína sa, odpojilo sa), zvuk sa zahodí, ale tempo beží ďalej.
     */
    static final class Usb extends Output {
        private volatile long written;
        private long startedAt = -1;

        /** Je zariadenie s výstupom práve otvorené? */
        static boolean available() {
            UsbAudio.Direct d = UsbAudio.Direct.current;
            return d != null && d.hasOutput();
        }

        void write(short[] pcm, int n) {
            UsbAudio.Direct d = UsbAudio.Direct.current;
            boolean ok = d != null && d.writeOut(pcm, n, RATE);
            written += n;
            if (!ok) {
                // Nikam to nejde: počkať, akoby to zaznelo, aby metronóm nebežal naprázdno.
                long now = SystemClock.uptimeMillis();
                if (startedAt < 0) startedAt = now - written * 1000 / RATE;
                long due = startedAt + written * 1000 / RATE;
                if (due > now) SystemClock.sleep(due - now);
            } else {
                startedAt = -1;
            }
        }

        long played() {
            return Math.max(0, written - Math.round(latencyMs() * RATE / 1000));
        }

        double latencyMs() {
            UsbAudio.Direct d = UsbAudio.Direct.current;
            return d == null ? 50 : d.pendingMs();
        }

        void close() {
        }
    }
}
