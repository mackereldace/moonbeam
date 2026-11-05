package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Frame interval statistics.
 *
 * The raw measurement is the frame interval (frametime); FPS is just 1000/frametime.
 * Everything here follows the definitions used by RTSS / MSI Afterburner / CapFrameX:
 *
 *  - percentiles are about frame counts: the P99 frametime is the interval that 99% of
 *    frames are below;
 *  - "x% low" uses the integral definition (Afterburner, CapFrameX >= 1.5.3): sort the
 *    intervals descending, keep adding until the accumulated time reaches x% of the total,
 *    and take the FPS of the interval that crossed that point. Semantics: "x% of the time
 *    the frame rate was below this value";
 *  - the percentage of time spent below a threshold is also exposed, which is easier to
 *    interpret than any of the low-percentile numbers.
 *
 * Timestamps come from System.nanoTime(). SystemClock.uptimeMillis() only has 1 ms
 * resolution, which is a 12% quantisation error on an 8.33 ms frame at 120 fps.
 *
 * Written on the render thread, read from the overlay thread; guarded by a lock.
 */
public class FrameIntervalTracker {
    private static final int CAPACITY = 1024;
    private static final int MIN_SAMPLES = 8;
    private static final long MAX_INTERVAL_NS = 2_000_000_000L;

    private final float[] intervalMs = new float[CAPACITY];
    private final long[] stampNs = new long[CAPACITY];
    private int writeIndex = 0;
    private int count = 0;
    private long lastTimestampNs = 0;
    private final float windowSeconds;

    public FrameIntervalTracker() {
        this(6.0f);
    }

    public FrameIntervalTracker(float windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    /** Record one presented frame. Must be called from the same thread every time. */
    public void recordFrame() {
        long now = System.nanoTime();
        long last;
        synchronized (this) {
            last = lastTimestampNs;
            lastTimestampNs = now;
        }
        if (last <= 0L) {
            return;
        }

        long deltaNs = now - last;
        // A multi-second gap (app switched away, codec stall) says nothing about pacing.
        if (deltaNs <= 0L || deltaNs > MAX_INTERVAL_NS) {
            return;
        }

        synchronized (this) {
            intervalMs[writeIndex] = deltaNs / 1_000_000f;
            stampNs[writeIndex] = now;
            writeIndex = (writeIndex + 1) % CAPACITY;
            if (count < CAPACITY) {
                count++;
            }
        }
    }

    /** Snapshot of the current window, or null when there is not enough data yet. */
    public Snapshot snapshot() {
        float[] values = collectWindow();
        if (values == null) {
            return null;
        }
        Arrays.sort(values);

        int n = values.length;
        float median = values[n / 2];
        float p95 = values[Math.min(n - 1, (int) (n * 0.95f))];
        float p99 = values[Math.min(n - 1, (int) (n * 0.99f))];

        float sum = 0f;
        for (float v : values) {
            sum += v;
        }
        float mean = sum / n;

        return new Snapshot(
                n,
                mean > 0f ? 1000f / mean : 0f,
                median,
                p95,
                p99,
                values[n - 1],
                integralLowFps(values, 0.01f),
                integralLowFps(values, 0.05f),
                p99 > 0f ? 1000f / p99 : 0f,
                p99 - median
        );
    }

    /**
     * Recent intervals in time order (oldest first) for the scrolling graph.
     *
     * @param maxPoints maximum number of points; longer windows are decimated evenly.
     * @return null when there is not enough data.
     */
    public float[] recentSeries(int maxPoints) {
        float[] window = collectWindow();
        if (window == null) {
            return null;
        }
        if (window.length <= maxPoints) {
            return window;
        }
        float[] out = new float[maxPoints];
        for (int i = 0; i < maxPoints; i++) {
            out[i] = window[(int) ((long) i * (window.length - 1) / (maxPoints - 1))];
        }
        return out;
    }

    /**
     * Fraction of elapsed time spent in frames slower than the threshold.
     *
     * @param thresholdMs threshold frame interval in milliseconds.
     * @return 0.0-1.0.
     */
    public float timeBelowFraction(float thresholdMs) {
        float[] window = collectWindow();
        if (window == null) {
            return 0f;
        }
        float total = 0f;
        float slow = 0f;
        for (float v : window) {
            total += v;
            if (v > thresholdMs) {
                slow += v;
            }
        }
        return total > 0f ? slow / total : 0f;
    }

    public void clear() {
        synchronized (this) {
            count = 0;
            writeIndex = 0;
            lastTimestampNs = 0L;
        }
    }

    /** Collect the in-window samples in arrival order, or null when too few. */
    private float[] collectWindow() {
        synchronized (this) {
            if (count < MIN_SAMPLES) {
                return null;
            }
            long cutoff = System.nanoTime() - (long) (windowSeconds * 1_000_000_000L);
            float[] tmp = new float[count];
            int taken = 0;
            // Walk backwards from the newest sample, stop at the window boundary
            for (int i = 0; i < count; i++) {
                int idx = (writeIndex - 1 - i + CAPACITY) % CAPACITY;
                if (stampNs[idx] < cutoff) {
                    break;
                }
                tmp[taken++] = intervalMs[idx];
            }
            if (taken < MIN_SAMPLES) {
                return null;
            }
            float[] ordered = new float[taken];
            // Reverse into chronological order
            for (int i = 0; i < taken; i++) {
                ordered[i] = tmp[taken - 1 - i];
            }
            return ordered;
        }
    }

    /** Integral x% low from an ascending sorted array. */
    private static float integralLowFps(float[] sortedAsc, float fraction) {
        float total = 0f;
        for (float v : sortedAsc) {
            total += v;
        }
        if (total <= 0f) {
            return 0f;
        }
        float budget = total * fraction;
        float acc = 0f;
        for (int i = sortedAsc.length - 1; i >= 0; i--) {
            acc += sortedAsc[i];
            if (acc >= budget) {
                return sortedAsc[i] > 0f ? 1000f / sortedAsc[i] : 0f;
            }
        }
        return sortedAsc[0] > 0f ? 1000f / sortedAsc[0] : 0f;
    }

    /** One statistics snapshot. */
    public static class Snapshot {
        public final int samples;
        public final float fps;
        public final float medianMs;
        public final float p95Ms;
        public final float p99Ms;
        public final float worstMs;
        /** Integral 1% low: 1% of the time the frame rate was below this. */
        public final float onePercentLowFps;
        /** Integral 5% low. */
        public final float fivePercentLowFps;
        /** P99 percentile expressed in FPS (frame-count definition, kept for reference). */
        public final float p99PercentileFps;
        /** p99 minus median, i.e. how much the tail sticks out. */
        public final float jitterMs;

        Snapshot(int samples, float fps, float medianMs, float p95Ms, float p99Ms, float worstMs,
                 float onePercentLowFps, float fivePercentLowFps, float p99PercentileFps, float jitterMs) {
            this.samples = samples;
            this.fps = fps;
            this.medianMs = medianMs;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
            this.worstMs = worstMs;
            this.onePercentLowFps = onePercentLowFps;
            this.fivePercentLowFps = fivePercentLowFps;
            this.p99PercentileFps = p99PercentileFps;
            this.jitterMs = jitterMs;
        }
    }
}
