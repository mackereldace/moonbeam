package com.limelight.binding.video;

/**
 * Told when the decoded video's resolution changes.
 *
 * The host can change the encoded resolution in the middle of a session - switching the virtual
 * display's mode does exactly that - and after that the picture no longer has the aspect ratio the
 * stream was started with. Nothing on screen would say so: the decoder simply starts producing a
 * different size, the surface stretches it into the old view, and every tap lands where the host
 * expects it to but not where the pixels suggest.
 */
public interface ResolutionListener {
    void onResolutionChanged(int width, int height);
}
