package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Hmatník so stupnicou: hore 1. (najtenšia) struna, pražce 0–15, bodky tónov stupnice s ich menami.
 * Základný tón je vo farbe zvýraznenia, tóny práve hraného akordu (sprievod) zelené; mimo zvolenej
 * polohy sú bodky bledé.
 */
public class ScaleView extends View {
    private static final int[] MARKS = {3, 5, 7, 9, 12, 15};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent, fg;
    private int[] strings = Tunings.strings(0);
    private boolean[] scale = new boolean[12], chord = new boolean[12];
    private int root, from = -1;

    public ScaleView(Context c, AttributeSet a) {
        super(c, a);
        accent = Look.accent(c);
        fg = Look.color(c, android.R.attr.textColorPrimary);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /** from = prvý pražec polohy (5 pražcov), -1 = celý hmatník. */
    void set(int[] strings, int root, boolean[] scale, int from) {
        this.strings = strings;
        this.root = root;
        this.scale = scale;
        this.from = from;
        invalidate();
    }

    /** Tóny akordu, ktorý práve hrá sprievod (prázdne = žiadny). */
    void setChord(boolean[] chord) {
        this.chord = chord;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        int frets = Scales.FRETS;
        float left = 22 * d, top = 10 * d, bottom = h - 18 * d;
        float fw = (w - left - 4 * d) / (frets + 0.5f);
        float sh = (bottom - top) / 5;
        // Struny a pražce
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x66808080);
        for (int s = 0; s < 6; s++) {
            paint.setStrokeWidth((0.8f + (5 - s) * 0.25f) * d);
            float y = top + s * sh;
            c.drawLine(left, y, w - 4 * d, y, paint);
        }
        for (int f = 0; f <= frets; f++) {
            float x = left + (f + 0.5f) * fw;
            paint.setStrokeWidth(f == 0 ? 4 * d : d);
            paint.setColor(f == 0 ? fg : 0x55808080);
            c.drawLine(x, top, x, bottom, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(0x99808080);
        for (int f : MARKS) {
            c.drawText(String.valueOf(f), left + f * fw, h - 4 * d, paint);
        }
        // Mená strún vľavo
        for (int s = 0; s < 6; s++) {
            int midi = strings[5 - s];
            c.drawText(Scales.name(midi), 9 * d, top + s * sh + 4 * d, paint);
        }
        // Bodky tónov
        float r = Math.min(fw, sh) * 0.36f;
        paint.setTextSize(r * 1.05f);
        for (int s = 0; s < 6; s++) {
            int open = strings[5 - s];
            float y = top + s * sh;
            for (int f = 0; f <= frets; f++) {
                int pc = Math.floorMod(open + f, 12);
                if (!scale[pc]) continue;
                boolean inPos = from < 0 || (f >= from && f <= from + 4) || (from == 0 && f == 0);
                float x = f == 0 ? left : left + f * fw;
                int color = chord[pc] ? 0xFF43A047 : pc == root ? accent : 0xFF808080;
                paint.setColor(color);
                paint.setAlpha(inPos ? 255 : 60);
                c.drawCircle(x, y, r, paint);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha(inPos ? 255 : 90);
                c.drawText(Scales.name(pc), x, y + r * 0.38f, paint);
            }
        }
    }
}
