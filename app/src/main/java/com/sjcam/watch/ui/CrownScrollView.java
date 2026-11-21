package com.sjcam.watch.ui;

import android.content.Context;
import android.widget.ScrollView;

/**
 * ScrollView that can be driven by the crown.
 *
 * AOSP's ScrollView turns a crown turn into a MOUSE WHEEL scroll: it negates
 * AXIS_VSCROLL ("wheel up" == scroll up). A watch crown reports clockwise as a
 * positive axis, so the stock behaviour scrolls the wrong way — that is the
 * inverted scrolling bug. The activity intercepts the event before views see
 * it and calls {@link #scrollByPx(int)} with an explicit, already-correct sign.
 */
public class CrownScrollView extends ScrollView implements ScrollTarget {

    public CrownScrollView(Context c) {
        super(c);
    }

    @Override
    public void scrollByPx(int dy) {
        scrollBy(0, dy);
    }
}
