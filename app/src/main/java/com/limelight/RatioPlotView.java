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
 * A strip chart for the two numbers that can move while the hold test runs.
 *
 * The HDR/SDR ratio turned out to be a driver constant on this device - it reports the same
 * value for minutes on end, and a flat line says nothing. The screen brightness value does move
 * when the panel limits itself, so both are sampled: the chart draws whichever actually varies
 * and labels which one that is. When neither moves, the label says so instead of pretending a
 * straight line is a measurement.
 */
public class RatioPlotView extends View {

    private static final int CAPACITY = 600;   // ten minutes at one sample per second

    private final float[] ratio = new float[CAPACITY];
    private final float[] brightness = new float[CAPACITY];
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

    public void add(float ratioValue, float brightnessValue) {
        ratio[head] = ratioValue;
        brightness[head] = brightnessValue;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        invalidate();
    }

    private float sample(float[] series, int index) {
        int start = (head - count + CAPACITY) % CAPACITY;
        return series[(start + index) % CAPACITY];
    }

    /** Range of a series, ignoring samples that were never available (<= 0). */
    private float[] range(float[] series) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int seen = 0;
        for (int i = 0; i < count; i++) {
            float v = sample(series, i);
            if (v <= 0f) {
                continue;
            }
            min = Math.min(min, v);
            max = Math.max(max, v);
            seen++;
        }
        return seen < 2 ? null : new float[] {min, max};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float pad = 6f;
        float labelHeight = 26f;

        canvas.drawRect(pad, pad, w - pad, h - pad - labelHeight, axisPaint);

        if (count < 3) {
            canvas.drawText("…", pad + 8f, h / 2f, textPaint);
            return;
        }

        float[] ratioRange = range(ratio);
        float[] brightnessRange = range(brightness);

        boolean useRatio = ratioRange != null && ratioRange[1] - ratioRange[0] > 0.01f;
        boolean useBrightness = brightnessRange != null
                && brightnessRange[1] - brightnessRange[0] > 0.0005f;

        float[] series;
        float[] bounds;
        String label;
        if (useRatio) {
            series = ratio;
            bounds = ratioRange;
            label = "HDR/SDR";
        }
        else if (useBrightness) {
            series = brightness;
            bounds = brightnessRange;
            label = "亮度";
        }
        else {
            canvas.drawText("两个值都没变化", pad + 8f, h - pad - 6f, textPaint);
            return;
        }

        float min = bounds[0];
        float max = bounds[1];
        float span = Math.max(1e-4f, max - min);
        min -= span * 0.15f;
        max += span * 0.15f;

        path.reset();
        int plotted = 0;
        for (int i = 0; i < count; i++) {
            float v = sample(series, i);
            if (v <= 0f) {
                continue;
            }
            float x = pad + (w - 2 * pad) * i / (float) (count - 1);
            float y = (h - pad - labelHeight) - (h - 2 * pad - labelHeight) * (v - min) / (max - min);
            if (plotted == 0) {
                path.moveTo(x, y);
            }
            else {
                path.lineTo(x, y);
            }
            plotted++;
        }
        canvas.drawPath(path, linePaint);

        canvas.drawText(String.format(Locale.US, "%.2f", bounds[1]), pad + 8f, pad + 22f, textPaint);
        canvas.drawText(String.format(Locale.US, "%.2f", bounds[0]), pad + 8f,
                h - pad - labelHeight - 6f, textPaint);
        canvas.drawText(label, pad + 8f, h - pad - 6f, textPaint);
    }
}
