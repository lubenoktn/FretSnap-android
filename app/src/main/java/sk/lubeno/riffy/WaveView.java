package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Priebeh celej nahrávky (z {@link Waves}). V prehrávači ukazuje, kde sa hrá a slučku;
 * ťuknutie = skok na miesto, potiahnutie = vyznačenie slučky.
 */
public class WaveView extends View {
    interface Listener {
        void onSeek(float at);

        void onSelect(float from, float to);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent, slop;
    private byte[] peaks;
    private float position = -1, loopFrom = -1, loopTo = -1, downX, dragX = -1;
    private Listener listener;

    public WaveView(Context c, AttributeSet a) {
        super(c, a);
        accent = Look.accent(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    void setPeaks(byte[] p) {
        if (p == peaks) return;
        peaks = p;
        invalidate();
    }

    /** Kde sa práve hrá (0–1), -1 = nikde. */
    void setPosition(float at) {
        position = at;
        invalidate();
    }

    /** Slučka (0–1); -1 = od začiatku / do konca. */
    void setLoop(float from, float to) {
        loopFrom = from;
        loopTo = to;
        invalidate();
    }

    void setListener(Listener l) {
        listener = l;
    }

    @Override
    @SuppressWarnings("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return false;
        float w = Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                dragX = -1;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragX >= 0 || Math.abs(e.getX() - downX) > slop) {
                    dragX = e.getX();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float a = clamp(downX / w), b = clamp(e.getX() / w);
                if (dragX >= 0 && Math.abs(b - a) > 0.02f) listener.onSelect(Math.min(a, b), Math.max(a, b));
                else listener.onSeek(a);
                dragX = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragX = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), mid = h / 2;
        float density = getResources().getDisplayMetrics().density;
        // Slučka (alebo práve ťahaná)
        float from = dragX >= 0 ? Math.min(downX, dragX) / w : loopFrom;
        float to = dragX >= 0 ? Math.max(downX, dragX) / w : loopTo;
        if (from >= 0 || to >= 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Look.alpha(accent, 0x30));
            c.drawRect(Math.max(0, from) * w, 0, (to < 0 ? 1 : to) * w, h, paint);
        }
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (peaks == null || peaks.length == 0) {
            paint.setStrokeWidth(density);
            paint.setColor(0x44808080);
            c.drawLine(0, mid, w, mid, paint);
        } else {
            int n = peaks.length;
            float step = w / n;
            paint.setStrokeWidth(Math.max(1, step * 0.6f));
            for (int i = 0; i < n; i++) {
                float x = (i + 0.5f) * step;
                float half = Math.max(density / 2, (peaks[i] & 0xff) / 255f * (mid - density));
                boolean played = position >= 0 && x <= position * w;
                paint.setColor(played ? accent : 0x88808080);
                c.drawLine(x, mid - half, x, mid + half, paint);
            }
        }
        if (position >= 0) {
            paint.setStrokeWidth(Math.max(2, 2 * density));
            paint.setColor(accent);
            c.drawLine(position * w, 0, position * w, h, paint);
        }
    }
}
