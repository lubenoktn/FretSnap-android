package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DrumsTest {
    /** Každý nástroj = jedna vzorka s vlastnou hodnotou, aby bolo vidno, čo kedy zaznelo. */
    static short[][] kit() {
        short[][] k = new short[Drums.SOUNDS][];
        for (int i = 0; i < Drums.SOUNDS; i++) k[i] = new short[]{(short) (1000 * (i + 1))};
        return k;
    }

    static List<Integer> onsets(Drums.Sequencer s, int samples, float speed) {
        short[] out = new short[samples];
        s.render(out, samples, speed);
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < samples; i++) if (out[i] != 0) at.add(i);
        return at;
    }

    @Test
    public void rockKickOnOneAndThree() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.style = 0;
        s.fillBars = 0;
        s.bpm = 120;
        s.volume = 1;
        s.seek(0);
        // 120 BPM: doba = 24000 vzoriek, osmina = 12000; hi-hat na každej osmine
        List<Integer> at = onsets(s, 96000, 1);
        assertEquals(8, at.size());
        for (int k = 0; k < 8; k++) assertEquals(12000 * k, (int) at.get(k));
    }

    @Test
    public void countInIsOneBarOfSticks() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.countIn = true;
        s.bpm = 120;
        s.volume = 1;
        s.seek(-4);
        short[] out = new short[96000];
        s.render(out, out.length, 1);
        // Len paličky (najvyššia vzorka nie je kopák ani činel) na každej dobe
        for (int b = 0; b < 4; b++) assertTrue(out[b * 24000] != 0);
        assertEquals(0, out[12000]);
    }

    @Test
    public void leadInPlaysGrooveThenSticks() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.countIn = true;
        s.bpm = 120;
        s.volume = 1;
        s.seek(-8);
        short[] out = new short[192000];
        s.render(out, out.length, 1);
        // Predposledný takt hrá rytmus (hi-hat aj na osminách), posledný len paličky na dobách
        assertTrue(out[12000] != 0);
        for (int b = 0; b < 4; b++) assertTrue(out[96000 + b * 24000] != 0);
        assertEquals(0, out[96000 + 12000]);
    }

    @Test
    public void reggaeSwingDelaysOffbeats() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.style = 2;
        s.fillBars = 0;
        s.bpm = 60;
        s.volume = 1;
        s.seek(0);
        List<Integer> at = onsets(s, 48000, 1);
        // 60 BPM: doba = 48000 vzoriek; osmina „a“ zaznie na 2/3 doby, nie v polovici
        assertEquals(0, (int) at.get(0));
        assertTrue(Math.abs(at.get(1) - 32040) < 100);
    }

    @Test
    public void speedFollowsPlayback() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.fillBars = 0;
        s.bpm = 120;
        s.volume = 1;
        s.seek(0);
        // pri 50 % sú osminy dvakrát ďalej od seba
        List<Integer> at = onsets(s, 48001, 0.5f);
        assertEquals(24000, (int) at.get(1));
    }

    @Test
    public void sixEightHasThreeEighthsPerBeat() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.style = Drums.styleIndex("6/8");
        s.fillBars = 0;
        s.bpm = 60;
        s.volume = 1;
        s.seek(0);
        assertEquals(2, s.beatsPerBar());
        // hi-hat na každej osmine: 48000 / 3 = 16000 vzoriek
        List<Integer> at = onsets(s, 96000, 1);
        assertEquals(6, at.size());
        assertEquals(16000, (int) at.get(1));
    }

    @Test
    public void waltzBarHasThreeBeats() {
        Drums.Sequencer s = new Drums.Sequencer(kit());
        s.style = Drums.styleIndex("3/4");
        assertEquals(3, s.beatsPerBar());
        assertEquals(9, Drums.STYLES.length);
    }

    @Test
    public void metronomeClicksEveryBeatWithAccent() {
        short[][] k = kit();
        Drums.Sequencer s = new Drums.Sequencer(k);
        s.mode = Drums.CLICK;
        s.clickBeats = 3;
        s.bpm = 120;
        s.volume = 1;
        s.seek(0);
        short[] out = new short[24000 * 4];
        s.render(out, out.length, 1);
        assertTrue(out[0] > out[24000]);
        assertTrue(out[24000] != 0 && out[48000] != 0);
        assertTrue(out[72000] > out[24000]);
    }

    @Test
    public void firstBeatAfterStart() {
        assertEquals(0, MainActivity.firstBeatMs(0, 120));
        assertEquals(0, MainActivity.firstBeatMs(8, 120));
        // nahrávka začala na 2. dobe (beat 1): do ďalšieho taktu sú 3 doby = 1,5 s
        assertEquals(1500, MainActivity.firstBeatMs(1, 120));
        // počas odpočtu (beat -1): prvá doba o jednu dobu neskôr
        assertEquals(500, MainActivity.firstBeatMs(-1, 120));
        assertEquals(250, MainActivity.firstBeatMs(3.5, 120));
        // 3/4: z doby 1 sú do ďalšieho taktu dve doby
        assertEquals(1000, MainActivity.firstBeatMs(1, 120, 3));
    }
}
