package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

public class MixdownTest {
    @Test
    public void drumsLandOnBeatsAtRiffRate() throws Exception {
        int rate = 44100, frames = rate * 2;
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        wav.write(Wav.header(rate, 2, 24, frames * 6L));
        wav.write(new byte[frames * 6]);
        short[][] kit = DrumsTest.kit();
        File out = File.createTempFile("mix", ".wav");
        // Rock pri 120 BPM, prvá doba po 100 ms: hi-hat na osminách = každých 250 ms od 100 ms
        Mixdown.withDrums(new ByteArrayInputStream(wav.toByteArray()), out, kit, 0, 0, 120, 100, 1f);
        try (FileInputStream in = new FileInputStream(out)) {
            Wav.Info info = Wav.read(in);
            assertEquals(rate, info.rate);
            assertEquals(frames, info.frames());
            byte[] data = new byte[(int) info.dataSize];
            int got = 0;
            while (got < data.length) got += in.read(data, got, data.length - got);
            for (int k = 0; k < 7; k++) {
                int at = (int) Math.round((0.1 + k * 0.25) * rate);
                boolean hit = false;
                for (int f = at - 2; f <= at + 2; f++) if (Wav.le(data, f * 6, 3) != 0) hit = true;
                assertTrue("úder " + k + " chýba pri " + at, hit);
                assertEquals(0, Wav.le(data, (at + rate / 8) * 6, 3));
            }
        } finally {
            out.delete();
        }
    }
}
