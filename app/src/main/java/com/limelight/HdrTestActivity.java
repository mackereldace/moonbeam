package com.limelight;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
    private static final float[] LADDER_NITS = {100, 203, 400, 700, 1000, 1600, 2400, 3200};

    private SurfaceView surfaceView;
    private TextView infoView;
    private TextView questionView;
    private TextView valuesView;
    private TextView progressView;
    private TextView modeToggle;
    private View answersRow;
    private TextView slotView;
    private int slot = 0;

    private static final int MODE_PEAK = 0;    // two 5% squares: peak luminance
    private static final int MODE_GLOBAL = 1;  // full-screen checkerboard: full-frame ceiling
    private static final int MODE_BLACK = 2;   // dark patch on black: black level
    private static final int MODE_LADDER = 3;  // eight bands, no measuring

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

        setContentView(R.layout.activity_hdr_test);
        applyWindowInsets();

        surfaceView = findViewById(R.id.hdrSurface);
        infoView = findViewById(R.id.hdrInfo);
        questionView = findViewById(R.id.hdrQuestion);
        valuesView = findViewById(R.id.hdrValues);
        progressView = findViewById(R.id.hdrProgress);
        modeToggle = findViewById(R.id.hdrMode);
        slotView = findViewById(R.id.hdrSlot);
        slotView.setText(getString(R.string.hdr_test_slot, slot + 1));
        slotView.setOnClickListener(v -> {
            slot = (slot + 1) % HdrProfileStore.SLOT_COUNT;
            slotView.setText(getString(R.string.hdr_test_slot, slot + 1));
        });
        answersRow = findViewById(R.id.hdrAnswers);

        surfaceView.getHolder().addCallback(this);
        modeToggle.setOnClickListener(v -> toggleMode());
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
        int gap = Math.max(8, surfaceWidth / 100);
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

    private void drawLadder() {
        int top = (int) (surfaceHeight * 0.1);
        int height = (int) (surfaceHeight * 0.8);
        HdrCanvas.nativeDrawLadder(top, height, LADDER_NITS);
    }

    private void redraw() {
        if (!ready) {
            return;
        }
        if (mode == MODE_LADDER) {
            drawLadder();
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
                    Math.round(areaPercent()), Math.round(areaPercent() * 2)));
            drawPair(candidateNits, MAX_NITS);
        }
    }

    /** Area of each patch as a percentage of the screen, computed from the real geometry. */
    private double areaPercent() {
        int side = (int) Math.sqrt(PATCH_AREA * surfaceWidth * surfaceHeight);
        return 100.0 * side * side / ((double) surfaceWidth * surfaceHeight);
    }

    private void answer(boolean dimmer) {
        if (!ready || finished) {
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
        valuesView.setText("");
        progressView.setText(getString(R.string.hdr_test_done, steps));
        answersRow.setVisibility(View.GONE);
        redraw();
    }

    private void toggleMode() {
        mode = (mode + 1) % 4;
        applyMode();
    }

    private void applyMode() {
        boolean measuring = mode != MODE_LADDER;
        answersRow.setVisibility(measuring ? View.VISIBLE : View.GONE);
        valuesView.setVisibility(measuring ? View.VISIBLE : View.GONE);
        progressView.setVisibility(measuring ? View.VISIBLE : View.GONE);

        switch (mode) {
            case MODE_PEAK:
                modeToggle.setText(R.string.hdr_test_mode_global);
                questionView.setText(R.string.hdr_test_question);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_dimmer);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_same);
                break;
            case MODE_GLOBAL:
                modeToggle.setText(R.string.hdr_test_mode_black);
                questionView.setText(R.string.hdr_test_question_grid);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_visible);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_invisible);
                break;
            case MODE_BLACK:
                modeToggle.setText(R.string.hdr_test_mode_ladder);
                questionView.setText(R.string.hdr_test_question_black);
                ((TextView) findViewById(R.id.hdrAnswerDimmer)).setText(R.string.hdr_test_answer_visible);
                ((TextView) findViewById(R.id.hdrAnswerSame)).setText(R.string.hdr_test_answer_invisible);
                break;
            default:
                modeToggle.setText(R.string.hdr_test_mode_peak);
                questionView.setText(R.string.hdr_test_ladder_hint);
                drawLadder();
                return;
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
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                right = bars.right;
                bottom = bars.bottom;
            }
            else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
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
        infoView.setText(sb.toString());
        infoView.setTextColor(hdr ? Color.parseColor("#66FFFFFF") : Color.parseColor("#FF6E6E"));
    }

    private void saveAndFinish() {
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

        if (field == HdrProfileStore.FIELD_PEAK) {
            // The host-facing override only makes sense for a peak.
            int peak = (int) Math.min(Math.max(peakNits, 300), 4000);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit()
                    .putBoolean(PreferenceConfiguration.HDR_BRIGHTNESS_OVERRIDE_PREF_STRING, true)
                    .putInt(PreferenceConfiguration.HDR_PEAK_BRIGHTNESS_NITS_PREF_STRING, peak)
                    .apply();
        }
        Toast.makeText(this, getString(R.string.hdr_test_saved_slot,
                formatNits(peakNits), slot + 1), Toast.LENGTH_LONG).show();
        finish();
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
