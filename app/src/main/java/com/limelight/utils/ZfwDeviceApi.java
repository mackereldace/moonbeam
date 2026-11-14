package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Looks up the streaming host's current IP on the campus network.
 *
 * A machine on the campus network gets a new address whenever its lease turns over, so a typed-in
 * IP goes stale without anything looking wrong. The zfw device service on the Mac knows the
 * address of every device under the account - it reads it from the portal - so the client can ask
 * for it instead of the user re-typing an address after every reconnect.
 */
public final class ZfwDeviceApi {

    public static final String PREF_API_URL = "text_zfw_api_url";
    public static final String PREF_API_TOKEN = "text_zfw_api_token";
    public static final String PREF_HOST_NAME = "text_zfw_host_name";

    public static final String DEFAULT_API_URL = "http://100.123.136.82:8791";
    public static final String DEFAULT_HOST_NAME = "Mac mini";

    private static final long TIMEOUT_SECONDS = 8;

    private ZfwDeviceApi() {
    }

    /** What the lookup found, or why it did not. */
    public static final class Result {
        public final String ip;
        public final String name;
        public final String error;

        private Result(String ip, String name, String error) {
            this.ip = ip;
            this.name = name;
            this.error = error;
        }

        public boolean isSuccess() {
            return ip != null;
        }
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String token = prefs.getString(PREF_API_TOKEN, "");
        return token != null && !token.trim().isEmpty();
    }

    /**
     * Ask the device service for the host's address. Blocking; call it off the main thread.
     */
    public static Result resolveHost(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl = trim(prefs.getString(PREF_API_URL, DEFAULT_API_URL));
        String token = trim(prefs.getString(PREF_API_TOKEN, ""));
        String hostName = trim(prefs.getString(PREF_HOST_NAME, DEFAULT_HOST_NAME));

        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_API_URL;
        }
        if (token.isEmpty()) {
            return new Result(null, null, "no token");
        }
        if (hostName.isEmpty()) {
            hostName = DEFAULT_HOST_NAME;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder()
                .url(baseUrl.replaceAll("/+$", "") + "/api/devices")
                .header("X-Token", token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() == null) {
                return new Result(null, null, "empty response");
            }
            String body = response.body().string();
            if (!response.isSuccessful()) {
                return new Result(null, null, "HTTP " + response.code());
            }
            return parse(body, hostName);
        }
        catch (IOException e) {
            return new Result(null, null, e.getClass().getSimpleName());
        }
        catch (JSONException e) {
            return new Result(null, null, "bad json");
        }
    }

    /** Picks the online device whose name matches, falling back to the last known address. */
    private static Result parse(String body, String hostName) throws JSONException {
        JSONArray devices = new JSONObject(body).optJSONArray("devices");
        if (devices == null || devices.length() == 0) {
            return new Result(null, null, "no devices");
        }

        String wanted = normalize(hostName);
        JSONObject offlineMatch = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.getJSONObject(i);
            String name = normalize(device.optString("name", ""));
            String ip = device.optString("ip", "");
            if (ip.isEmpty() || wanted.isEmpty() || !name.contains(wanted)) {
                continue;
            }
            if (device.optBoolean("online", false)) {
                return new Result(ip, device.optString("name"), null);
            }
            if (offlineMatch == null) {
                offlineMatch = device;
            }
        }

        if (offlineMatch != null) {
            // Offline still gives the last address, which beats nothing when the point is to
            // discover that the machine is simply not reachable right now.
            return new Result(offlineMatch.optString("ip"), offlineMatch.optString("name"), null);
        }
        return new Result(null, null, "not found");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s·\\-]", "").toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
