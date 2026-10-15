package sk.lubeno.riffy;

/**
 * Hornopriepustný filter (Butterworth 2. rádu) pre striedavo uložené kanály: odstráni dunenie pod
 * najnižším tónom gitary (ťukanie do tela, otrasy stojana, vietor), samotnú gitaru nechá.
 */
final class HighPass {
    private final double b0, b1, b2, a1, a2;
    private final double[] x1, x2, y1, y2;

    HighPass(double cutoff, int rate, int channels) {
        double w = 2 * Math.PI * cutoff / rate, cos = Math.cos(w), alpha = Math.sin(w) / Math.sqrt(2);
        double a0 = 1 + alpha;
        b0 = (1 + cos) / 2 / a0;
        b1 = -(1 + cos) / a0;
        b2 = b0;
        a1 = -2 * cos / a0;
        a2 = (1 - alpha) / a0;
        x1 = new double[channels];
        x2 = new double[channels];
        y1 = new double[channels];
        y2 = new double[channels];
    }

    /** Prefiltruje n vzoriek na mieste (32-bitové čísla ako zo {@link Source#read}). */
    void process(int[] buf, int n) {
        int ch = x1.length;
        for (int i = 0; i < n; i++) {
            int c = i % ch;
            double x = buf[i], y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c];
            x2[c] = x1[c];
            x1[c] = x;
            y2[c] = y1[c];
            y1[c] = y;
            buf[i] = Source.clamp(y);
        }
    }
}
