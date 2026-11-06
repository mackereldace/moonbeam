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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.preferences.PreferenceConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device HDR test.
 *
 * Ladder mode: eight fixed steps, three layouts, for a rough look at whether the screen can
 * separate highlights at all.
 *
 * Pair mode: a log bisection. The two panes show the current bounds - left = lower, right =
 * upper - starting at 100 and 10000 nit. "Tell them apart" means the lower bound is still
 * below the ceiling, so it moves up; otherwise the upper bound comes down. Six answers put the
 * ceiling between two neighbouring steps of a 48-step log ladder, and nothing is capped by the
 * peak Android reports.
 *
 * Decoding is MediaCodec with KEY_COLOR_RANGE full, matching the streaming decoder and the
 * full-range chain this project runs on. The level clip is all-intra at 1 fps so a seek lands
 * on the exact frame and one decode is enough.
 */
public class HdrTestActivity extends Activity implements SurfaceHolder.Callback {

    private static final int[] STEPS_NITS = {100, 203, 400, 700, 1000, 1600, 2400, 3200};

    private static final String[] LADDER_CLIPS = {
            "hdr/ladder-A.mp4",  // eight adjacent bars, full height
            "hdr/ladder-B.mp4",  // the same bars at 40% height
            "hdr/ladder-C.mp4",  // one bar at a time
    };
    private static final String LEVEL_CLIP = "hdr/levels.mp4";
    private static final int LEVEL_COUNT = 48;
    private static final double LEVEL_MIN_NITS = 100.0;
    private static final double LEVEL_MAX_NITS = 10000.0;

    /** One SurfaceView plus the decoder that feeds it. Kept alive across comparisons. */
    private final class Pane {
        final SurfaceView view;
        MediaExtractor extractor;
        MediaCodec codec;
        long segmentUs = 1_000_000L;

        Pane(SurfaceView view) {
            this.view = view;
        }

        boolean hasSurface() {
            return view.getHolder().getSurface() != null && view.getHolder().getSurface().isValid();
        }

        void close() {
            if (codec != null) {
                try {
                    codec.stop();
                }
                catch (RuntimeException ignored) {
                }
                codec.release();
                codec = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private Pane paneA;
    private Pane paneB;

    private TextView infoView;
    private TextView hintView;
    private TextView pairStatus;
    private LinearLayout ladderRow;
    private LinearLayout stepRow;
    private LinearLayout pairRow;
    private Button pairModeButton;
    private Button[] stepButtons;

    private volatile boolean running;
    private Thread ladderThread;
    private volatile String pendingClip = LADDER_CLIPS[0];
    private int selectedIndex = STEPS_NITS.length - 1;

    private boolean pairMode;
    private int levelLo, levelHi, pairSteps;
    private double pairPeakNits;

    /** One renderer thread: a Surface takes one codec at a time, and answers come in bursts. */
    private final ExecutorService renderer = Executors.newSingleThreadExecutor();

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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().setColorMode(ActivityInfo.COLOR_MODE_HDR);
        }

        setContentView(R.layout.activity_hdr_test);

        paneA = new Pane((SurfaceView) findViewById(R.id.hdrSurface));
        paneB = new Pane((SurfaceView) findViewById(R.id.hdrSurfaceB));
        infoView = findViewById(R.id.hdrInfo);
        hintView = findViewById(R.id.hdrHint);
        pairStatus = findViewById(R.id.hdrPairStatus);
        ladderRow = findViewById(R.id.hdrLadderRow);
        stepRow = findViewById(R.id.hdrSteps);
        pairRow = findViewById(R.id.hdrPairRow);
        pairModeButton = findViewById(R.id.hdrPairMode);

        paneA.view.getHolder().addCallback(this);
        paneB.view.getHolder().addCallback(this);

        buildStepButtons();
        wireLayoutButtons();
        pairModeButton.setOnClickListener(v -> togglePairMode());
        findViewById(R.id.hdrPairDiff).setOnClickListener(v -> answerPair(true));
        findViewById(R.id.hdrPairSame).setOnClickListener(v -> answerPair(false));
        findViewById(R.id.hdrSave).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.hdrClose).setOnClickListener(v -> finish());

        hintView.setText(R.string.hdr_test_hint);
    }

    private void buildStepButtons() {
        stepButtons = new Button[STEPS_NITS.length];
        for (int i = 0; i < STEPS_NITS.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(String.format(Locale.US, "%d", STEPS_NITS[i]));
            button.setAllCaps(false);
            button.setTextSize(11f);
            button.setTextColor(Color.WHITE);
            button.setPadding(0, 0, 0, 0);
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
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
                pendingClip = LADDER_CLIPS[index];
                if (!pairMode) {
                    startLadder();
                }
            });
        }
    }

    // ---- pair mode: log bisection -----------------------------------------

    private void togglePairMode() {
        pairMode = !pairMode;
        ladderRow.setVisibility(pairMode ? View.GONE : View.VISIBLE);
        pairRow.setVisibility(pairMode ? View.VISIBLE : View.GONE);
        pairModeButton.setText(pairMode ? R.string.hdr_test_mode_ladder : R.string.hdr_test_mode_pair);

        if (pairMode) {
            stopLadder();
            paneA.close();
            paneB.close();
            paneB.view.setVisibility(View.VISIBLE);

            levelLo = 0;
            levelHi = LEVEL_COUNT - 1;
            pairSteps = 0;
            pairPeakNits = 0;
            showBounds();
        }
        else {
            paneB.view.setVisibility(View.GONE);
            paneA.close();
            paneB.close();
            pairStatus.setText("");
            startLadder();
        }
    }

    /** Draw the current bounds; converge when only neighbours are left. */
    private void showBounds() {
        if (levelHi - levelLo <= 1) {
            finishSearch();
            return;
        }
        pairSteps++;
        int loNits = (int) Math.round(levelNits(levelLo));
        int hiNits = (int) Math.round(levelNits(levelHi));
        pairStatus.setText(getString(R.string.hdr_test_pair_progress, pairSteps, loNits, hiNits));
        renderLevel(paneA, levelLo);
        renderLevel(paneB, levelHi);
    }

    private void answerPair(boolean different) {
        if (!pairMode || levelHi - levelLo <= 1) {
            return;
        }
        int mid = (levelLo + levelHi) / 2;
        if (different) {
            levelLo = mid;
        }
        else {
            levelHi = mid;
        }
        showBounds();
    }

    private void finishSearch() {
        pairPeakNits = Math.sqrt(levelNits(levelLo) * levelNits(levelHi));
        pairStatus.setText(getString(R.string.hdr_test_pair_result,
                Math.round(pairPeakNits),
                Math.round(levelNits(levelLo)),
                Math.round(levelNits(levelHi))));
    }

    /** Level i's luminance: log-spaced across the clip's range. */
    private static double levelNits(int index) {
        double t = (double) index / (LEVEL_COUNT - 1);
        return LEVEL_MIN_NITS * Math.pow(LEVEL_MAX_NITS / LEVEL_MIN_NITS, t);
    }

    /** Open the level clip on a pane once, then reuse it: configure is the fragile step. */
    private boolean ensurePane(Pane pane) {
        if (pane.codec != null) {
            return true;
        }
        if (!pane.hasSurface()) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            AssetFileDescriptor afd = getAssets().openFd(LEVEL_CLIP);
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
                extractor.release();
                postError("no video track");
                return false;
            }
            extractor.selectTrack(track);

            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);
                if (durationUs > 0) {
                    pane.segmentUs = durationUs / LEVEL_COUNT;
                }
            }
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            }

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, pane.view.getHolder().getSurface(), null, 0);
            codec.start();

            pane.extractor = extractor;
            pane.codec = codec;
            return true;
        }
        catch (IOException | RuntimeException e) {
            extractor.release();
            reportFailure("open", e);
            return false;
        }
    }

    /** Seek to one level and render exactly that frame, on the renderer thread. */
    private void renderLevel(Pane pane, final int index) {
        renderer.execute(() -> renderLevelNow(pane, index));
    }

    private void renderLevelNow(Pane pane, int index) {
        // A SurfaceView that just became visible has no Surface for a frame or two.
        long surfaceDeadline = SystemClock.elapsedRealtime() + 1500;
        while (!pane.hasSurface() && SystemClock.elapsedRealtime() < surfaceDeadline) {
            SystemClock.sleep(50);
        }
        if (!ensurePane(pane)) {
            return;
        }
        MediaCodec codec = pane.codec;
        MediaExtractor extractor = pane.extractor;
        if (codec == null || extractor == null) {
            return;
        }

        long target = Math.min(index * pane.segmentUs,
                Math.max(0, pane.segmentUs * LEVEL_COUNT - 1));
        try {
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = SystemClock.elapsedRealtime() + 2000;
            boolean fed = false;

            while (SystemClock.elapsedRealtime() < deadline) {
                if (!fed) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            return;
                        }
                        codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                        fed = true;
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    boolean reached = info.presentationTimeUs >= target - 1;
                    codec.releaseOutputBuffer(outIndex, reached);
                    if (reached) {
                        return;
                    }
                    fed = false;
                }
            }
            reportFailure("seek to " + index, new IllegalStateException("no frame within 2s"));
        }
        catch (RuntimeException e) {
            pane.close();
            reportFailure("render " + index, e);
        }
    }

    // ---- ladder mode -------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (pairMode) {
            return;  // pair mode draws on demand
        }
        if (holder.getSurface() == paneA.view.getHolder().getSurface()) {
            startLadder();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopLadder();
        paneA.close();
        paneB.close();
    }

    private void startLadder() {
        stopLadder();
        final String clip = pendingClip;
        running = true;
        ladderThread = new Thread(() -> decodeLadder(clip), "hdr-ladder");
        ladderThread.start();
        handler.post(sampler);
    }

    private void stopLadder() {
        running = false;
        Thread thread = ladderThread;
        ladderThread = null;
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

    private void decodeLadder(String asset) {
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
                postError("no video track");
                return;
            }
            extractor.selectTrack(track);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
            }

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, paneA.view.getHolder().getSurface(), null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long firstPts = -1;
            long firstWall = 0;

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
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
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
                        long due = firstWall + (info.presentationTimeUs - firstPts) / 1000;
                        long wait = due - SystemClock.elapsedRealtime();
                        if (wait > 0 && wait < 1000) {
                            SystemClock.sleep(wait);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, true);
                    if (eos) {
                        codec.flush();
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                        firstPts = -1;
                    }
                }
            }
        }
        catch (IOException | RuntimeException e) {
            reportFailure("ladder", e);
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

    // ---- diagnostics -------------------------------------------------------

    private void reportFailure(String stage, Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : error.getStackTrace()) {
            sb.append(element.toString()).append('\n');
            if (sb.length() > 600) {
                break;
            }
        }
        LimeLog.severe("HDR test " + stage + " failed: " + error + "\n" + sb);
        postError(stage + ": " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " " + error.getMessage()));
    }

    private void postError(String message) {
        // Persistent, not just a toast: the next person to hit this needs to be able to read
        // the text without adb, and a toast disappears in two seconds.
        handler.post(() -> {
            hintView.setText(getString(R.string.hdr_test_play_error, message));
            hintView.setTextColor(Color.parseColor("#FF6E6E"));
            Toast.makeText(this, getString(R.string.hdr_test_play_error, message),
                    Toast.LENGTH_LONG).show();
        });
    }

    private void updateInfo() {
        Display display = getWindowManager().getDefaultDisplay();
        StringBuilder sb = new StringBuilder();

        boolean hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display.isHdr();
        sb.append(getString(R.string.hdr_test_info_hdr,
                getString(hdr ? R.string.common_yes : R.string.common_no)));

        try {
            Object info = Display.class.getMethod("getBrightnessInfo").invoke(display);
            if (info != null) {
                Class<?> type = info.getClass();
                float white = type.getField("brightness").getFloat(info);
                float max = type.getField("brightnessMaximum").getFloat(info);
                int hbm = type.getField("highBrightnessMode").getInt(info);
                sb.append("  ·  ").append(getString(R.string.hdr_test_info_brightness, fmt(white), fmt(max)));
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
            hintView.setText(pairMode ? R.string.hdr_test_pair_hint : R.string.hdr_test_hint);
            hintView.setTextColor(Color.parseColor("#C0FFFFFF"));
        }
    }

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
        int peak = pairMode
                ? (int) Math.min(Math.max(pairPeakNits, 300), 4000)
                : STEPS_NITS[selectedIndex];
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
        stopLadder();
        paneA.close();
        paneB.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!pairMode && paneA.hasSurface()) {
            startLadder();
        }
    }
}
