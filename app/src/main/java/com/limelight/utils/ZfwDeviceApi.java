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


    public static final String DEFAULT_API_URL = "http://100.123.136.82:8791";


    private static final long TIMEOUT_SECONDS = 8;

    private ZfwDeviceApi() {
    }

    /** One device as the service reports it. */
    public static final class Device {
        public final String name;
        public final String ip;
        public final String segment;
        public final String type;
        public final boolean online;
        public final boolean looksLikeComputer;

        Device(String name, String ip, String segment, String type, boolean online) {
            this.name = name;
            this.ip = ip;
            this.segment = segment;
            this.type = type;
            this.online = online;
            this.looksLikeComputer = looksLikeComputer(type, name);
        }
    }

    /**
     * Whether a device is plausibly something that can host a stream.
     *
     * The portal only knows what the OUI database says, so this is a heuristic: desktops,
     * laptops and machines whose type is unknown are offered; phones, tablets and consoles are
     * not, because they cannot run Sunshine. The full list stays reachable in the picker, since
     * an unknown device may well be the one that was actually wanted.
     */
    private static boolean looksLikeComputer(String type, String name) {
        if (type == null) {
            return false;
        }
        String t = type;
        if (t.contains("手机") || t.contains("平板") || t.contains("游戏机")) {
            return false;
        }
        if (t.contains("台式") || t.contains("笔记本") || t.contains("主机")) {
            return true;
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("windows") || n.contains("mac") || n.contains("linux")
                || n.contains("pc") || n.equals("未知");
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

    /** Result of a device listing. */
    public static final class DeviceList {
        public final java.util.List<Device> devices;
        public final String error;

        DeviceList(java.util.List<Device> devices, String error) {
            this.devices = devices;
            this.error = error;
        }

        public boolean isSuccess() {
            return devices != null;
        }
    }

    /**
     * Ask the device service which devices the account has right now. Blocking.
     */
    public static DeviceList listDevices(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl = trim(prefs.getString(PREF_API_URL, DEFAULT_API_URL));
        String token = trim(prefs.getString(PREF_API_TOKEN, ""));
        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_API_URL;
        }
        if (token.isEmpty()) {
            return new DeviceList(null, "no token");
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
                return new DeviceList(null, "empty response");
            }
            String body = response.body().string();
            if (!response.isSuccessful()) {
                return new DeviceList(null, "HTTP " + response.code());
            }
            return parse(body);
        }
        catch (IOException e) {
            return new DeviceList(null, e.getClass().getSimpleName());
        }
        catch (JSONException e) {
            return new DeviceList(null, "bad json");
        }
    }

    private static DeviceList parse(String body) throws JSONException {
        JSONArray array = new JSONObject(body).optJSONArray("devices");
        if (array == null) {
            return new DeviceList(null, "no devices");
        }
        java.util.List<Device> devices = new java.util.ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject device = array.getJSONObject(i);
            String ip = device.optString("ip", "");
            if (ip.isEmpty()) {
                continue;
            }
            devices.add(new Device(device.optString("name", ""), ip,
                    device.optString("segment", ""), device.optString("type", ""),
                    device.optBoolean("online", false)));
        }
        devices.sort((a, b) -> {
            if (a.looksLikeComputer != b.looksLikeComputer) {
                return a.looksLikeComputer ? -1 : 1;
            }
            if (a.online != b.online) {
                return a.online ? -1 : 1;
            }
            return a.name.compareTo(b.name);
        });
        return new DeviceList(devices, null);
    }

    /** The host part of the configured service URL: the Mac's own address on the tailnet. */
    public static String serviceHost(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String baseUrl = trim(prefs.getString(PREF_API_URL, DEFAULT_API_URL));
        if (baseUrl.isEmpty()) {
            baseUrl = DEFAULT_API_URL;
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            return uri.getHost() == null ? "" : uri.getHost();
        }
        catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s·\\-]", "").toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
