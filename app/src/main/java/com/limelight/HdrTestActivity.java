package com.limelight;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.hdr.HdrCanvas;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.HdrDisplayCapabilities;
import com.limelight.utils.HdrProfileStore;

import java.util.Locale;

/**
 * Measures the display's luminance ceiling by drawing PQ patches natively.
 *
 * The values are produced on the device rather than decoded from a clip: the surface is
 * declared BT2020/PQ through EGL, so the shader's output is a PQ code and any luminance can be
 * asked for at runtime. That makes the patch geometry exact (5% of the screen really is 5%), it
 * removes the clips from the APK, and a measurement can be refined to any resolution.
 *
 * The right patch is always the top of the range, which the panel clips to its own peak - the
 * brightest it can produce. The left one is bisected: dimmer means still below the ceiling,
 * identical means it has reached it.
 */
public class HdrTestActivity extends Activity implements SurfaceHolder.Callback {

    private static final double PATCH_AREA = 0.05;   // per patch, so 10% total
    private static final double START_NITS = 5000;
    private static final double MIN_NITS = 100;
    private static final double MAX_NITS = 10000;
    private static final int MAX_STEPS = 9;

    private SurfaceView surfaceView;
    private TextView infoView;
    private TextView questionView;
    private TextView valuesView;
    private TextView progressView;
    private TextView[] modeButtons;
    private View answersRow;
    private TextView slotView;
    private int slot = 0;
    private boolean guarding;
    private long guardStartMillis;
    private double guardDimNits;
    private RatioPlotView ratioPlot;
    private int ticks;
    private float ratioHigh;
    private float ratioBaseline;
    private int dropSamples;
    private boolean dropRecorded;
    private int dropSeconds;
    private float ratioFirst;
    private float ratioLast;
    private float ratioMin;

    private static final int MODE_PEAK = 0;    // two 5% squares: peak luminance
    private static final int MODE_GLOBAL = 1;  // full-screen checkerboard: full-frame ceiling
    private static final int MODE_BLACK = 2;   // dark patch on black: black level

    private static final double BLACK_MIN_NITS = 0.001;
    private static final double BLACK_MAX_NITS = 1.0;
    private static final double BLACK_START_NITS = 0.05;
    private static final int BLACK_STEPS = 8;

    private boolean ready;
    private int mode = MODE_PEAK;

    private double lowNits;
    private double highNits;
    private double candidateNits;
    private int steps;
    private double peakNits;
    private boolean finished;

    private int surfaceWidth;
    private int surfaceHeight;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        setContentView(R.layout.activity_hdr_test);
        applyWindowInsets();

        surfaceView = findViewById(R.id.hdrSurface);
        infoView = findViewById(R.id.hdrInfo);
        questionView = findViewById(R.id.hdrQuestion);
        valuesView = findViewById(R.id.hdrValues);
        progressView = findViewById(R.id.hdrProgress);
        ratioPlot = findViewById(R.id.hdrRatioPlot);
        modeButtons = new TextView[] {
                findViewById(R.id.hdrModePeak),
                findViewById(R.id.hdrModeGlobal),
                findViewById(R.id.hdrModeBlack)};
        for (int i = 0; i < modeButtons.length; i++) {
            final int index = i;
            modeButtons[i].setOnClickListener(v -> {
                if (mode != index) {
                    mode = index;
                    applyMode();
                }
            });
        }
        slotView = findViewById(R.id.hdrSlot);
        slotView.setText(getString(R.string.hdr_test_slot, slot + 1));
        slotView.setOnClickListener(v -> {
            slot = (slot + 1) % HdrProfileStore.SLOT_COUNT;
            slotView.setText(getString(R.string.hdr_test_slot, slot + 1));
        });
        answersRow = findViewById(R.id.hdrAnswers);

        surfaceView.getHolder().addCallback(this);
        highlightMode();
        findViewById(R.id.hdrAnswerDimmer).setOnClickListener(v -> answer(true));
        findViewById(R.id.hdrAnswerSame).setOnClickListener(v -> answer(false));
        findViewById(R.id.hdrSave).setOnClickListener(v -> saveAndFinish());
        findViewById(R.id.hdrClose).setOnClickListener(v -> finish());
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        surfaceWidth = surfaceView.getWidth();
        surfaceHeight = surfaceView.getHeight();
        int initResult = HdrCanvas.nativeInit(surface, surfaceWidth, surfaceHeight);
        ready = initResult == 0;
        if (!ready) {
            valuesView.setText(getString(R.string.hdr_test_no_pq, initResult, describeInit(initResult)));
            valuesView.setTextColor(Color.parseColor("#FF6E6E"));
            LimeLog.severe("HDR test: init failed at step " + initResult);
            return;
        }
        startSearch();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        if (ready) {
            redraw();
        }
        else if (surfaceValid(holder)) {
            // Rotation tears the surface down; set it up again and keep the search where it was.
            int result = HdrCanvas.nativeInit(holder.getSurface(), width, height);
            ready = result == 0;
            if (!ready) {
                valuesView.setText(getString(R.string.hdr_test_no_pq, result, describeInit(result)));
                valuesView.setTextColor(Color.parseColor("#FF6E6E"));
                return;
            }
            if (steps == 0) {
                startSearch();
            }
            else {
                redraw();
            }
        }
    }

    private static boolean surfaceValid(SurfaceHolder holder) {
        return holder.getSurface() != null && holder.getSurface().isValid();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (ready) {
            HdrCanvas.nativeRelease();
            ready = false;
        }
    }

    private String describeInit(int code) {
        switch (code) {
            case 1:
                return getString(R.string.hdr_init_no_display);
            case 2:
                return getString(R.string.hdr_init_no_config);
            case 3:
                return getString(R.string.hdr_init_no_context);
            case 4:
                return getString(R.string.hdr_init_no_surface);
            case 6:
                return getString(R.string.hdr_init_shader);
            default:
                return getString(R.string.hdr_init_no_pq);
        }
    }

    private void drawPair(double leftNits, double rightNits) {
        int side = (int) Math.sqrt(PATCH_AREA * surfaceWidth * surfaceHeight);
        // The two squares touch: a gap would put a black bar exactly where the comparison
        // happens, and the eye would judge the edges instead of the two fields.
        int gap = 0;
        int leftX = (surfaceWidth - (side * 2 + gap)) / 2;
        int top = (surfaceHeight - side) / 2;
        HdrCanvas.nativeDrawPair(leftX, top, side, side, (float) leftNits,
                leftX + side + gap, top, side, side, (float) rightNits);
    }

    /** One patch on a black field: what a black-level search needs is an isolated patch. */
    private void drawBlackPatch(double nits) {
        int side = (int) Math.sqrt(PATCH_AREA * surfaceWidth * surfaceHeight);
        int left = (surfaceWidth - side) / 2;
        int top = (surfaceHeight - side) / 2;
        HdrCanvas.nativeDraw(left, top, side, side, (float) nits);
    }

    /** Full-screen checkerboard: cells alternate the candidate and the top of the range. */
    private void drawGrid(double candidate, double reference) {
        int cell = Math.max(32, surfaceWidth / 16);
        HdrCanvas.nativeDrawGrid(cell, (float) reference, (float) candidate);
    }

    private void redraw() {
        if (!ready) {
            return;
        }
        if (guarding) {
            drawGrid(guardDimNits, MAX_NITS);
        }
        else if (mode == MODE_GLOBAL) {
            drawGrid(finished ? peakNits : candidateNits, MAX_NITS);
        }
        else if (mode == MODE_BLACK) {
            drawBlackPatch(finished ? peakNits : candidateNits);
        }
        else if (!finished) {
            drawPair(candidateNits, MAX_NITS);
        }
        else {
            drawPair(peakNits, MAX_NITS);
        }
    }

    // ---- search ------------------------------------------------------------

    private void startSearch() {
        lowNits = 0;
        highNits = 0;
        candidateNits = mode == MODE_BLACK ? BLACK_START_NITS : START_NITS;
        steps = 0;
        finished = false;
        ask();
    }

    private void ask() {
        steps++;
        if (mode == MODE_BLACK) {
            valuesView.setText(getString(R.string.hdr_test_values_black, formatNits(candidateNits)));
            progressView.setText(getString(R.string.hdr_test_progress_black, steps, BLACK_STEPS));
            drawBlackPatch(candidateNits);
            return;
        }
        if (mode == MODE_GLOBAL) {
            valuesView.setText(getString(R.string.hdr_test_values_grid,
                    Math.round(candidateNits), Math.round(MAX_NITS)));
            progressView.setText(getString(R.string.hdr_test_progress_grid, steps, MAX_STEPS));
            drawGrid(candidateNits, MAX_NITS);
        }
        else {
            valuesView.setText(getString(R.string.hdr_test_values,
                    Math.round(candidateNits), Math.round(MAX_NITS)));
            progressView.setText(getString(R.string.hdr_test_progress, steps, MAX_STEPS,
                    Math.round(areaPercent())));
            drawPair(candidateNits, MAX_NITS);
        }
    }

    /** Area of each patch as a percentage of the screen, computed from the real geometry. */
    private double areaPercent() {
        int side = (int) Math.sqrt(PATCH_AREA * surfaceWidth * surfaceHeight);
        return 100.0 * side * side / ((double) surfaceWidth * surfaceHeight);
    }

    private void answer(boolean dimmer) {
        if (!ready) {
            return;
        }
        if (guarding) {
            if (dimmer) {
                saveHoldTime();
            }
            else {
                guardStartMillis = SystemClock.elapsedRealtime();
            }
            return;
        }
        if (finished) {
            return;
        }
        if (mode == MODE_BLACK) {
            answerBlack(dimmer);
            return;
        }
        if (dimmer) {
            lowNits = candidateNits;
        }
        else {
            highNits = candidateNits;
        }

        double low = lowNits > 0 ? lowNits : MIN_NITS;
        double high = highNits > 0 ? highNits : MAX_NITS;
        double next = Math.sqrt(low * high);
        boolean converged = (lowNits > 0 && highNits > 0 && high / low < 1.01)
                || Math.abs(next - candidateNits) < 1;
        candidateNits = next;
        if (converged) {
            finishSearch();
        }
        else {
            ask();
        }
    }

    /**
     * Black level: the smallest luminance that is still visible against black.
     * "I can see it" moves the ceiling down, "I cannot" moves the floor up.
     */
    private void answerBlack(boolean visible) {
        if (visible) {
            highNits = candidateNits;
        }
        else {
            lowNits = candidateNits;
        }
        double low = lowNits > 0 ? lowNits : BLACK_MIN_NITS;
        double high = highNits > 0 ? highNits : BLACK_MAX_NITS;
        double next = Math.sqrt(low * high);
        boolean converged = (lowNits > 0 && highNits > 0 && high / low < 1.05)
                || Math.abs(next - candidateNits) < 1e-6;
        candidateNits = next;
        if (converged) {
            finished = true;
            peakNits = highNits > 0 ? highNits : next;
            questionView.setText(getString(R.string.hdr_test_result_black,
                    formatNits(peakNits), formatNits(low), formatNits(high)));
            valuesView.setText("");
            progressView.setText(getString(R.string.hdr_test_done, steps));
            answersRow.setVisibility(View.GONE);
            redraw();
        }
        else {
            ask();
        }
    }

    private static String formatNits(double nits) {
        return nits < 1.0
                ? String.format(Locale.US, "%.3f", nits)
                : String.format(Locale.US, "%.0f", nits);
    }

    private void finishSearch() {
        finished = true;
        double low = lowNits > 0 ? lowNits : MIN_NITS;
        double high = highNits > 0 ? highNits : MAX_NITS;
        peakNits = Math.sqrt(low * high);
        questionView.setText(getString(mode == MODE_GLOBAL
                ? R.string.hdr_test_result_grid : R.string.hdr_test_result,
                Math.round(peakNits), Math.round(low), Math.round(high)));
        if (mode == MODE_GLOBAL) {
            startGuarding();
            return;
        }
        valuesView.setText("");
        progressView.setText(getString(R.string.hdr_test_done, steps));
        answersRow.setVisibility(View.GONE);
        redraw();
    }

    private void highlightMode() {
        for (int i = 0; i < modeButtons.length; i++) {
            boolean active = i == mode;
            modeButtons[i].setBackgroundResource(active
                    ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
            modeButtons[i].setTextColor(active ? Color.WHITE : Color.parseColor("#99FFFFFF"));
        }
    }

    /**
     * After the full-frame ceiling is known, keep a checkerboard on screen whose dim cells sit
     * at 80% of that ceiling. The bright cells clip to the panel, so the pattern stays visible
     * only while the panel still reaches 80% of what it reached a minute ago; if it fades,
     * brightness dropped. The elapsed time until that happens is what the save button records.
     */
    private void startGuarding() {
        guarding = true;
        guardStartMillis = SystemClock.elapsedRealtime();
        guardDimNits = peakNits * 0.8;
        ratioPlot.setVisibility(View.VISIBLE);
        ratioPlot.clear();
        dropSamples = 0;
        dropRecorded = false;
        dropSeconds = 0;
        ratioBaseline = 0f;
        ratioFirst = currentRatio();
        ratioLast = ratioFirst;
        ratioMin = ratioFirst;
        answersRow.setVisibility(View.VISIBLE);
        valuesView.setVisibility(View.VISIBLE);
        valuesView.setText(getString(R.string.hdr_test_guard_hint, Math.round(guardDimNits)));
        ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_save_time);
        ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_restart);
        drawGrid(guardDimNits, MAX_NITS);
        handler.post(ticker);
    }

    private void stopGuarding() {
        guarding = false;
        handler.removeCallbacks(ticker);
        if (ratioPlot != null) {
            ratioPlot.setVisibility(View.GONE);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!guarding) {
                return;
            }
            float ratio = currentRatio();
            ratioPlot.add(ratio);
            trackDrop(ratio);
            if (ticks++ % 10 == 0) {
                LimeLog.info("HDR hold: ratio=" + ratio + " t=" + elapsedText());
            }
            if (ratio > 0f) {
                if (ratioFirst <= 0f) {
                    ratioFirst = ratio;
                }
                ratioLast = ratio;
                if (ratioMin <= 0f || ratio < ratioMin) {
                    ratioMin = ratio;
                }
            }
            progressView.setText(getString(R.string.hdr_test_guard_timer, elapsedText())
                    + "  ·  " + getString(R.string.hdr_test_guard_ratio,
                    ratioText(ratioFirst), ratioText(ratioLast), ratioText(ratioMin)));
            handler.postDelayed(this, 500);
        }
    };

    /**
     * Watches for the moment the ratio starts falling and locks the elapsed time there.
     *
     * Waiting for the checkerboard to fade asks the user to notice something subtle and to
     * react in time; the ratio drops first and more sharply, so it is the better trigger. The
     * baseline is the highest value seen, and a drop must persist for a second before it counts
     * - one noisy sample should not end the test.
     */
    private void trackDrop(float ratio) {
        if (ratio <= 0f || dropRecorded) {
            return;
        }
        if (ratio > ratioBaseline) {
            ratioBaseline = ratio;
            dropSamples = 0;
            return;
        }
        if (ratioBaseline > 0f && ratio <= ratioBaseline * 0.97f) {
            if (++dropSamples >= 2) {
                dropRecorded = true;
                dropSeconds = Math.max(1, elapsedSeconds());
                HdrProfileStore.set(this, slot, HdrProfileStore.FIELD_STABILITY, dropSeconds);
                progressView.setText(getString(R.string.hdr_test_dropped, secondsText(dropSeconds)));
                LimeLog.info("HDR hold: ratio dropped " + ratioBaseline + " -> " + ratio
                        + " at " + dropSeconds + "s");
            }
        }
        else {
            dropSamples = 0;
        }
    }

    /** What the display currently reports as its HDR/SDR ratio; 0 when unavailable. */
    private float currentRatio() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable()) {
                return display.getHdrSdrRatio();
            }
        }
        catch (RuntimeException ignored) {
        }
        return 0f;
    }

    /** Highest ratio the display can report; 0 when the API is unavailable. */
    private float highestRatio() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= 36 && display.isHdrSdrRatioAvailable()) {
                return display.getHighestHdrSdrRatio();
            }
        }
        catch (RuntimeException ignored) {
        }
        return 0f;
    }

    /** Display brightness as the system currently applies it; 0 when it cannot be read. */
    private float currentBrightness() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Object info = Display.class.getMethod("getBrightnessInfo").invoke(display);
            if (info != null) {
                java.lang.reflect.Field field = info.getClass().getField("brightness");
                return field.getFloat(info);
            }
        }
        catch (Throwable ignored) {
        }
        return 0f;
    }

    private static String ratioText(float ratio) {
        return ratio <= 0f ? "-" : String.format(Locale.US, "%.2f", ratio);
    }

    private int elapsedSeconds() {
        return (int) ((SystemClock.elapsedRealtime() - guardStartMillis) / 1000);
    }

    private String elapsedText() {
        int total = elapsedSeconds();
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private void applyMode() {
        stopGuarding();
        highlightMode();

        answersRow.setVisibility(View.VISIBLE);
        valuesView.setVisibility(View.VISIBLE);
        progressView.setVisibility(View.VISIBLE);

        switch (mode) {
            case MODE_PEAK:
                questionView.setText(R.string.hdr_test_question);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_dimmer);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_same);
                break;
            case MODE_GLOBAL:
                questionView.setText(R.string.hdr_test_question_grid);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_visible);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_invisible);
                break;
            case MODE_BLACK:
                questionView.setText(R.string.hdr_test_question_black);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_visible);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_invisible);
                break;
            default:
                break;
        }
        startSearch();
    }

    // ---- lifecycle ---------------------------------------------------------

    private void applyWindowInsets() {
        final View root = findViewById(R.id.hdrRoot);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        else {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int right;
            int bottom;
            // Only the navigation bar is respected. The cutout is deliberately ignored: the
            // fields are what is being judged, and shrinking them to dodge a camera hole puts
            // black margins inside the thing that is supposed to be uniform.
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.navigationBars());
                left = 0;
                right = 0;
                bottom = bars.bottom;
            }
            else {
                left = 0;
                right = 0;
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, 0, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void updateInfo() {
        Display display = getWindowManager().getDefaultDisplay();
        StringBuilder sb = new StringBuilder();
        boolean hdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display.isHdr();
        sb.append(getString(R.string.hdr_test_info_hdr,
                getString(hdr ? R.string.common_yes : R.string.common_no)));
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
        // Track the best ratio seen: the system compresses the whole HDR range once it reduces
        // headroom, and a compressed range still separates two patches, so a measurement taken
        // then describes the compression, not the panel.
        float ratio = currentRatio();
        // The API knows the ceiling; tracking the maximum seen is only a fallback for devices
        // that do not expose it (API 36+).
        float apiHighest = highestRatio();
        if (apiHighest > 0f) {
            ratioHigh = apiHighest;
        }
        else if (ratio > ratioHigh) {
            ratioHigh = ratio;
        }
        if (ratio > 0f && ratioHigh > 0f) {
            boolean compressed = ratio < ratioHigh * 0.98f;
            sb.append("  ·  ").append(getString(R.string.hdr_test_info_ratio_pair,
                    String.format(Locale.US, "%.2f", ratio),
                    String.format(Locale.US, "%.2f", ratioHigh)));
            if (compressed) {
                sb.append("  ·  ").append(getString(R.string.hdr_test_compressed,
                        String.format(Locale.US, "%.2f", ratio / ratioHigh)));
            }
            infoView.setTextColor(compressed ? Color.parseColor("#FFC46E")
                    : (hdr ? Color.parseColor("#66FFFFFF") : Color.parseColor("#FF6E6E")));
        }
        else {
            infoView.setTextColor(hdr ? Color.parseColor("#66FFFFFF") : Color.parseColor("#FF6E6E"));
        }

        infoView.setText(sb.toString());
    }

    /**
     * Records how long the full-frame level held. A value below one second is meaningless and
     * would read as "not measured", so it is clamped.
     */
    private void saveHoldTime() {
        int seconds = Math.max(1, elapsedSeconds());
        HdrProfileStore.set(this, slot, HdrProfileStore.FIELD_STABILITY, seconds);

        Toast.makeText(this, getString(R.string.hdr_test_saved_time, secondsText(seconds), slot + 1),
                Toast.LENGTH_LONG).show();
    }

    private static String secondsText(int seconds) {
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void saveAndFinish() {
        if (guarding) {
            // While the hold test is running the measurement in hand is the elapsed time itself.
            saveHoldTime();
            return;
        }
        if (!finished) {
            Toast.makeText(this, R.string.hdr_test_finish_first, Toast.LENGTH_SHORT).show();
            return;
        }
        // Each run writes one field into one slot; measuring a peak today and a black level
        // tomorrow can end up in the same profile instead of overwriting each other.
        int field;
        if (mode == MODE_GLOBAL) {
            field = HdrProfileStore.FIELD_GLOBAL;
        }
        else if (mode == MODE_BLACK) {
            field = HdrProfileStore.FIELD_BLACK;
        }
        else {
            field = HdrProfileStore.FIELD_PEAK;
        }
        HdrProfileStore.set(this, slot, field, (float) peakNits);
        if (ratioHigh > 0f && currentRatio() < ratioHigh * 0.98f) {
            Toast.makeText(this, R.string.hdr_test_saved_compressed, Toast.LENGTH_LONG).show();
        }

        Toast.makeText(this, getString(R.string.hdr_test_saved_slot,
                formatNits(peakNits), slot + 1), Toast.LENGTH_LONG).show();
        // Stay on the page: there are three measurements and a hold test to get through.
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(sampler);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(sampler);
    }
}
