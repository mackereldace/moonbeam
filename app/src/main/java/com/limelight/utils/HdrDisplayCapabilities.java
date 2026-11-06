package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the phone's own display says about its HDR ability and its brightness.
 *
 * Everything here is read-only. It exists so the app can show what it would report to the
 * host, and so the brightness report sent with a session can be traced back to a source:
 * EDID, the HDR/SDR ratio, or a manual override. Guessing what a panel can do and then not
 * being able to see where the guess came from is how colour bugs survive for months.
 */
public final class HdrDisplayCapabilities {

    /** Brightness report derived from the display, or null when nothing trustworthy exists. */
    public final HdrBrightness brightness;
    /** Peak the driver claims, kept even when the full report is rejected. */
    public final Float edidPeakNits;
    public final boolean screenHdr;
    public final boolean wideColorGamut;
    public final boolean ratioAvailable;
    public final float hdrSdrRatio;
    public final float highestHdrSdrRatio;
    public final int[] hdrTypes;

    private HdrDisplayCapabilities(HdrBrightness brightness, Float edidPeakNits, boolean screenHdr,
                                   boolean wideColorGamut, boolean ratioAvailable, float hdrSdrRatio,
                                   float highestHdrSdrRatio, int[] hdrTypes) {
        this.brightness = brightness;
        this.edidPeakNits = edidPeakNits;
        this.screenHdr = screenHdr;
        this.wideColorGamut = wideColorGamut;
        this.ratioAvailable = ratioAvailable;
        this.hdrSdrRatio = hdrSdrRatio;
        this.highestHdrSdrRatio = highestHdrSdrRatio;
        this.hdrTypes = hdrTypes;
    }

    /** Probe the default display; safe on every API level (older ones simply report less). */
    public static HdrDisplayCapabilities probe(Context context) {
        Display display = getDefaultDisplay(context);
        if (display == null) {
            return new HdrDisplayCapabilities(null, null, false, false, false, 1f, 1f, new int[0]);
        }
        return probe(context, display);
    }

    public static HdrDisplayCapabilities probe(Context context, Display display) {
        float maxNits = Float.NaN;
        float minNits = Float.NaN;
        float maxFullFrameNits = Float.NaN;
        int[] hdrTypes = new int[0];

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Display.HdrCapabilities caps = display.getHdrCapabilities();
                if (caps != null) {
                    maxNits = caps.getDesiredMaxLuminance();
                    minNits = caps.getDesiredMinLuminance();
                    maxFullFrameNits = caps.getDesiredMaxAverageLuminance();
                    hdrTypes = caps.getSupportedHdrTypes();
                }
            }
            catch (RuntimeException e) {
                LimeLog.warning("HDR capabilities unavailable: " + e.getMessage());
            }
        }

        boolean ratioAvailable = false;
        float ratio = 1f;
        float highestRatio = 1f;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ratioAvailable = display.isHdrSdrRatioAvailable();
                if (ratioAvailable) {
                    ratio = display.getHdrSdrRatio();
                    highestRatio = ratio;
                }
            }
            catch (RuntimeException e) {
                LimeLog.warning("HDR/SDR ratio unavailable: " + e.getMessage());
                ratioAvailable = false;
            }
            // highestHdrSdrRatio arrived one release later; keep the current ratio as a floor.
            if (Build.VERSION.SDK_INT >= 36 && ratioAvailable) {
                try {
                    highestRatio = display.getHighestHdrSdrRatio();
                }
                catch (RuntimeException | LinkageError e) {
                    highestRatio = ratio;
                }
            }
        }

        Float usableRatio = null;
        if (ratioAvailable) {
            if (highestRatio > 1f) {
                usableRatio = highestRatio;
            }
            else if (ratio > 1f) {
                usableRatio = ratio;
            }
        }

        HdrBrightness report = HdrBrightness.fromDisplay(maxNits, minNits, maxFullFrameNits, usableRatio);

        boolean screenHdr = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context != null) {
            try {
                screenHdr = context.getResources().getConfiguration().isScreenHdr();
            }
            catch (RuntimeException ignored) {
            }
        }

        boolean wideColorGamut = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                wideColorGamut = display.isWideColorGamut();
            }
            catch (RuntimeException ignored) {
            }
        }

        Float edidPeak = (maxNits == maxNits && maxNits > 0f) ? maxNits : null;

        return new HdrDisplayCapabilities(report, edidPeak, screenHdr, wideColorGamut,
                ratioAvailable, ratio, highestRatio, hdrTypes);
    }

    /** "HDR10 · HLG · Dolby Vision" style summary of the declared HDR formats. */
    public String getTypeSummary() {
        List<String> names = new ArrayList<>();
        for (int type : hdrTypes) {
            if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                names.add("HDR10");
            }
            else if (type == Display.HdrCapabilities.HDR_TYPE_HLG) {
                names.add("HLG");
            }
            else if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) {
                names.add("Dolby Vision");
            }
            else if (Build.VERSION.SDK_INT >= 29 && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS) {
                names.add("HDR10+");
            }
        }
        if (names.isEmpty()) {
            return "none";
        }
        // String.join needs API 26 and this app still installs down to 21.
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                joined.append(" \u00b7 ");
            }
            joined.append(names.get(i));
        }
        return joined.toString();
    }

    /** Settings row summary: one value per line, because three numbers on one line is unreadable. */
    public String summary(Context context) {
        if (brightness == null) {
            return context.getString(R.string.hdr_report_unavailable);
        }
        String peak = context.getString(R.string.hdr_report_peak, oneDecimal(brightness.maxNits, 0));
        if (brightness.estimatedFromRatio) {
            peak = context.getString(R.string.hdr_report_estimated, peak);
        }
        String black = context.getString(R.string.hdr_report_black, oneDecimal(brightness.minNits, 2));
        return peak + "\n" + black + "\n" + getTypeSummary();
    }

    /** Multi-line detail for the dialog behind the row. */
    public String detail(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.getString(R.string.hdr_detail_screen,
                context.getString(screenHdr ? R.string.common_yes : R.string.common_no))).append('\n');
        sb.append(context.getString(R.string.hdr_detail_wide_gamut,
                context.getString(wideColorGamut ? R.string.common_yes : R.string.common_no))).append('\n');
        sb.append(context.getString(R.string.hdr_detail_formats, getTypeSummary())).append('\n');
        sb.append(edidPeakNits == null
                ? context.getString(R.string.hdr_detail_edid_peak_none)
                : context.getString(R.string.hdr_detail_edid_peak, oneDecimal(edidPeakNits, 0))).append('\n');
        sb.append(ratioAvailable
                ? context.getString(R.string.hdr_detail_ratio,
                        oneDecimal(hdrSdrRatio, 2), oneDecimal(highestHdrSdrRatio, 2))
                : context.getString(R.string.hdr_detail_ratio_unavailable)).append('\n');

        sb.append('\n');
        if (brightness == null) {
            sb.append(context.getString(R.string.hdr_detail_report_none));
        }
        else {
            String triple = context.getString(R.string.hdr_report_peak, oneDecimal(brightness.maxNits, 0))
                    + " \u00b7 "
                    + context.getString(R.string.hdr_report_black, oneDecimal(brightness.minNits, 2))
                    + " \u00b7 "
                    + context.getString(R.string.hdr_detail_full_frame, oneDecimal(brightness.maxFullFrameNits, 0));
            sb.append(context.getString(R.string.hdr_detail_report, triple));
            if (brightness.estimatedFromRatio) {
                sb.append('\n').append(context.getString(R.string.hdr_detail_estimated_note));
            }
        }

        if (edidPeakNits != null && ratioAvailable && highestHdrSdrRatio > 1f) {
            float impliedSdrWhite = edidPeakNits / highestHdrSdrRatio;
            if (impliedSdrWhite < 100f) {
                sb.append('\n').append(context.getString(R.string.hdr_detail_implied_hint,
                        oneDecimal(impliedSdrWhite, 0)));
            }
        }
        return sb.toString();
    }

    private static String oneDecimal(float value, int decimals) {
        return String.format(Locale.US, decimals == 0 ? "%.0f" : "%.2f", value);
    }

    private static Display getDefaultDisplay(Context context) {
        if (context == null) {
            return null;
        }
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                return dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
        }
        catch (RuntimeException e) {
            LimeLog.warning("DisplayManager unavailable: " + e.getMessage());
        }
        return null;
    }
}
