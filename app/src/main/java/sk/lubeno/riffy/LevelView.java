package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Ukazovateľ úrovne: zelená do -12 dB, žltá do -3 dB, nad tým červená; pri prebudení
 * červený okraj. Krátko drží najvyššiu hodnotu, aby sa dala prečítať.
 */
public class LevelView extends View {
    static final float GREEN = 48 / 60f, YELLOW = 57 / 60f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float level, hold;
    private long holdAt;
    private boolean clip;

    public LevelView(Context c, AttributeSet a) {
        super(c, a);
    }

    /** peak v 16-bitovej mierke (32767 = plná úroveň); 0 = ticho. */
    void set(int peak) {
        double db = peak <= 0 ? -60 : 20 * Math.log10(peak / 32768.0);
        level = (float) Math.max(0, Math.min(1, (db + 60) / 60));
        long now = System.currentTimeMillis();
        if (level >= hold || now - holdAt > 1500) {
            hold = level;
            holdAt = now;
        }
        clip = peak >= 32700 || (clip && now - holdAt < 1500);
        invalidate();
    }

    void clear() {
        level = hold = 0;
        clip = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33808080);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, paint);
        c.save();
        c.clipRect(0, 0, w * level, h);
        bar(c, 0, GREEN, w, h, 0xFF43A047);
        bar(c, GREEN, YELLOW, w, h, 0xFFFDD835);
        bar(c, YELLOW, 1, w, h, 0xFFE53935);
        c.restore();
        if (hold > 0.02f) {
            paint.setColor(hold >= YELLOW ? 0xFFE53935 : 0xAA808080);
            c.drawRect(w * hold - 3, 0, w * hold, h, paint);
        }
        if (clip) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(0xFFE53935);
            r.set(1.5f, 1.5f, w - 1.5f, h - 1.5f);
            c.drawRoundRect(r, rad, rad, paint);
        }
    }

    private void bar(Canvas c, float from, float to, float w, float h, int color) {
        paint.setColor(color);
        r.set(w * from, 0, w * to, h);
        c.drawRect(r, paint);
    }
}
