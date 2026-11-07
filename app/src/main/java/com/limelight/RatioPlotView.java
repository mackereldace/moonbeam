package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Strip chart of the display's HDR/SDR ratio against time.
 *
 * x is the sample index - one sample every 500 ms - and y is the ratio itself. A sample of zero
 * means the value could not be read at that instant; those are breaks in the line, not points
 * at zero, because joining across a gap invents a measurement that never happened.
 *
 * The previous version silently switched to plotting the brightness series when the ratio had
 * too few usable samples, which produced a flat line while the text underneath kept updating -
 * a chart that disagrees with its own numbers is worse than no chart.
 */
public class RatioPlotView extends View {

    private static final int CAPACITY = 1200;   // ten minutes at two samples per second

    private final float[] ratio = new float[CAPACITY];
    private int count;
    private int head;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public RatioPlotView(Context context) {
        super(context);
        init();
    }

    public RatioPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f);
        linePaint.setColor(Color.parseColor("#7FE0C070"));

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1f);
        axisPaint.setColor(Color.parseColor("#33FFFFFF"));

        textPaint.setColor(Color.parseColor("#99FFFFFF"));
        textPaint.setTextSize(20f);
    }

    public void clear() {
        count = 0;
        head = 0;
        invalidate();
    }

    public void add(float value) {
        ratio[head] = value > 0f ? value : 0f;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        invalidate();
    }

    private float sample(int index) {
        int start = (head - count + CAPACITY) % CAPACITY;
        return ratio[(start + index) % CAPACITY];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float pad = 6f;
        float labelHeight = 26f;
        float plotBottom = h - pad - labelHeight;

        // A hairline frame inside the rounded card, kept away from the corners.
        canvas.drawLine(pad + 4f, pad + 4f, w - pad - 4f, pad + 4f, axisPaint);
        canvas.drawLine(pad + 4f, plotBottom - 4f, w - pad - 4f, plotBottom - 4f, axisPaint);

        int valid = 0;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float last = 0f;
        for (int i = 0; i < count; i++) {
            float v = sample(i);
            if (v <= 0f) {
                continue;
            }
            valid++;
            last = v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        if (valid < 2) {
            canvas.drawText("读不到比值", pad + 10f, h / 2f, textPaint);
            return;
        }

        float span = Math.max(0.02f, max - min);
        float lo = min - span * 0.2f;
        float hi = max + span * 0.2f;

        path.reset();
        boolean drawing = false;
        for (int i = 0; i < count; i++) {
            float v = sample(i);
            if (v <= 0f) {
                drawing = false;
                continue;
            }
            float x = pad + (w - 2 * pad) * i / (float) Math.max(1, count - 1);
            float y = plotBottom - (plotBottom - pad) * (v - lo) / (hi - lo);
            if (!drawing) {
                path.moveTo(x, y);
                drawing = true;
            }
            else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);

        canvas.drawText(String.format(Locale.US, "%.2f", max), pad + 8f, pad + 20f, textPaint);
        canvas.drawText(String.format(Locale.US, "%.2f", min), pad + 8f, plotBottom - 6f, textPaint);
        canvas.drawText(String.format(Locale.US, "HDR/SDR %.2f", last),
                pad + 10f, h - pad - 6f, textPaint);
    }
}
