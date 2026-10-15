package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Ladička: oblúk od -50 do +50 centov s ručičkou a veľkým názvom tónu; v strede zelená zóna. */
public class TunerView extends View {
    static final float OK = 5, SWEEP = 50;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int fg;
    private String note = "—";
    private float target, shown;
    private boolean active;

    public TunerView(Context c, AttributeSet a) {
        super(c, a);
        fg = Look.color(c, android.R.attr.textColorPrimary);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void set(String name, double cents) {
        note = name;
        target = (float) Math.max(-SWEEP, Math.min(SWEEP, cents));
        if (!active) shown = target;
        active = true;
        invalidate();
    }

    void clear() {
        note = "—";
        active = false;
        target = 0;
        invalidate();
    }

    static int colorFor(float cents) {
        float a = Math.abs(cents);
        return a < OK ? 0xFF43A047 : a < 15 ? 0xFFFB8C00 : 0xFFE53935;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        float cx = w / 2, cy = h * 0.66f, rad = Math.min(w * 0.42f, h * 0.58f);
        // Ručička sa k cieľu posúva plynulo
        shown += (target - shown) * 0.35f;
        if (Math.abs(target - shown) > 0.2f) postInvalidateOnAnimation();
        else shown = target;

        // Oblúk a zelená zóna
        paint.setStyle(Paint.Style.STROKE);
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        paint.setStrokeWidth(6 * d);
        paint.setColor(0x33808080);
        c.drawArc(r, 270 - SWEEP, 2 * SWEEP, false, paint);
        paint.setColor(0xAA43A047);
        c.drawArc(r, 270 - OK, 2 * OK, false, paint);

        // Značky po 10 centoch
        paint.setStrokeWidth(2 * d);
        for (int k = -50; k <= 50; k += 10) {
            double a = Math.toRadians(k);
            float in = rad - (k == 0 ? 16 : 9) * d, out = rad + 6 * d;
            paint.setColor(k == 0 ? fg : 0x99808080);
            c.drawLine(cx + (float) (in * Math.sin(a)), cy - (float) (in * Math.cos(a)),
                    cx + (float) (out * Math.sin(a)), cy - (float) (out * Math.cos(a)), paint);
        }

        // Ručička
        int color = active ? colorFor(shown) : 0x88808080;
        double a = Math.toRadians(shown);
        paint.setColor(color);
        paint.setStrokeWidth(4 * d);
        c.drawLine(cx, cy, cx + (float) ((rad - 4 * d) * Math.sin(a)), cy - (float) ((rad - 4 * d) * Math.cos(a)), paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, 7 * d, paint);

        // Tón
        paint.setTextSize(h * 0.24f);
        paint.setFakeBoldText(true);
        paint.setColor(active ? colorFor(target) : 0xFF808080);
        c.drawText(note, cx, Math.min(h - 4 * d, cy + h * 0.30f), paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(0x99808080);
        double end = Math.toRadians(SWEEP);
        float ex = (float) (rad * Math.sin(end)) + 16 * d, ey = cy - (float) (rad * Math.cos(end)) + 14 * d;
        paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
        c.drawText("♭", cx - ex, ey, paint);
        c.drawText("♯", cx + ex, ey, paint);
    }
}
