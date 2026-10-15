package sk.lubeno.riffy;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/** Cvičebný denník: koľko milisekúnd v ktorý deň išiel zo vstupu signál (hralo sa). */
final class Diary {
    /** Deň sa počíta do série, keď sa hralo aspoň minútu. */
    static final long STREAK_MS = 60_000;
    final TreeMap<LocalDate, Long> days = new TreeMap<>();

    /** Formát na uloženie: „2026-10-15=123456;2026-10-14=…“. */
    static Diary parse(String s) {
        Diary d = new Diary();
        if (s == null) return d;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                d.days.put(LocalDate.parse(part.substring(0, eq)), Long.parseLong(part.substring(eq + 1)));
            } catch (RuntimeException ignored) {
            }
        }
        return d;
    }

    String format() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<LocalDate, Long> e : days.entrySet()) {
            if (s.length() > 0) s.append(';');
            s.append(e.getKey()).append('=').append(e.getValue());
        }
        return s.toString();
    }

    void add(LocalDate day, long ms) {
        if (ms > 0) days.merge(day, ms, Long::sum);
    }

    long on(LocalDate day) {
        return days.getOrDefault(day, 0L);
    }

    /** Súčet za 7 dní končiacich dňom today. */
    long week(LocalDate today) {
        long sum = 0;
        for (int i = 0; i < 7; i++) sum += on(today.minusDays(i));
        return sum;
    }

    long total() {
        long sum = 0;
        for (long v : days.values()) sum += v;
        return sum;
    }

    /** Koľko dní po sebe sa hralo; dnešok sa nepočíta ako prerušenie, kým sa ešte nehralo. */
    int streak(LocalDate today) {
        LocalDate d = on(today) >= STREAK_MS ? today : today.minusDays(1);
        int n = 0;
        while (on(d) >= STREAK_MS) {
            n++;
            d = d.minusDays(1);
        }
        return n;
    }

    static String minutes(long ms) {
        long m = Math.round(ms / 60000.0);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }
}
