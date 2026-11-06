package com.limelight;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.preferences.PreferenceConfiguration;

import java.io.IOException;
import java.util.Locale;

/**
 * On-device HDR test: play a brightness ladder and let the user say where two steps stop
 * being distinguishable.
 *
 * Why it exists: every other measurement we have runs through the host, so "the ladder
 * merges" cannot be attributed - it could be the panel, the system's tone mapping, or our
 * streaming chain. This page removes the host from the loop and answers one question only:
 * on this screen, at this brightness, which step is the last one that still looks different?
 *
 * It is a perceptual tool, not a meter. It cannot report absolute luminance; it can say where
 * the useful ceiling is, which is exactly what the manual peak setting needs.
 *
 * The clips are 10-bit HEVC, BT.2020/PQ, limited range, lossless - decoded by the platform and
 * presented through a SurfaceView, which carries its own HDR headroom independent of the
 * window. The info line above the buttons reports what the system is actually doing, so a run
 * that never reached HDR is visible instead of silently producing a wrong answer.
 */
public class HdrTestActivity extends Activity implements SurfaceHolder.Callback {

    /** Ladder steps, in the same order as the clips: 100/203/400/700/1000/1600/2400/3200. */
    private static final int[] STEPS_NITS = {100, 203, 400, 700, 1000, 1600, 2400, 3200};

    private static final String[] CLIPS = {
            "hdr/ladder-A.mp4",  // eight adjacent bars, full height
            "hdr/ladder-B.mp4",  // the same bars at 40% height
            "hdr/ladder-C.mp4",  // one bar at a time
    };

    private SurfaceView surfaceView;
    private TextView infoView;
    private TextView hintView;
    private LinearLayout stepRow;
    private Button[] stepButtons = new Button[STEPS_NITS.length];

    private MediaPlayer player;
    private String pendingClip = CLIPS[0];
    private boolean surfaceReady;
    private int selectedIndex = STEPS_NITS.length - 1;  // default: everything distinguishable

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            updateInfo();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the panel awake and at full brightness: a dimmed screen makes every step merge.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);

        // HDR on the window itself (API 26+). The SurfaceView below has its own headroom, so
        // this is belt and braces rather than the mechanism that makes PQ reach the panel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
        }

        setContentView(R.layout.activity_hdr_test);

        surfaceView = findViewById(R.id.hdrSurface);
        infoView = findViewById(R.id.hdrInfo);
        hintView = findViewById(R.id.hdrHint);
        stepRow = findViewById(R.id.hdrSteps);
        surfaceView.getHolder().addCallback(this);

        buildStepButtons();
        wireLayoutButtons();

        findViewById(R.id.hdrSave).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.hdrClose).setOnClickListener(v -> finish());

        hintView.setText(R.string.hdr_test_hint);
    }

    private void buildStepButtons() {
        for (int i = 0; i < STEPS_NITS.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(String.format(Locale.US, "%d", STEPS_NITS[i]));
            button.setAllCaps(false);
            button.setTag(index);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            button.setLayoutParams(params);
            button.setTextSize(11f);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> selectStep(index));
            stepRow.addView(button);
            stepButtons[i] = button;
        }
        selectStep(selectedIndex);
    }

    private void selectStep(int index) {
        selectedIndex = index;
        for (int i = 0; i < stepButtons.length; i++) {
            stepButtons[i].setTextColor(i == index ? Color.BLACK : Color.WHITE);
            stepButtons[i].setBackgroundColor(i == index ? Color.WHITE : Color.TRANSPARENT);
        }
    }

    private void wireLayoutButtons() {
        int[] ids = {R.id.hdrLayoutA, R.id.hdrLayoutB, R.id.hdrLayoutC};
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            findViewById(ids[i]).setOnClickListener(v -> {
                pendingClip = CLIPS[index];
                if (surfaceReady) {
                    startPlayback();
                }
            });
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startPlayback();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopPlayback();
    }

    private void startPlayback() {
        stopPlayback();
        try {
            AssetFileDescriptor afd = getAssets().openFd(pendingClip);
            player = new MediaPlayer();
            player.setSurface(surfaceView.getHolder().getSurface());
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            player.setLooping(true);
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, getString(R.string.hdr_test_play_error,
                        what + "/" + extra), Toast.LENGTH_LONG).show();
                return true;
            });
            player.prepareAsync();
            player.setOnPreparedListener(mp -> {
                mp.start();
                handler.post(sampler);
            });
            afd.close();
        }
        catch (IOException e) {
            Toast.makeText(this, getString(R.string.hdr_test_play_error,
                    String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback() {
        handler.removeCallbacks(sampler);
        if (player != null) {
            try {
                player.stop();
            }
            catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
    }

    /** What the system is doing right now: this is what makes a run trustworthy or not. */
    private void updateInfo() {
        Display display = getWindowManager().getDefaultDisplay();
        StringBuilder sb = new StringBuilder();

        boolean hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display.isHdr();
        sb.append(getString(R.string.hdr_test_info_hdr, getString(hdr ? R.string.common_yes : R.string.common_no)));

        // Display.getBrightnessInfo() and android.hardware.display.BrightnessInfo are @hide, so
        // they are read reflectively and simply skipped when the platform does not expose them.
        // Losing this line does not invalidate a run - isHdr and the ratio below still tell us
        // whether the panel is in HDR - it just removes the evidence of *why* it is capped.
        try {
            Object info = Display.class.getMethod("getBrightnessInfo").invoke(display);
            if (info != null) {
                Class<?> type = info.getClass();
                float white = type.getField("brightness").getFloat(info);
                float max = type.getField("brightnessMaximum").getFloat(info);
                int hbm = type.getField("highBrightnessMode").getInt(info);
                sb.append("  ·  ").append(getString(R.string.hdr_test_info_brightness,
                        fmt(white), fmt(max)));
                sb.append("  ·  ").append(getString(R.string.hdr_test_info_hbm, hbmName(hbm)));
            }
        }
        catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (display.isHdrSdrRatioAvailable()) {
                    sb.append("  ·  ").append(getString(R.string.hdr_test_info_ratio,
                            String.format(Locale.US, "%.2f", display.getHdrSdrRatio())));
                }
            }
            catch (RuntimeException ignored) {
            }
        }

        infoView.setText(sb.toString());
        if (!hdr) {
            hintView.setText(R.string.hdr_test_not_hdr);
            hintView.setTextColor(Color.parseColor("#FF6E6E"));
        }
        else {
            hintView.setText(R.string.hdr_test_hint);
            hintView.setTextColor(Color.WHITE);
        }
    }

    /** High brightness mode: 0 off, 1 sunlight, 2 HDR. */
    private String hbmName(int mode) {
        switch (mode) {
            case 1:
                return getString(R.string.hdr_test_hbm_sunlight);
            case 2:
                return getString(R.string.hdr_test_hbm_hdr);
            default:
                return getString(R.string.common_no);
        }
    }

    private static String fmt(float value) {
        return String.format(Locale.US, "%.0f", value);
    }

    private void saveAndFinish() {
        int peak = STEPS_NITS[selectedIndex];
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putBoolean(PreferenceConfiguration.HDR_BRIGHTNESS_OVERRIDE_PREF_STRING, true)
                .putInt(PreferenceConfiguration.HDR_PEAK_BRIGHTNESS_NITS_PREF_STRING, peak)
                .apply();
        Toast.makeText(this, getString(R.string.hdr_test_saved, peak), Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }
}
