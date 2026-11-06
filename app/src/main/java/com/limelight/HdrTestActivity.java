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

/**
 * On-device HDR test.
 *
 * Two ways to use it:
 *
 *  - Ladder. Eight steps from 100 to 3200 nit, three layouts. Good for "can this screen show
 *    separate highlights at all", useless for a number: consecutive steps merge somewhere and
 *    the ladder's own resolution is the limit.
 *  - Pair search. Two patches, X and X+200, and a binary search down the range. It asks one
 *    question with an obvious answer - "does the right patch look brighter?" - and converges
 *    to the luminance where the panel stops producing more light. This is the only one of the
 *    two that can find a peak *above* what Android reports, which matters because some panels
 *    under-report it.
 *
 * Playback is MediaCodec, not MediaPlayer, because the stream decoder asks for full-range
 * output (KEY_COLOR_RANGE from the same preference the stream uses) and MediaPlayer has no
 * such knob. The clips are stored full range to match the chain this project runs on.
 *
 * It is a perceptual tool, not a meter: no absolute luminance, only where the ceiling is.
 */
public class HdrTestActivity extends Activity implements SurfaceHolder.Callback {

    /** Ladder steps, matching the ladder clips. */
    private static final int[] STEPS_NITS = {100, 203, 400, 700, 1000, 1600, 2400, 3200};

    /** Pair candidates: X and X+DELTA, one per segment of pairs.mp4. */
    private static final int PAIR_START_NITS = 1000;
    private static final int PAIR_STEP_NITS = 100;
    private static final int PAIR_COUNT = 31;
    private static final int PAIR_DELTA_NITS = 200;
    private static final long PAIR_SEGMENT_US = 2_000_000L;

    private static final String[] CLIPS = {
            "hdr/ladder-A.mp4",  // eight adjacent bars, full height
            "hdr/ladder-B.mp4",  // the same bars at 40% height
            "hdr/ladder-C.mp4",  // one bar at a time
    };
    private static final String PAIR_CLIP = "hdr/pairs.mp4";

    private SurfaceView surfaceView;
    private TextView infoView;
    private TextView hintView;
    private TextView pairStatus;
    private LinearLayout stepRow;
    private LinearLayout pairRow;
    private Button pairModeButton;
    private final Button[] stepButtons = new Button[STEPS_NITS.length];

    private volatile boolean running;
    private Thread decodeThread;
    private volatile String pendingClip = CLIPS[0];
    private boolean surfaceReady;
    private int selectedIndex = STEPS_NITS.length - 1;

    private boolean pairMode;
    private int pairLo, pairHi, pairMid, pairAnswerIndex, pairSteps;
    private int pairPeakNits;

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

        surfaceView = findViewById(R.id.hdrSurface);
        infoView = findViewById(R.id.hdrInfo);
        hintView = findViewById(R.id.hdrHint);
        stepRow = findViewById(R.id.hdrSteps);
        pairRow = findViewById(R.id.hdrPairRow);
        pairStatus = findViewById(R.id.hdrPairStatus);
        pairModeButton = findViewById(R.id.hdrPairMode);
        surfaceView.getHolder().addCallback(this);

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
        for (int i = 0; i < STEPS_NITS.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setText(String.format(Locale.US, "%d", STEPS_NITS[i]));
            button.setAllCaps(false);
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
                if (pairMode) {
                    return;  // the pair clip has its own layout
                }
                pendingClip = CLIPS[index];
                if (surfaceReady) {
                    startPlayback();
                }
            });
        }
    }

    // ---- pair search -------------------------------------------------------

    private void togglePairMode() {
        pairMode = !pairMode;
        stepRow.setVisibility(pairMode ? View.GONE : View.VISIBLE);
        pairRow.setVisibility(pairMode ? View.VISIBLE : View.GONE);
        pairModeButton.setText(pairMode ? R.string.hdr_test_mode_ladder : R.string.hdr_test_mode_pair);

        if (pairMode) {
            stopPlayback();
            pairLo = 0;
            pairHi = PAIR_COUNT - 1;
            pairAnswerIndex = -1;
            pairSteps = 0;
            showNextPair();
        }
        else {
            pairStatus.setText("");
            startPlayback();
        }
    }

    /** One comparison per step; the bracket halves until lo passes hi. */
    private void showNextPair() {
        if (pairLo > pairHi) {
            finishPairSearch();
            return;
        }
        pairMid = pairLo + (pairHi - pairLo) / 2;
        pairSteps++;
        int left = PAIR_START_NITS + pairMid * PAIR_STEP_NITS;
        pairStatus.setText(getString(R.string.hdr_test_pair_progress, pairSteps,
                left, left + PAIR_DELTA_NITS));
        showPairFrame(pairMid);
    }

    private void answerPair(boolean different) {
        if (!pairMode || pairLo > pairHi) {
            return;
        }
        if (different) {
            pairAnswerIndex = pairMid;
            pairLo = pairMid + 1;
        }
        else {
            pairHi = pairMid - 1;
        }
        showNextPair();
    }

    private void finishPairSearch() {
        if (pairAnswerIndex >= 0) {
            pairPeakNits = PAIR_START_NITS + pairAnswerIndex * PAIR_STEP_NITS + PAIR_DELTA_NITS;
            pairStatus.setText(getString(R.string.hdr_test_pair_result, pairPeakNits));
        }
        else {
            // Never looked different, even at the top of the range.
            pairPeakNits = PAIR_START_NITS + (PAIR_COUNT - 1) * PAIR_STEP_NITS + PAIR_DELTA_NITS;
            pairStatus.setText(getString(R.string.hdr_test_pair_ceiling, pairPeakNits));
        }
        pairStatus.setTextColor(Color.WHITE);
    }

    /** Seek to one comparison and freeze the frame on screen. */
    private void showPairFrame(int index) {
        stopPlayback();
        final long targetUs = index * PAIR_SEGMENT_US;
        new Thread(() -> {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try {
                AssetFileDescriptor afd = getAssets().openFd(PAIR_CLIP);
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
                    postError("no video track in " + PAIR_CLIP);
                    return;
                }
                extractor.selectTrack(track);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL);
                }

                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.configure(format, surfaceView.getHolder().getSurface(), null, 0);
                codec.start();

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean fed = false;
                long deadline = SystemClock.elapsedRealtime() + 3000;

                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!fed) {
                        int inIndex = codec.dequeueInputBuffer(10_000);
                        if (inIndex >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(inIndex);
                            int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                break;
                            }
                            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                            fed = true;
                        }
                    }
                    int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                    if (outIndex >= 0) {
                        // Frames before the target come from the keyframe the seek landed on.
                        boolean reached = info.presentationTimeUs >= targetUs - 1;
                        codec.releaseOutputBuffer(outIndex, reached);
                        if (reached) {
                            break;
                        }
                        fed = false;
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
        }, "hdr-pair-seek").start();
    }

    // ---- ladder playback ---------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (!pairMode) {
            startPlayback();
        }
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
                        long target = firstWall + (info.presentationTimeUs - firstPts) / 1000;
                        long wait = target - SystemClock.elapsedRealtime();
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

        // Display.getBrightnessInfo() and BrightnessInfo are @hide: read reflectively, skipped
        // when unavailable. Losing this line does not invalidate a run.
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
            hintView.setTextColor(Color.WHITE);
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
                ? Math.min(Math.max(pairPeakNits, 300), 4000)
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
        stopPlayback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (surfaceReady && !pairMode) {
            startPlayback();
        }
    }
}
