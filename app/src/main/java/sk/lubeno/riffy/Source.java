package sk.lubeno.riffy;

import java.io.IOException;
import java.util.Locale;

/**
 * Otvorený vstup. Vzorky sa čítajú ako 32-bitové čísla so zvukom zarovnaným hore
 * (24-bitový zvuk má spodný bajt nulový, 16-bitový spodné dva), kanály idú striedavo.
 */
abstract class Source {
    int channels, rate;
    /** Zosilnenie vstupu (1 = bez zmeny); mení sa aj počas nahrávania. */
    volatile float gain = 1f;
    String where = "", error;

    /** Prečíta blok vzoriek; vráti ich počet, záporné číslo pri chybe. */
    abstract int read(int[] out);

    abstract void close();

    /** Vzorkovanie, ktoré sa zapíše do nahrávky. */
    int finalRate() {
        return rate;
    }

    interface Opener {
        Source open() throws IOException;
    }

    static int clamp(double v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(v)));
    }

    /** Špičky po kanáloch v 16-bitovej mierke (32767 = plná úroveň). */
    static int[] peaks(int[] buf, int n, int channels, int[] into) {
        for (int i = 0; i < n; i++) {
            int v = Math.abs(buf[i] >> 16);
            if (v > into[i % channels]) into[i % channels] = v;
        }
        return into;
    }

    static String db(int peak) {
        return peak == 0 ? "ticho" : String.format(Locale.ROOT, "%.0f dB", 20 * Math.log10(peak / 32768.0));
    }

    static String levels(int[] p) {
        return p.length == 2 ? "ľavý " + db(p[0]) + ", pravý " + db(p[1]) : db(p[0]);
    }
}
