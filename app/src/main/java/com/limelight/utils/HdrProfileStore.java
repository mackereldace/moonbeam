package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.limelight.R;

import java.util.Locale;

/**
 * Storage for measured HDR values.
 *
 * The system's own report is never stored - it comes from the display and is read live. What is
 * stored are slots the user fills in one value at a time: a run measures one of peak, full-frame
 * or black, and writes it into the chosen slot. Keeping the three fields independent means a
 * peak measured on one day and a black level measured on another end up in the same profile
 * instead of overwriting each other.
 */
public final class HdrProfileStore {

    public static final int SLOT_COUNT = 5;

    private static final String KEY_PEAK = "hdr_slot%d_peak";
    private static final String KEY_GLOBAL = "hdr_slot%d_global";
    private static final String KEY_BLACK = "hdr_slot%d_black";
    private static final String KEY_STABILITY = "hdr_slot%d_stability";

    public static final int FIELD_PEAK = 0;
    public static final int FIELD_GLOBAL = 1;
    public static final int FIELD_BLACK = 2;
    /** Seconds the full-frame level held before the checkerboard faded. */
    public static final int FIELD_STABILITY = 3;

    private HdrProfileStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static String key(int field, int slot) {
        switch (field) {
            case FIELD_GLOBAL:
                return String.format(Locale.US, KEY_GLOBAL, slot);
            case FIELD_BLACK:
                return String.format(Locale.US, KEY_BLACK, slot);
            case FIELD_STABILITY:
                return String.format(Locale.US, KEY_STABILITY, slot);
            default:
                return String.format(Locale.US, KEY_PEAK, slot);
        }
    }

    /** 0 means "not measured yet". */
    public static float get(Context context, int slot, int field) {
        return prefs(context).getFloat(key(field, slot), 0f);
    }

    public static void set(Context context, int slot, int field, float nits) {
        prefs(context).edit().putFloat(key(field, slot), nits).apply();
    }

    public static void clear(Context context, int slot) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove(key(FIELD_PEAK, slot));
        editor.remove(key(FIELD_GLOBAL, slot));
        editor.remove(key(FIELD_BLACK, slot));
        editor.remove(key(FIELD_STABILITY, slot));
        editor.apply();
    }


    /**
     * A profile resolved from the user's choice: either the display's own report, or a slot
     * with whatever fields were measured. Missing fields fall back to the report, because a
     * slot that only has a peak measured should still contribute that peak.
     */
    public static final class Profile {
        public final String label;
        public final float peakNits;
        public final float blackNits;
        public final float globalNits;
        public final boolean fromSlot;

        Profile(String label, float peakNits, float blackNits, float globalNits, boolean fromSlot) {
            this.label = label;
            this.peakNits = peakNits;
            this.blackNits = blackNits;
            this.globalNits = globalNits;
            this.fromSlot = fromSlot;
        }

        /** The three values as a session report, or null when they cannot all be true. */
        public HdrBrightness toCapabilities(HdrBrightness reported) {
            float peak = peakNits > 0 ? peakNits : (reported != null ? reported.maxNits : 0);
            float black = blackNits > 0 ? blackNits : (reported != null ? reported.minNits : 0);
            float global = globalNits > 0 ? globalNits
                    : (reported != null ? reported.maxFullFrameNits : 0);
            if (peak <= 0 || global <= 0) {
                return reported;
            }
            return HdrBrightness.create(peak, black, global);
        }
    }

    public static Profile resolve(Context context, String source) {
        if (source == null || !source.startsWith("slot")) {
            return new Profile("system", 0, 0, 0, false);
        }
        int slot;
        try {
            slot = Integer.parseInt(source.substring(4));
        }
        catch (NumberFormatException e) {
            return new Profile("system", 0, 0, 0, false);
        }
        if (slot < 0 || slot >= SLOT_COUNT) {
            return new Profile("system", 0, 0, 0, false);
        }
        return new Profile("slot" + (slot + 1),
                get(context, slot, FIELD_PEAK),
                get(context, slot, FIELD_BLACK),
                get(context, slot, FIELD_GLOBAL),
                true);
    }

    /** One line per slot, for the dialog in Settings. */
    public static String describe(Context context, HdrDisplayCapabilities caps) {
        StringBuilder sb = new StringBuilder();
        // Same order Android reports it in: peak, black, full frame.
        sb.append(context.getString(R.string.hdr_profiles_system_line,
                caps.brightness == null ? "-" : round(caps.brightness.maxNits),
                caps.brightness == null ? "-" : decimals(caps.brightness.minNits),
                caps.brightness == null ? "-" : round(caps.brightness.maxFullFrameNits)));
        sb.append("\n\n");
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            sb.append(context.getString(R.string.hdr_profiles_slot_line, slot + 1,
                    value(get(context, slot, FIELD_PEAK), true),
                    value(get(context, slot, FIELD_BLACK), false),
                    value(get(context, slot, FIELD_GLOBAL), true),
                    duration(context, get(context, slot, FIELD_STABILITY))));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String duration(Context context, float seconds) {
        if (seconds <= 0f) {
            return "-";
        }
        int total = Math.round(seconds);
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private static String value(float nits, boolean integer) {
        if (nits <= 0f) {
            return "-";
        }
        return integer ? String.valueOf(Math.round(nits)) : decimals(nits);
    }

    private static String round(float nits) {
        return String.valueOf(Math.round(nits));
    }

    private static String decimals(float nits) {
        return String.format(Locale.US, nits < 1f ? "%.3f" : "%.0f", nits);
    }
}
