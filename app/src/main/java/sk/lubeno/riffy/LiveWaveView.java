package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Priebeh zvuku za posledných {@link Engine#HISTORY_SECONDS} sekúnd, teda to, čo uloží „⟲“.
 * Nové je vpravo; čo sa nahráva, je červené.
 */
public class LiveWaveView extends View {
    private static final int SIZE = 1024;
    private final long[] times = new long[SIZE];
    private final float[] values = new float[SIZE];
    private final boolean[] rec = new boolean[SIZE];
    private int head, count;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent;

    public LiveWaveView(Context c, AttributeSet a) {
        super(c, a);
        accent = Look.accent(c);
        text.setColor(0x99808080);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** peak v 16-bitovej mierke; výška podľa decibelov, aby bolo vidno aj tiché hranie. */
    void push(int peak, boolean recording) {
        double db = peak <= 0 ? -60 : 20 * Math.log10(peak / 32768.0);
        times[head] = SystemClock.uptimeMillis();
        values[head] = (float) Math.max(0, Math.min(1, (db + 54) / 54));
        rec[head] = recording;
        head = (head + 1) % SIZE;
        count = Math.min(count + 1, SIZE);
        invalidate();
    }

    void clear() {
        count = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), mid = h / 2;
        float density = getResources().getDisplayMetrics().density;
        long now = SystemClock.uptimeMillis(), span = Engine.HISTORY_SECONDS * 1000L;
        paint.setStrokeWidth(density);
        paint.setColor(0x33808080);
        c.drawLine(0, mid, w, mid, paint);
        c.drawText("← " + Engine.HISTORY_SECONDS + " s", 2 * density, text.getTextSize(), text);
        paint.setStrokeWidth(Math.max(2, 2 * density));
        for (int k = 0; k < count; k++) {
            int i = Math.floorMod(head - 1 - k, SIZE);
            long age = now - times[i];
            if (age > span) break;
            float x = w - age * w / span;
            float half = Math.max(density, values[i] * (mid - density));
            paint.setColor(rec[i] ? 0xFFE53935 : accent);
            paint.setAlpha(rec[i] ? 255 : (int) (110 + 145 * (1 - age / (float) span)));
            c.drawLine(x, mid - half, x, mid + half, paint);
        }
    }
}
