package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Talks to the Sunshine web API that runs next to the stream.
 *
 * The phone cannot reach BetterDisplay or displayplacer - both live on the host - and Moonlight's
 * control protocol has no resize message, so the resolution switch is a small token-guarded HTTP
 * call. The web UI uses a self-signed certificate that changes whenever Sunshine is rebuilt, so
 * this client trusts it rather than trying to pin something that will not hold.
 */
public final class SunshineClient {

    public static final String PREF_TOKEN = "text_sunshine_token";
    private static final int WEB_PORT = 47990;
    private static final long TIMEOUT_SECONDS = 6;

    private SunshineClient() {
    }

    public static String token(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String value = prefs.getString(PREF_TOKEN, "");
        return value == null ? "" : value.trim();
    }

    /** Requests a display mode change; returns null on success or a message describing failure. */
    public static String requestDisplayMode(Context context, String host, int width, int height) {
        String token = token(context);
        if (token.isEmpty()) {
            return "no token";
        }
        if (host == null || host.isEmpty()) {
            return "no host";
        }

        String url = "https://" + host + ":" + WEB_PORT + "/api/display-mode?w=" + width
                + "&h=" + height + "&token=" + token;

        try {
            OkHttpClient client = trustAllClient();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return null;
                }
                return "HTTP " + response.code();
            }
        }
        catch (IOException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static OkHttpClient trustAllClient() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(context.getSocketFactory(), (X509TrustManager) trustAll[0])
                    .hostnameVerifier((name, session) -> true)
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build();
        }
        catch (Exception e) {
            return new OkHttpClient();
        }
    }
}
