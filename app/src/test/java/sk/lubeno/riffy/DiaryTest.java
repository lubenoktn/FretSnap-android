package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;

import org.junit.Test;

public class DiaryTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test
    public void sucetASeria() {
        Diary d = new Diary();
        d.add(TODAY, 30_000);
        d.add(TODAY, 40_000);
        d.add(TODAY.minusDays(1), 120_000);
        d.add(TODAY.minusDays(2), 90_000);
        d.add(TODAY.minusDays(4), 600_000);
        d.add(TODAY.minusDays(10), 600_000);
        assertEquals(70_000, d.on(TODAY));
        assertEquals(70_000 + 120_000 + 90_000 + 600_000, d.week(TODAY));
        assertEquals(3, d.streak(TODAY));
        assertEquals(1_480_000, d.total());
    }

    @Test
    public void dnesEsteNehral() {
        Diary d = new Diary();
        d.add(TODAY.minusDays(1), 120_000);
        d.add(TODAY.minusDays(2), 120_000);
        d.add(TODAY, 10_000);
        assertEquals(2, d.streak(TODAY));
        assertEquals(0, new Diary().streak(TODAY));
    }

    @Test
    public void ulozenieANacitanie() {
        Diary d = new Diary();
        d.add(TODAY, 1234);
        d.add(TODAY.minusDays(3), 99);
        Diary e = Diary.parse(d.format());
        assertEquals(1234, e.on(TODAY));
        assertEquals(99, e.on(TODAY.minusDays(3)));
        assertEquals(0, Diary.parse("blbost;2026-13-01=5;=3").days.size());
    }

    @Test
    public void minuty() {
        assertEquals("0 min", Diary.minutes(20_000));
        assertEquals("25 min", Diary.minutes(25 * 60_000));
        assertEquals("1 h 40 min", Diary.minutes(100 * 60_000));
    }
}
