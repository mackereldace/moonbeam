package com.limelight.telemetry;

import android.content.Context;

import com.limelight.binding.video.PerfHudData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Session performance telemetry, ported from Moonlight V+.
 *
 * Design, unchanged from V+:
 *  - sampling happens on the client because only the client sees real render intervals;
 *    one sample per second;
 *  - the session never blocks on the upload, and a failed upload is harmless because a
 *    local JSON copy is always written to the app's external files dir;
 *  - the upload target is derived from the host we are already connected to (port 8792),
 *    so there is nothing for the user to configure.
 */
public final class SessionTelemetry {
    private static final int UPLOAD_PORT = 8792;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    private static final int MAX_SAMPLES = 7200; // 2 hours at 1 Hz
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    private static final SessionTelemetry INSTANCE = new SessionTelemetry();

    private String hostAddress;
    private long startedAt;
    private long lastSampleAt;
    private final List<JSONObject> samples = new ArrayList<>();
    private JSONObject meta = new JSONObject();
    private boolean active;

    private SessionTelemetry() {
    }

    public static SessionTelemetry get() {
        return INSTANCE;
    }

    /** Begin collecting for a new session. */
    public synchronized void start(String address, String pcName) {
        if (active) {
            return;
        }
        active = true;
        hostAddress = address;
        startedAt = System.currentTimeMillis();
        lastSampleAt = 0L;
        samples.clear();
        meta = new JSONObject();
        try {
            meta.put("pc", pcName == null ? "" : pcName);
            meta.put("address", address == null ? "" : address);
        } catch (JSONException ignored) {
        }
    }

    /** Attach session metadata (resolution, codec, HDR, ...). May be called repeatedly. */
    public synchronized void putMeta(String key, Object value) {
        if (!active) {
            return;
        }
        try {
            meta.put(key, value == null ? "" : value);
        } catch (JSONException ignored) {
        }
    }

    /** Record one sample, throttled to 1 Hz. */
    public synchronized void record(PerfHudData data) {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastSampleAt != 0L && now - lastSampleAt < SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSampleAt = now;
        if (samples.size() >= MAX_SAMPLES) {
            return;
        }

        try {
            samples.add(new JSONObject()
                    .put("t", (now - startedAt) / 1000.0)
                    .put("fps", round(data.renderedFps, 1))
                    .put("ft_med", round(data.frameTimeMedianMs, 2))
                    .put("ft_p99", round(data.frameTimeP99Ms, 2))
                    .put("jitter", round(data.frameTimeP99Ms - data.frameTimeMedianMs, 2))
                    .put("low1", round(data.onePercentLowFps, 1))
                    .put("low5", round(data.fivePercentLowFps, 1))
                    .put("below", round(data.timeBelowTargetFraction, 3))
                    .put("hz", data.displayRefreshHz)
                    .put("net_ms", data.networkLatencyMs)
                    .put("net_dev", data.hostLatencyMs)
                    .put("decode_ms", round(data.decodeTimeMs, 2))
                    .put("lost", round(data.packetLossPercent / 100.0, 3)));
        } catch (JSONException ignored) {
        }
    }

    /** Finish the session: write locally, then upload in the background. */
    public void finish(final Context context) {
        final String payload;
        final String url;
        synchronized (this) {
            if (!active) {
                return;
            }
            active = false;
            url = (hostAddress != null && !hostAddress.isEmpty())
                    ? "http://" + hostAddress + ":" + UPLOAD_PORT + "/upload" : null;
            String built;
            try {
                built = new JSONObject()
                        .put("schema", 1)
                        .put("started_at", startedAt)
                        .put("duration_s", (System.currentTimeMillis() - startedAt) / 1000.0)
                        .put("meta", meta)
                        .put("samples", new JSONArray(samples))
                        .toString();
            } catch (JSONException e) {
                built = "{}";
            }
            payload = built;
        }

        // Always keep a local copy; an upload failure must not lose the data
        final File local = writeLocal(context, payload);
        if (url == null) {
            return;
        }

        new Thread(() -> {
            try {
                RequestBody body = RequestBody.create(payload, JSON);
                Request request = new Request.Builder().url(url).post(body).build();
                try (Response response = http.newCall(request).execute()) {
                    android.util.Log.i("SessionTelemetry", "upload "
                            + (response.isSuccessful() ? "ok" : "failed " + response.code())
                            + (local != null ? " (local: " + local.getName() + ")" : ""));
                }
            } catch (Exception e) {
                android.util.Log.w("SessionTelemetry", "upload failed: " + e.getMessage()
                        + "; local=" + (local != null ? local.getName() : "none"));
            }
        }, "session-telemetry-upload").start();
    }

    private static File writeLocal(Context context, String payload) {
        if (context == null) {
            return null;
        }
        File dir = context.getExternalFilesDir("telemetry");
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "session_" + stamp + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
