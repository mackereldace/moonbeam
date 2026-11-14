package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws the pointer the stream itself does not contain.
 *
 * Moonlight sends mouse input to the host but never receives the host's cursor back, so on a Mac
 * there is simply nothing on screen to aim with. This overlays a pointer at the position the last
 * input went to, which is what the user is actually pointing at.
 */
public class CursorOverlayView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float x = -1f;
    private float y = -1f;

    public CursorOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        edge.setColor(Color.parseColor("#CC000000"));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2.5f);
        setClickable(false);
        setFocusable(false);
    }

    public void setPosition(float px, float py) {
        if (px == x && py == y) {
            return;
        }
        x = px;
        y = py;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (x < 0 || y < 0) {
            return;
        }
        // A plain arrow, tip at the position: readable over both bright and dark content because
        // it is filled white with a dark outline.
        float size = 26f;
        arrow.reset();
        arrow.moveTo(x, y);
        arrow.lineTo(x, y + size);
        arrow.lineTo(x + size * 0.30f, y + size * 0.76f);
        arrow.lineTo(x + size * 0.55f, y + size * 1.10f);
        arrow.lineTo(x + size * 0.74f, y + size * 1.01f);
        arrow.lineTo(x + size * 0.49f, y + size * 0.67f);
        arrow.lineTo(x + size * 0.80f, y + size * 0.66f);
        arrow.close();
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, edge);
    }
}
