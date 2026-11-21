package com.sjcam.watch.ui;

/**
 * Anything that can be driven by the rotating crown (or by the volume / dpad
 * keys, which is how some ColorOS Watch builds report the crown).
 *
 * {@code dy > 0} always means "go forward": the content moves up and the next
 * items come into view. The mapping from the raw crown axis to this sign lives
 * in {@link BaseRoundActivity#onCrown(int)}, not here.
 */
public interface ScrollTarget {

    void scrollByPx(int dy);
}
