package sk.lubeno.riffy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Looper na obrazovke: takty ako dieliky s dobami, bežec, kde slučka práve je, a pri čakaní
 * veľký odpočet dôb do začiatku nahrávania. Pri nahrávaní je všetko červené.
 */
public class LoopView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int accent, fg;
    private int bars = 2, beats = 4, layers;
    private double position = -1, countdown, recorded;
    private boolean recording, waiting;

    public LoopView(Context c, AttributeSet a) {
        super(c, a);
        accent = Look.accent(c);
        fg = Look.color(c, android.R.attr.textColorPrimary);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    void set(int bars, int beats, int layers, double position, boolean waiting, double countdown,
             boolean recording, double recorded) {
        this.bars = Math.max(1, bars);
        this.beats = Math.max(1, beats);
        this.layers = layers;
        this.position = position;
        this.waiting = waiting;
        this.countdown = countdown;
        this.recording = recording;
        this.recorded = recorded;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        float gap = 4 * d, top = h * 0.35f, bottom = h - 4 * d;
        float barW = (w - gap * (bars - 1)) / bars;
        int color = recording ? 0xFFE53935 : accent;
        for (int b = 0; b < bars; b++) {
            float x0 = b * (barW + gap);
            r.set(x0, top, x0 + barW, bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Look.alpha(layers > 0 ? accent : 0xFF808080, layers > 0 ? 0x40 : 0x22));
            c.drawRoundRect(r, 6 * d, 6 * d, paint);
            // Čo z kola je už nahraté
            double done = recording ? recorded : 0;
            float fill = (float) Math.max(0, Math.min(1, done * bars - b));
            if (fill > 0) {
                paint.setColor(Look.alpha(0xFFE53935, 0x77));
                r.set(x0, top, x0 + barW * fill, bottom);
                c.drawRoundRect(r, 6 * d, 6 * d, paint);
            }
            paint.setColor(0x55808080);
            paint.setStrokeWidth(d);
            for (int k = 1; k < beats; k++) {
                float x = x0 + barW * k / beats;
                c.drawLine(x, top + 8 * d, x, bottom - 8 * d, paint);
            }
        }
        if (position >= 0) {
            int bar = (int) Math.min(bars - 1, position * bars);
            float x = bar * (barW + gap) + (float) (position * bars - bar) * barW;
            paint.setColor(color);
            paint.setStrokeWidth(3 * d);
            c.drawLine(x, top - 4 * d, x, bottom + 2 * d, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(top * 0.62f);
        paint.setFakeBoldText(true);
        String text;
        if (waiting) {
            paint.setColor(0xFFE53935);
            text = countdown > 0.05 ? getContext().getString(R.string.loop_starts_in, (int) Math.ceil(countdown - 0.05))
                    : getContext().getString(R.string.loop_now);
        } else if (recording) {
            paint.setColor(0xFFE53935);
            text = layers > 0 ? getContext().getString(R.string.loop_rec_layer, layers + 1) : getContext().getString(R.string.loop_rec);
        } else {
            paint.setColor(fg);
            text = layers == 0 ? getContext().getString(R.string.loop_bars, getResources().getQuantityString(R.plurals.bars, bars, bars))
                    : getResources().getQuantityString(R.plurals.layers, layers, layers);
        }
        c.drawText(text, w / 2, top * 0.75f, paint);
        paint.setFakeBoldText(false);
    }
}
