package com.limelight.binding.video;

/**
 * Structured performance data for the HUD.
 *
 * The text overlay only needs numbers, but a HUD with charts needs the rolling series too,
 * so the renderer publishes this once per second and the view decides what to draw.
 */
public class PerfHudData {
    // Stream
    public String streamSummary;      // "2608x1200 @120"
    public String decoderName;

    // Frames
    public float renderedFps;
    public float receivedFps;
    public float frameTimeMedianMs;
    public float frameTimeP99Ms;
    public float onePercentLowFps;
    public float fivePercentLowFps;
    public float timeBelowTargetFraction;   // 0..1
    public float[] frameTimeSeries;         // ms, oldest first

    // Network
    public int networkLatencyMs = -1;
    public int hostLatencyMs = -1;
    public float packetLossPercent = -1f;
    public float[] networkSeries;           // ms, oldest first

    // Decode
    public float decodeTimeMs;

    // Display
    public int displayRefreshHz = -1;
    public boolean hdrActive;
}
