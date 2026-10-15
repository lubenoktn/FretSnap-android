package sk.lubeno.riffy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LabelTest {
    @Test
    public void automatickeMeno() {
        assertEquals("Riff 9:12", MainActivity.label("Riff 2026-10-15 09.12.30"));
        assertEquals("Riff 18:05 · spätne", MainActivity.label("Riff 2026-10-14 18.05.59 spätne"));
    }

    @Test
    public void vlastneMenoOstane() {
        assertEquals("Sólo nápad", MainActivity.label("Sólo nápad"));
        assertEquals("Riff 2026-10-15 09.12.30 (1)", MainActivity.label("Riff 2026-10-15 09.12.30 (1)"));
    }

    @Test
    public void hladanieBezDiakritiky() {
        assertEquals("solo napad", MainActivity.fold("Sólo Nápad"));
    }
}
