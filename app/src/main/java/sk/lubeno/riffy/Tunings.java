package sk.lubeno.riffy;

/** Ladenia gitary (MIDI tóny strún od 6. po 1.) a hľadanie struny, ktorá sa ladí. */
final class Tunings {
    static String[] NAMES = {"Štandard", "Drop D", "Eb (o poltón nižšie)", "D štandard", "Drop C", "DADGAD", "Open G"};
    static final int[][] STRINGS = {
            {40, 45, 50, 55, 59, 64},
            {38, 45, 50, 55, 59, 64},
            {39, 44, 49, 54, 58, 63},
            {38, 43, 48, 53, 57, 62},
            {36, 43, 48, 53, 57, 62},
            {38, 45, 50, 55, 57, 62},
            {38, 43, 50, 55, 59, 62},
    };

    private Tunings() {
    }

    static int[] strings(int tuning) {
        return STRINGS[Math.max(0, Math.min(tuning, STRINGS.length - 1))];
    }

    static String note(int midi) {
        return Tuner.NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    /**
     * Ktorá struna (index 0 = 6. struna) je najbližšie k tónu exact (MIDI, desatinné); -1, ak je tón
     * od všetkých strún ďalej než 3,5 poltóna (vtedy ladička ukáže len najbližší tón).
     */
    static int nearest(int[] strings, double exact) {
        int best = -1;
        double dist = 3.5;
        for (int i = 0; i < strings.length; i++) {
            double d = Math.abs(exact - strings[i]);
            if (d < dist) {
                dist = d;
                best = i;
            }
        }
        return best;
    }
}
