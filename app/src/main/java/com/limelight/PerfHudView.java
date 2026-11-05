package com.limelight;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Performance HUD modelled on Apple's Metal Performance HUD.
 *
 * Layout conventions taken from the Metal HUD: fixed-size rolling window (120 frames for
 * the averages), one inline chart per metric, and a compact dark panel with monospaced
 * digits. Rows without data are simply omitted, so the panel stays small when few things
 * are known (for example before the first network sample arrives).
 *
 * Everything is drawn in one onDraw pass with pre-allocated Paint/Path objects, because a
 * HUD that allocates per frame ends up perturbing the thing it is measuring.
 */
public class PerfHudView extends View {

    /** One row of the HUD. */
    public static class Row {
        public final String label;
        public final String value;
        public final float[] series;
        public final float threshold;
        public final boolean invert;
        public final int colorHint;

        /**
         * @param label     short left-hand label, e.g. "FPS".
         * @param value     right-hand value text.
         * @param series    rolling series for the inline chart; null for a plain row.
         * @param threshold value that marks "bad"; used to colour the chart.
         * @param invert    true when a *higher* series value is worse (frametime), false when
         *                  lower is worse (fps).
         * @param colorHint fixed colour for the value text, or 0 to colour by threshold.
         */
        public Row(String label, String value, float[] series, float threshold, boolean invert, int colorHint) {
            this.label = label;
            this.value = value;
            this.series = series;
            this.threshold = threshold;
            this.invert = invert;
            this.colorHint = colorHint;
        }
    }

    private String header = "";
    private Row[] rows = new Row[0];

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curveBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thresholdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panelRect = new RectF();
    private final Path curvePath = new Path();

    private float density;
    private float rowHeight;
    private float padding;
    private float chartWidth;

    private static final int COLOR_TEXT = 0xFFEDEDED;
    private static final int COLOR_LABEL = 0xFF9AA0A6;
    private static final int COLOR_GOOD = 0xFF66E0A3;
    private static final int COLOR_WARN = 0xFFFFD54F;
    private static final int COLOR_BAD = 0xFFFF7043;

    public PerfHudView(Context context) {
        this(context, null);
    }

    public PerfHudView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        rowHeight = 15f * density;
        padding = 7f * density;
        chartWidth = 66f * density;

        bgPaint.setColor(0xB4101014);
        bgPaint.setStyle(Paint.Style.FILL);

        Typeface mono = Typeface.create("monospace", Typeface.NORMAL);
        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(9.5f * density);
        labelPaint.setTypeface(mono);

        valuePaint.setColor(COLOR_TEXT);
        valuePaint.setTextSize(11f * density);
        valuePaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));

        headerPaint.setColor(COLOR_LABEL);
        headerPaint.setTextSize(9f * density);
        headerPaint.setTypeface(mono);

        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(1.1f * density);
        curveBgPaint.setStyle(Paint.Style.FILL);
        curveBgPaint.setColor(0x1FFFFFFF);

        thresholdPaint.setStyle(Paint.Style.STROKE);
        thresholdPaint.setStrokeWidth(0.8f * density);
        thresholdPaint.setColor(0x66FFD54F);
    }

    /** Replace the displayed data and redraw. */
    public void setData(String header, Row[] rows) {
        this.header = header == null ? "" : header;
        this.rows = rows == null ? new Row[0] : rows;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float textWidth = 0f;
        for (Row row : rows) {
            textWidth = Math.max(textWidth, valuePaint.measureText(row.value));
            textWidth = Math.max(textWidth, labelPaint.measureText(row.label));
        }
        float width = padding * 2 + labelPaint.measureText("LOST") + 10f * density
                + textWidth + 8f * density + chartWidth;
        float height = padding * 2 + (header.isEmpty() ? 0 : rowHeight) + rows.length * rowHeight;
        setMeasuredDimension(
                resolveSize((int) width, widthMeasureSpec),
                resolveSize((int) height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        panelRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(panelRect, 6f * density, 6f * density, bgPaint);

        float y = padding + headerPaint.getTextSize();
        if (!header.isEmpty()) {
            canvas.drawText(header, padding, y, headerPaint);
            y += rowHeight;
        }

        float labelX = padding;
        float valueX = padding + labelPaint.measureText("LOST") + 10f * density;
        float chartRight = getWidth() - padding;
        float chartLeft = chartRight - chartWidth;

        for (Row row : rows) {
            float baseline = y + valuePaint.getTextSize() * 0.82f;

            canvas.drawText(row.label, labelX, baseline, labelPaint);

            int color = row.colorHint != 0 ? row.colorHint : colorFor(row);
            valuePaint.setColor(color);
            canvas.drawText(row.value, valueX, baseline, valuePaint);

            if (row.series != null && row.series.length > 1 && chartLeft > valueX) {
                drawChart(canvas, row, chartLeft, y - rowHeight * 0.55f, chartRight, y + rowHeight * 0.35f, color);
            }

            y += rowHeight;
        }
    }

    private int colorFor(Row row) {
        if (row.series == null || row.series.length == 0) {
            return COLOR_TEXT;
        }
        float last = row.series[row.series.length - 1];
        if (row.threshold <= 0f) {
            return COLOR_TEXT;
        }
        boolean bad = row.invert ? last > row.threshold : last < row.threshold;
        boolean warn = row.invert ? last > row.threshold * 0.9f : last < row.threshold * 1.1f;
        return bad ? COLOR_BAD : (warn ? COLOR_WARN : COLOR_GOOD);
    }

    private void drawChart(Canvas canvas, Row row, float left, float top, float right, float bottom, int color) {
        float[] series = row.series;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : series) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (row.threshold > 0f) {
            // Keep the threshold line inside the chart so the shape stays comparable
            min = Math.min(min, row.threshold * 0.9f);
            max = Math.max(max, row.threshold * 1.1f);
        }
        if (max - min < 1e-3f) {
            max = min + 1f;
        }

        canvas.drawRect(left, top, right, bottom, curveBgPaint);

        // Threshold reference line
        if (row.threshold > 0f && row.threshold >= min && row.threshold <= max) {
            float ty = bottom - (row.threshold - min) / (max - min) * (bottom - top);
            canvas.drawLine(left, ty, right, ty, thresholdPaint);
        }

        curvePath.reset();
        int n = series.length;
        for (int i = 0; i < n; i++) {
            float x = left + (right - left) * i / (n - 1);
            float norm = (series[i] - min) / (max - min);
            float y = bottom - norm * (bottom - top);
            if (i == 0) {
                curvePath.moveTo(x, y);
            } else {
                curvePath.lineTo(x, y);
            }
        }
        curvePaint.setColor(color);
        canvas.drawPath(curvePath, curvePaint);
    }
}
