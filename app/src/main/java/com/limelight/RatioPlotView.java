package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A strip chart of the display's reported HDR/SDR ratio against time.
 *
 * The hold test watches for a checkerboard to fade, and the ratio is the only independent
 * number available while that happens - if the panel is limiting itself both should move at
 * once, and if only the pattern fades the eye adapted instead. A single reading cannot show
 * that; the shape over time can.
 */
public class RatioPlotView extends View {

    private static final int CAPACITY = 600;   // ten minutes at one sample per second

    private final float[] samples = new float[CAPACITY];
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

    public void add(float ratio) {
        if (ratio <= 0f) {
            return;
        }
        samples[head] = ratio;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        invalidate();
    }

    private float sample(int index) {
        int start = (head - count + CAPACITY) % CAPACITY;
        return samples[(start + index) % CAPACITY];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float pad = 6f;

        canvas.drawRect(pad, pad, w - pad, h - pad, axisPaint);

        if (count < 2) {
            canvas.drawText("—", pad + 8f, h / 2f, textPaint);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float v = sample(i);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        // Keep a little air so a flat line does not sit on the frame.
        float span = Math.max(0.05f, max - min);
        min -= span * 0.15f;
        max += span * 0.15f;

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = pad + (w - 2 * pad) * i / (float) (count - 1);
            float y = h - pad - (h - 2 * pad) * (sample(i) - min) / (max - min);
            if (i == 0) {
                path.moveTo(x, y);
            }
            else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);

        canvas.drawText(String.format(java.util.Locale.US, "%.2f", max), pad + 8f, pad + 22f, textPaint);
        canvas.drawText(String.format(java.util.Locale.US, "%.2f", min), pad + 8f, h - pad - 8f, textPaint);
    }
}
