package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import com.limelight.LimeLog;

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

    /** One line for the settings row. */
    public String summary() {
        if (brightness == null) {
            return "No usable brightness report on this display";
        }
        return String.format(Locale.US, "Peak %.0f nit · black %.2f nit · %s%s",
                brightness.maxNits, brightness.minNits, getTypeSummary(),
                brightness.estimatedFromRatio ? " (estimated)" : "");
    }

    /** Multi-line detail for the dialog behind the row. */
    public String detail() {
        StringBuilder sb = new StringBuilder();
        sb.append("HDR screen: ").append(screenHdr ? "yes" : "no").append('\n');
        sb.append("Wide colour gamut: ").append(wideColorGamut ? "yes" : "no").append('\n');
        sb.append("HDR formats: ").append(getTypeSummary()).append('\n');
        sb.append("EDID peak: ")
                .append(edidPeakNits == null ? "not reported" : String.format(Locale.US, "%.0f nit", edidPeakNits))
                .append('\n');
        if (ratioAvailable) {
            sb.append(String.format(Locale.US, "HDR/SDR ratio: now %.2f, highest %.2f%n", hdrSdrRatio, highestHdrSdrRatio));
        }
        else {
            sb.append("HDR/SDR ratio: unavailable\n");
        }

        sb.append('\n');
        if (brightness == null) {
            sb.append("Report to host: none (the display did not give a consistent set)\n");
        }
        else {
            sb.append("Report to host: ").append(brightness.describe()).append('\n');
            if (brightness.estimatedFromRatio) {
                sb.append("Peak estimated from the HDR/SDR ratio times 300 nit of assumed SDR white\n");
            }
        }
        if (edidPeakNits != null && ratioAvailable && highestHdrSdrRatio > 1f) {
            float impliedSdrWhite = edidPeakNits / highestHdrSdrRatio;
            if (impliedSdrWhite < 100f) {
                sb.append(String.format(Locale.US,
                        "Hint: peak divided by ratio implies %.0f nit of SDR white, which is implausibly"
                                + " low - the driver may be under-reporting the peak\n", impliedSdrWhite));
            }
        }
        return sb.toString();
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
