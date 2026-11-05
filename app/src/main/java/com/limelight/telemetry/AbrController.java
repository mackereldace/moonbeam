package com.limelight.telemetry;

import com.limelight.LimeLog;
import com.limelight.binding.video.PerfHudData;

/**
 * Adaptive bitrate controller.
 *
 * Why it exists: the stream bitrate is negotiated once at session start and never changes.
 * On a shared Wi-Fi network that produces a sawtooth — congestion causes loss, the client
 * falls behind, catches up when the air clears. Measured on this setup: 60 Mbps produced
 * 45% loss bursts and 61 fps dips, 40 Mbps produced steady 120 Hz.
 *
 * Policy (deliberately asymmetric — drop fast, recover slowly):
 *  - loss above 5%: cut to 70% immediately, no waiting;
 *  - loss above 2% twice in a row: cut to 85%;
 *  - RTT above 30 ms and rising: cut to 90% (buffer bloat shows up before loss does);
 *  - ten clean seconds: raise by 5%, never above the user's configured value.
 *
 * Deadband and cooldown keep it from oscillating. The ceiling is the user's own setting,
 * so the controller can never ask for more than the user chose (V+ allowed 150 Mbps against
 * a 60 Mbps host cap, which made every upward step a no-op).
 */
public class AbrController {

    private static final int MIN_KBPS = 8000;
    private static final float DEADBAND = 0.03f;
    private static final long MIN_STEP_INTERVAL_MS = 1000;
    private static final int CLEAN_SECONDS_TO_PROBE = 10;

    /** Where the controller sends its decisions; keeps it independent of the HTTP layer. */
    public interface BitrateSink {
        boolean setBitrate(int kbps);
    }

    private final BitrateSink sink;
    private final int maxKbps;

    private int currentKbps;
    private int lastSentKbps;
    private long lastStepAt;
    private int lossStreak;
    private int cleanSeconds;
    private int lastRttMs = -1;
    private boolean enabled = true;

    public AbrController(BitrateSink sink, int startKbps) {
        this.sink = sink;
        this.maxKbps = Math.max(startKbps, MIN_KBPS);
        this.currentKbps = startKbps;
        this.lastSentKbps = startKbps;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            LimeLog.info("[ABR] disabled");
        } else {
            LimeLog.info("[ABR] enabled, start " + currentKbps + "kbps, ceiling " + maxKbps + "kbps");
        }
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    /** Feed one sample; call at the renderer's 1 Hz cadence. */
    public void onSample(PerfHudData data) {
        if (!enabled || data == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStepAt < MIN_STEP_INTERVAL_MS) {
            return;
        }

        float loss = data.packetLossPercent;
        int rtt = data.networkLatencyMs;
        int target = currentKbps;
        String reason = null;

        if (loss > 5f) {
            target = (int) (currentKbps * 0.70f);
            reason = String.format(java.util.Locale.US, "loss %.1f%% emergency", loss);
            lossStreak++;
            cleanSeconds = 0;
        } else if (loss > 2f) {
            lossStreak++;
            cleanSeconds = 0;
            if (lossStreak >= 2) {
                target = (int) (currentKbps * 0.85f);
                reason = String.format(java.util.Locale.US, "loss %.1f%% sustained", loss);
                lossStreak = 0;
            }
        } else if (rtt > 30 && lastRttMs > 0 && rtt > lastRttMs + 5) {
            target = (int) (currentKbps * 0.90f);
            reason = "rtt " + lastRttMs + "->" + rtt + "ms";
            cleanSeconds = 0;
        } else {
            lossStreak = 0;
            cleanSeconds++;
            if (cleanSeconds >= CLEAN_SECONDS_TO_PROBE && currentKbps < maxKbps) {
                target = (int) (currentKbps * 1.05f);
                reason = "clean " + cleanSeconds + "s";
                cleanSeconds = 0;
            }
        }

        lastRttMs = rtt;

        target = Math.max(MIN_KBPS, Math.min(maxKbps, target));

        // Deadband: ignore changes too small to matter, they only add churn
        if (reason == null || Math.abs(target - currentKbps) < currentKbps * DEADBAND) {
            return;
        }

        if (sink != null && sink.setBitrate(target)) {
            LimeLog.info("[ABR] " + currentKbps + " -> " + target + " kbps (" + reason + ")");
            currentKbps = target;
            lastSentKbps = target;
            lastStepAt = now;
        }
    }
}
