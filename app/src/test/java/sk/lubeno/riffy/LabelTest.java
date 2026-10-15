package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LabelTest {
    @Test
    public void automatickeMeno() {
        assertEquals("Riff 9:12", MainActivity.label("Riff 2026-10-15 09.12.30"));
        assertEquals("Riff 18:05 · spätne", MainActivity.label("Riff 2026-10-14 18.05.59 spätne"));
        assertEquals("Riff 7:03 · obnovené", MainActivity.label("Riff 2026-10-14 07.03.10 obnovené"));
    }

    @Test
    public void vlastneMenoOstane() {
        assertEquals("Sólo nápad", MainActivity.label("Sólo nápad"));
        assertEquals("Riff 2026-10-15 09.12.30 (1)", MainActivity.label("Riff 2026-10-15 09.12.30 (1)"));
    }

    @Test
    public void menoSuboruVZipe() {
        assertEquals("Riff 9.12 spatne", MainActivity.fileName("Riff 9:12 · spätne"));
        assertEquals("a-b-c", MainActivity.fileName("a/b?c"));
        assertEquals("Riff 9.53 obnovene", MainActivity.fileName("Riff 9:53 · obnovené"));
    }

    @Test
    public void hladanieBezDiakritiky() {
        assertEquals("solo napad", MainActivity.fold("Sólo Nápad"));
    }
}
