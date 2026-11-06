package com.limelight.utils;

import java.util.Locale;

/**
 * The three HDR luminance values a client reports to the host, and the rules for when a
 * report is fit to send at all.
 *
 * The host uses these as the target for its tone mapping and HDR metadata, so they describe
 * the display the stream will actually be watched on. The three are deliberately treated as
 * one atomic set: a real peak paired with an invented black level describes a contrast ratio
 * no panel has, and it makes a bad picture impossible to attribute afterwards. When any part
 * fails validation the whole report is dropped and the host falls back to its own defaults.
 */
public final class HdrBrightness {

    /** SDR white assumed when only the HDR/SDR ratio is known. */
    private static final float ASSUMED_SDR_WHITE_NITS = 300f;

    /** A ratio of 1 means no headroom at all, which is not a usable measurement. */
    private static final float MIN_USABLE_RATIO = 1f;

    public static final int MIN_PEAK_OVERRIDE_NITS = 300;
    public static final int MAX_PEAK_OVERRIDE_NITS = 4000;

    /** Peak luminance of a small area, in nits. */
    public final float maxNits;
    /** Black level, in nits. */
    public final float minNits;
    /** Peak luminance sustained over the whole frame, in nits. */
    public final float maxFullFrameNits;
    /** True when the values came from the HDR/SDR ratio rather than from EDID. */
    public final boolean estimatedFromRatio;

    private HdrBrightness(float maxNits, float minNits, float maxFullFrameNits,
                          boolean estimatedFromRatio) {
        this.maxNits = maxNits;
        this.minNits = minNits;
        this.maxFullFrameNits = maxFullFrameNits;
        this.estimatedFromRatio = estimatedFromRatio;
    }

    /**
     * Build a report, or null when the three values cannot all be true at once.
     *
     * Ranges follow the host's own validation: a peak outside 1..10000 nits, a black level
     * outside 0..100 nits, a full-frame value outside 1..peak, or a black level brighter than
     * the full-frame value all mean the display driver is not describing a real panel.
     */
    public static HdrBrightness create(float maxNits, float minNits, float maxFullFrameNits) {
        if (!isFinite(maxNits) || !isFinite(minNits) || !isFinite(maxFullFrameNits)) {
            return null;
        }
        if (maxNits < 1f || maxNits > 10000f) {
            return null;
        }
        if (minNits < 0f || minNits > 100f) {
            return null;
        }
        if (maxFullFrameNits < 1f || maxFullFrameNits > maxNits || minNits > maxFullFrameNits) {
            return null;
        }
        return new HdrBrightness(maxNits, minNits, maxFullFrameNits, false);
    }

    /**
     * Build a report from what the display reports, estimating from the HDR/SDR ratio when the
     * EDID peak is missing or implausible.
     *
     * @param hdrSdrRatio largest usable HDR/SDR ratio for this display, or null when unknown.
     * @return the report, or null when nothing trustworthy can be derived.
     */
    public static HdrBrightness fromDisplay(float maxNits, float minNits, float maxFullFrameNits,
                                            Float hdrSdrRatio) {
        if (isFinite(maxNits) && maxNits >= 1f && maxNits <= 10000f) {
            return create(maxNits, minNits, maxFullFrameNits);
        }

        if (hdrSdrRatio == null || !isFinite(hdrSdrRatio) || hdrSdrRatio <= MIN_USABLE_RATIO) {
            return null;
        }

        float estimatedPeak = ASSUMED_SDR_WHITE_NITS * hdrSdrRatio;
        if (!isFinite(estimatedPeak) || estimatedPeak < 1f || estimatedPeak > 10000f) {
            return null;
        }

        boolean knownFullFrame = isFinite(maxFullFrameNits) && maxFullFrameNits >= 1f;
        float peak = Math.max(estimatedPeak, knownFullFrame ? maxFullFrameNits : 0f);
        float minimum = (isFinite(minNits) && minNits >= 0f && minNits <= 100f) ? minNits : 0.001f;
        float fullFrame = knownFullFrame ? maxFullFrameNits : Math.max(minimum, peak * 0.2f);

        HdrBrightness report = create(peak, minimum, fullFrame);
        if (report == null) {
            return null;
        }
        return new HdrBrightness(report.maxNits, report.minNits, report.maxFullFrameNits, true);
    }

    /**
     * Produce the report for this session, applying a manual peak override when one is set.
     *
     * The override replaces the peak only; the black level keeps whatever the display reported,
     * and the full-frame value is derived from the chosen peak. That keeps the triple plausible
     * instead of inventing two numbers as well.
     *
     * @param peakOverrideNits manual peak, or null to send the display report unchanged.
     */
    public static HdrBrightness forSession(HdrBrightness reported, Integer peakOverrideNits) {
        if (peakOverrideNits == null) {
            return reported;
        }

        float peak = Math.min(Math.max(peakOverrideNits,
                MIN_PEAK_OVERRIDE_NITS), MAX_PEAK_OVERRIDE_NITS);
        float minimum = reported != null ? reported.minNits : 0f;
        float fullFrame = Math.min(Math.max(peak * 0.2f, 100f), 1000f);
        return create(peak, minimum, fullFrame);
    }

    /**
     * Query parameters for the launch request. Float.toString keeps full precision and a
     * locale-independent decimal point; String.format would emit a comma in some locales and
     * the host would reject the report as malformed.
     */
    public String toQueryParameters() {
        return "&minBrightness=" + minNits
                + "&maxBrightness=" + maxNits
                + "&maxAverageBrightness=" + maxFullFrameNits;
    }

    /** Human readable one-liner for logs and the settings summary. */
    public String describe() {
        return String.format(Locale.US, "peak %.0f nit, black %.2f nit, full-frame %.0f nit%s",
                maxNits, minNits, maxFullFrameNits, estimatedFromRatio ? " (estimated)" : "");
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
