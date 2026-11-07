package com.limelight.hdr;

import android.view.Surface;

/**
 * Thin bridge to the native PQ renderer.
 *
 * The renderer draws a rectangle at a requested luminance straight into a surface whose colour
 * space is declared as BT2020/PQ, so the value written is a PQ code and any luminance can be
 * produced at runtime. Everything the test page needs - patch size, patch position, the
 * luminance under comparison - is therefore exact by construction instead of being baked into
 * a clip.
 */
public final class HdrCanvas {

    static {
        // The renderer is compiled into moonlight-core: one shared library, already loaded
        // by MoonBridge, and no second packaging path to keep in sync.
        System.loadLibrary("moonlight-core");
    }

    private HdrCanvas() {
    }

    /**
     * Set up the renderer on a surface.
     *
     * @return true when the surface really is in BT2020/PQ; false means the driver lacks
     *         EGL_EXT_gl_colorspace_bt2020_pq and anything drawn here is not HDR.
     */
    public static native boolean nativeInit(Surface surface, int width, int height);

    /** Fill one pixel rectangle with a patch of {@code nits}; the rest stays black. */
    public static native void nativeDraw(int x, int y, int width, int height, float nits);

    /** Two patches in one frame, so both are presented at the same instant. */
    public static native void nativeDrawPair(int x1, int y1, int w1, int h1, float nits1,
                                             int x2, int y2, int w2, int h2, float nits2);

    /** Full-screen checkerboard alternating two luminances, cells of {@code cell} pixels. */
    public static native void nativeDrawGrid(int cell, float nitsA, float nitsB);

    /** Evenly spaced vertical bands, one per luminance, inside the given band. */
    public static native void nativeDrawLadder(int y, int height, float[] nits);

    public static native void nativeRelease();
}
