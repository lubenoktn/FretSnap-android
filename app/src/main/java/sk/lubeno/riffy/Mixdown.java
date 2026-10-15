package sk.lubeno.riffy;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Riff zmiešaný s bicími do nového WAV (originál ostane čistý). */
final class Mixdown {
    private Mixdown() {
    }

    /** Do 0,9 nič nemení, nad tým mäkko zastaví pred plnou úrovňou. */
    static double limit(double y) {
        double a = Math.abs(y);
        if (a <= 0.9) return y;
        return Math.signum(y) * (0.9 + 0.099 * Math.tanh((a - 0.9) / 0.1));
    }

    /**
     * wav = riff; bicie v jeho tempe a štýle od prvej doby beat0Ms. Bicie sa skladajú pri 48 kHz
     * a prepočítajú na vzorkovanie riffu.
     */
    static void withDrums(InputStream wav, File out, short[][] kit, int style, int fillBars, float bpm,
                          int beat0Ms, float drumVolume) throws IOException {
        InputStream in = new BufferedInputStream(wav, 1 << 16);
        Wav.Info info = Wav.read(in);
        int rate = info.rate, ch = info.channels, sb = info.bits / 8, fb = info.frameBytes(), shift = 32 - info.bits;
        Drums.Sequencer seq = new Drums.Sequencer(kit);
        seq.style = style;
        seq.fillBars = fillBars;
        seq.bpm = bpm;
        seq.volume = drumVolume;
        seq.mode = Drums.DRUMS;
        seq.seek(-Math.max(0, beat0Ms) * bpm / 60000.0);
        float[] drum = new float[9600];
        long drumBase = 0;
        int drumLen = 0;
        double step = Drums.RATE / (double) rate;
        long frames = info.frames();
        Wav.Writer w = new Wav.Writer(out, 2, rate);
        byte[] frame = new byte[fb];
        int[] buf = new int[2048 * 2];
        int k = 0;
        for (long j = 0; j < frames; j++) {
            int got = 0;
            while (got < fb) {
                int r = in.read(frame, got, fb - got);
                if (r < 0) break;
                got += r;
            }
            if (got < fb) break;
            float l = (Wav.le(frame, 0, sb) << shift) / 2147483648f;
            float rr = ch > 1 ? (Wav.le(frame, sb, sb) << shift) / 2147483648f : l;
            double t = j * step;
            // Bicie dopredu, kým pokrývajú čas t (a jednu vzorku za ním na prepočet).
            while (t + 1 >= drumBase + drumLen) {
                int keep = Math.min(drumLen, 2);
                System.arraycopy(drum, drumLen - keep, drum, 0, keep);
                drumBase += drumLen - keep;
                float[] fresh = new float[drum.length - keep];
                seq.mix(fresh, fresh.length, 1);
                System.arraycopy(fresh, 0, drum, keep, fresh.length);
                drumLen = drum.length;
            }
            double at = t - drumBase;
            int i = (int) at;
            float d = drum[i] + (drum[i + 1] - drum[i]) * (float) (at - i);
            buf[k++] = Source.clamp(limit(l + d) * 2147483647.0);
            buf[k++] = Source.clamp(limit(rr + d) * 2147483647.0);
            if (k == buf.length) {
                w.write(buf, 0, k);
                k = 0;
            }
        }
        if (k > 0) w.write(buf, 0, k);
        w.finish(rate);
    }
}
