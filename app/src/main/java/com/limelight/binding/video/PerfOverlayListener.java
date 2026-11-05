package com.limelight.binding.video;

public interface PerfOverlayListener {
    /** Plain-text overlay (numbers mode). */
    void onPerfUpdate(final String text);

    /** Structured data for the chart HUD. */
    void onPerfHud(final PerfHudData data);
}
