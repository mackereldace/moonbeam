package com.limelight;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.preferences.PreferenceConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * On-device HDR test: play a brightness ladder and let the user say where two steps stop
 * being distinguishable.
 *
 * Why it exists: every other measurement we have runs through the host, so "the ladder
 * merges" cannot be attributed - it could be the panel, the system tone mapping, or our
 * streaming chain. This page removes the host from the loop and answers one question only:
 * on this screen, at this brightness, which step is the last one that still looks different?
 *
 * Playback goes through MediaCodec directly rather than MediaPlayer for one reason: the
 * streaming decoder asks for full-range output (KEY_COLOR_RANGE, set from the same
 * preference the stream uses) and MediaPlayer offers no way to do that. The clips are
 * therefore stored full-range too, which is the combination the whole HDR chain runs on -
 * client full-range preference, host full-range encoder, full-range capture. Playing them
 * any other way would measure a pipeline that never exists in practice.
 *
 * It is a perceptual tool, not a meter. It cannot report absolute luminance; it can say where
 * the useful ceiling is, which is exactly what the manual peak setting needs.
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
    private final Button[] stepButtons = new Button[STEPS_NITS.length];

    private volatile boolean running;
    private Thread decodeThread;
    private volatile String pendingClip = CLIPS[0];
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
        final String clip = pendingClip;
        running = true;
        decodeThread = new Thread(() -> decodeLoop(clip), "hdr-test-decode");
        decodeThread.start();
        handler.post(sampler);
    }

    private void stopPlayback() {
        running = false;
        Thread thread = decodeThread;
        decodeThread = null;
        if (thread != null) {
            try {
                thread.join(1500);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        handler.removeCallbacks(sampler);
    }

    /**
     * Decode the ladder straight onto the SurfaceView, looping.
     *
     * Pacing matters for the single-bar clip: it holds each step for five seconds, and without
     * waiting on presentation timestamps the decoder would run it at whatever rate it can.
     */
    private void decodeLoop(String asset) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            AssetFileDescriptor afd = getAssets().openFd(asset);
            extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();

            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (format == null) {
                postError("no video track in " + asset);
                return;
            }
            extractor.selectTrack(track);

            // Same request the streaming decoder makes, from the same preference.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            }

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, surfaceView.getHolder().getSurface(), null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long firstPts = -1;
            long firstWall = 0;
            long lastPts = 0;

            while (running) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                        else {
                            lastPts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, lastPts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                    if (firstPts < 0) {
                        firstPts = info.presentationTimeUs;
                        firstWall = SystemClock.elapsedRealtime();
                    }
                    else if (info.presentationTimeUs > firstPts) {
                        long target = firstWall
                                + (info.presentationTimeUs - firstPts) / 1000;
                        long wait = target - SystemClock.elapsedRealtime();
                        if (wait > 0 && wait < 1000) {
                            SystemClock.sleep(wait);
                        }
                    }

                    codec.releaseOutputBuffer(outIndex, true);

                    if (eos) {
                        // Rewind and start over: the clip is a loop of still frames.
                        codec.flush();
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                        firstPts = -1;
                    }
                }
            }
        }
        catch (IOException | RuntimeException e) {
            postError(String.valueOf(e.getMessage()));
        }
        finally {
            if (codec != null) {
                try {
                    codec.stop();
                }
                catch (RuntimeException ignored) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private void postError(String message) {
        handler.post(() -> Toast.makeText(this,
                getString(R.string.hdr_test_play_error, message), Toast.LENGTH_LONG).show());
    }

    /** What the system is doing right now: this is what makes a run trustworthy or not. */
    private void updateInfo() {
        Display display = getWindowManager().getDefaultDisplay();
        StringBuilder sb = new StringBuilder();

        boolean hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display.isHdr();
        sb.append(getString(R.string.hdr_test_info_hdr,
                getString(hdr ? R.string.common_yes : R.string.common_no)));

        // Display.getBrightnessInfo() and android.hardware.display.BrightnessInfo are @hide, so
        // they are read reflectively and simply skipped when the platform does not expose them.
        // Losing this line does not invalidate a run - isHdr and the ratio below still tell us
        // whether the panel is in HDR - it only removes the evidence of *why* it is capped.
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

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceReady) {
            startPlayback();
        }
    }
}
