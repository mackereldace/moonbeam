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
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.preferences.PreferenceConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Measures the display's luminance ceiling on the device itself.
 *
 * Two fields are shown side by side. The right one is always the top of the ladder, which any
 * panel clips to its own peak - the brightest light it can produce. The left one is a candidate:
 * if it looks dimmer than the right, it is still below the ceiling; if the two look identical,
 * it has reached it. A plain bisection over a one-percent log ladder finds that point in about
 * nine answers, and nothing here depends on the peak Android reports, which is what makes the
 * tool worth having when a panel under-reports itself.
 */
public class HdrTestActivity extends Activity implements SurfaceHolder.Callback {

    private static final String LEVEL_CLIP = "hdr/levels.mp4";
    private static final String LADDER_CLIP = "hdr/ladder.mp4";
    /** One percent apart, from 100 to 10000 nit. */
    private static final int LEVEL_COUNT = 462;
    private static final double LEVEL_MIN_NITS = 100.0;
    private static final double LEVEL_MAX_NITS = 10000.0;
    private static final double START_NITS = 5000.0;
    /** ceil(log2(LEVEL_COUNT)); shown as the progress denominator. */
    private static final int MAX_STEPS = 9;

    /** One SurfaceView plus the decoder that feeds it, kept alive across answers. */
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
    private TextView questionView;
    private TextView valuesView;
    private TextView modeToggle;
    private boolean ladderMode;
    private volatile boolean ladderRunning;
    private Thread ladderThread;
    private Pane ladderPane;
    private TextView progressView;
    private TextView answerDimmer;
    private TextView answerSame;

    private int indexLow = -1;
    private int indexHigh = -1;
    private int indexCandidate;
    private int steps;
    private double peakNits;
    private boolean finished;

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
        questionView = findViewById(R.id.hdrQuestion);
        modeToggle = findViewById(R.id.hdrMode);
        valuesView = findViewById(R.id.hdrValues);
        progressView = findViewById(R.id.hdrProgress);
        answerDimmer = findViewById(R.id.hdrAnswerDimmer);
        answerSame = findViewById(R.id.hdrAnswerSame);

        paneA.view.getHolder().addCallback(this);
        paneB.view.getHolder().addCallback(this);

        modeToggle.setOnClickListener(v -> toggleMode());
        answerDimmer.setOnClickListener(v -> answer(true));
        answerSame.setOnClickListener(v -> answer(false));
        findViewById(R.id.hdrSave).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.hdrClose).setOnClickListener(v -> finish());
    }

    // ---- ladder ------------------------------------------------------------

    /**
     * Eight fixed steps from 100 to 3200 nit, shown as adjacent bars. This is the sanity view:
     * it says whether the screen separates highlights at all, and where the steps start to
     * merge, without asking the user anything. The two variants that used to live here (a
     * shorter band and a single step at a time) only repeated what this already shows.
     */
    private void toggleMode() {
        ladderMode = !ladderMode;
        modeToggle.setText(ladderMode ? R.string.hdr_test_mode_measure : R.string.hdr_test_mode_ladder);
        if (ladderMode) {
            stopSearch();
            paneB.view.setVisibility(View.GONE);
            questionView.setText(R.string.hdr_test_ladder_hint);
            valuesView.setText("");
            progressView.setText("");
            answerDimmer.setAlpha(0.35f);
            answerSame.setAlpha(0.35f);
            startLadder();
        }
        else {
            stopLadder();
            paneB.view.setVisibility(View.VISIBLE);
            answerDimmer.setAlpha(1f);
            answerSame.setAlpha(1f);
            questionView.setText(R.string.hdr_test_question);
            startSearch();
        }
    }

    private void startLadder() {
        stopLadder();
        ladderRunning = true;
        ladderThread = new Thread(this::decodeLadder, "hdr-ladder");
        ladderThread.start();
    }

    private void stopLadder() {
        ladderRunning = false;
        Thread thread = ladderThread;
        ladderThread = null;
        if (thread != null) {
            try {
                thread.join(1200);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (ladderPane != null) {
            ladderPane.close();
            ladderPane = null;
        }
    }

    private void decodeLadder() {
        ladderPane = new Pane(paneA.view);
        long deadline = SystemClock.elapsedRealtime() + 1500;
        while (!ladderPane.hasSurface() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        if (!ensurePane(ladderPane, LADDER_CLIP, 1)) {
            return;
        }
        MediaExtractor extractor = ladderPane.extractor;
        MediaCodec codec = ladderPane.codec;
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (ladderRunning) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
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
                    codec.releaseOutputBuffer(outIndex, true);
                    if (eos) {
                        codec.flush();
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                    }
                }
            }
        }
        catch (RuntimeException e) {
            reportFailure("ladder", e);
        }
    }

    private void stopSearch() {
        finished = true;
    }

    // ---- search ------------------------------------------------------------

    private void startSearch() {
        indexLow = -1;
        indexHigh = -1;
        indexCandidate = nearestLevel(START_NITS);
        steps = 0;
        finished = false;
        ask();
    }

    private void ask() {
        steps++;
        show(valuesView, getString(R.string.hdr_test_values,
                Math.round(levelNits(indexCandidate)), Math.round(levelNits(LEVEL_COUNT - 1))));
        int low = indexLow >= 0 ? indexLow : 0;
        int high = indexHigh >= 0 ? indexHigh : LEVEL_COUNT - 1;
        progressView.setText(getString(R.string.hdr_test_progress, steps, MAX_STEPS,
                Math.round(levelNits(low)), Math.round(levelNits(high))));
        renderLevel(paneA, indexCandidate);
        renderLevel(paneB, LEVEL_COUNT - 1);
    }

    private void answer(boolean dimmer) {
        if (finished) {
            return;
        }
        if (dimmer) {
            indexLow = indexCandidate;
        }
        else {
            indexHigh = indexCandidate;
        }

        int low = indexLow >= 0 ? indexLow : 0;
        int high = indexHigh >= 0 ? indexHigh : LEVEL_COUNT - 1;
        int next = (low + high) / 2;

        boolean converged = (indexLow >= 0 && indexHigh >= 0 && indexHigh - indexLow <= 1)
                || next == indexCandidate || next == indexLow || next == indexHigh;
        indexCandidate = next;
        if (converged) {
            finishSearch();
        }
        else {
            ask();
        }
    }

    private void finishSearch() {
        finished = true;
        double lower = levelNits(indexLow >= 0 ? indexLow : 0);
        double upper = levelNits(indexHigh >= 0 ? indexHigh : LEVEL_COUNT - 1);
        peakNits = Math.sqrt(lower * upper);
        show(valuesView, getString(R.string.hdr_test_result, Math.round(peakNits),
                Math.round(lower), Math.round(upper)));
        progressView.setText(getString(R.string.hdr_test_done, steps));
        answerDimmer.setAlpha(0.35f);
        answerSame.setAlpha(0.35f);
    }

    /** Level i's luminance: log-spaced across the clip's range. */
    private static double levelNits(int index) {
        double t = (double) index / (LEVEL_COUNT - 1);
        return LEVEL_MIN_NITS * Math.pow(LEVEL_MAX_NITS / LEVEL_MIN_NITS, t);
    }

    private static int nearestLevel(double nits) {
        double t = Math.log(nits / LEVEL_MIN_NITS) / Math.log(LEVEL_MAX_NITS / LEVEL_MIN_NITS);
        return Math.max(0, Math.min(LEVEL_COUNT - 1, (int) Math.round(t * (LEVEL_COUNT - 1))));
    }

    // ---- rendering ---------------------------------------------------------

    private void renderLevel(final Pane pane, final int index) {
        renderer.execute(() -> renderLevelNow(pane, index));
    }

    private void renderLevelNow(Pane pane, int index) {
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
        }
        catch (RuntimeException e) {
            pane.close();
            reportFailure("render", e);
        }
    }

    private boolean ensurePane(Pane pane) {
        return ensurePane(pane, LEVEL_CLIP, LEVEL_COUNT);
    }

    private boolean ensurePane(Pane pane, String clip, int levelCount) {
        if (pane.codec != null) {
            return true;
        }
        if (!pane.hasSurface()) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            AssetFileDescriptor afd = getAssets().openFd(clip);
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
                reportFailure("open", new IllegalStateException("no video track"));
                return false;
            }
            extractor.selectTrack(track);

            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);
                if (durationUs > 0) {
                    pane.segmentUs = durationUs / levelCount;
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

    // ---- lifecycle ---------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (paneA.hasSurface() && paneB.hasSurface() && !finished && steps == 0) {
            handler.post(this::startSearch);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        paneA.close();
        paneB.close();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(sampler);
        if (!ladderMode && paneA.hasSurface() && paneB.hasSurface() && steps == 0) {
            handler.post(this::startSearch);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(sampler);
        stopLadder();
        paneA.close();
        paneB.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        renderer.shutdownNow();
    }

    // ---- diagnostics -------------------------------------------------------

    private void reportFailure(String stage, Throwable error) {
        StringBuilder trace = new StringBuilder();
        for (StackTraceElement element : error.getStackTrace()) {
            trace.append(element).append('\n');
            if (trace.length() > 600) {
                break;
            }
        }
        LimeLog.severe("HDR test " + stage + " failed: " + error + "\n" + trace);
        handler.post(() -> {
            valuesView.setText(getString(R.string.hdr_test_play_error, stage + ": "
                    + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : " " + error.getMessage())));
            valuesView.setTextColor(Color.parseColor("#FF6E6E"));
        });
    }

    private void updateInfo() {
        Display display = getWindowManager().getDefaultDisplay();
        StringBuilder sb = new StringBuilder();

        boolean hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display.isHdr();
        sb.append(getString(R.string.hdr_test_info_hdr,
                getString(hdr ? R.string.common_yes : R.string.common_no)));

        // BrightnessInfo is @hide: read reflectively, skipped when unavailable.
        try {
            Object info = Display.class.getMethod("getBrightnessInfo").invoke(display);
            if (info != null) {
                Class<?> type = info.getClass();
                sb.append("  ·  ").append(getString(R.string.hdr_test_info_brightness,
                        fmt(type.getField("brightness").getFloat(info)),
                        fmt(type.getField("brightnessMaximum").getFloat(info))));
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
            infoView.setTextColor(Color.parseColor("#FF6E6E"));
            infoView.append("  ·  " + getString(R.string.hdr_test_not_hdr));
        }
        else {
            infoView.setTextColor(Color.parseColor("#80FFFFFF"));
        }
    }

    private static String fmt(float value) {
        return String.format(Locale.US, "%.0f", value);
    }

    private void saveAndFinish() {
        if (!finished) {
            Toast.makeText(this, R.string.hdr_test_finish_first, Toast.LENGTH_SHORT).show();
            return;
        }
        int peak = (int) Math.min(Math.max(peakNits, 300), 4000);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
                .putBoolean(PreferenceConfiguration.HDR_BRIGHTNESS_OVERRIDE_PREF_STRING, true)
                .putInt(PreferenceConfiguration.HDR_PEAK_BRIGHTNESS_NITS_PREF_STRING, peak)
                .apply();
        Toast.makeText(this, getString(R.string.hdr_test_saved, peak), Toast.LENGTH_LONG).show();
        finish();
    }

    private void show(TextView view, String text) {
        view.setText(text);
    }
}
