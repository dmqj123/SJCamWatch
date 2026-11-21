package com.sjcam.watch.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Root container for a round watch face.
 *
 * A rectangle of width W is fully visible inside a circle of diameter D only
 * when its height is at most sqrt(D^2 - W^2). We therefore reserve a centred
 * "safe column" of width 0.74*D, and put ALL interactive widgets inside it so
 * nothing is ever placed in the four cut-off corners.
 *
 * Vertical inset is OPTIONAL (and off by default). Shrinking the height as well
 * leaves a huge empty band above and below the content, which is exactly what
 * broke the history screen: the list only got 65% of the screen height and the
 * top half of the display looked empty. Screens now use the full height and
 * keep their top row narrow enough to fit the arc of the circle themselves
 * (see HistoryActivity's header width calculation).
 *
 * This is deliberately a plain AOSP FrameLayout: OPPO Watch X3 mini runs
 * ColorOS Watch (Android 11), NOT Wear OS, so BoxInsetLayout /
 * WatchViewStub / WearableRecyclerView are unavailable.
 */
public class RoundSafeLayout extends FrameLayout {

    private final int baseLeft;
    private final int baseTop;
    private final int baseRight;
    private final int baseBottom;
    private float safeWidthRatio = 0.74f;
    private boolean verticalInset = false;

    public RoundSafeLayout(Context c) {
        this(c, null);
    }

    public RoundSafeLayout(Context c, AttributeSet a) {
        super(c, a);
        baseLeft = getPaddingLeft();
        baseTop = getPaddingTop();
        baseRight = getPaddingRight();
        baseBottom = getPaddingBottom();
    }

    /** 0.70 keeps a comfortable margin; list screens may relax it to 0.76. */
    public void setSafeWidthRatio(float ratio) {
        safeWidthRatio = Math.max(0.5f, Math.min(0.9f, ratio));
        requestLayout();
    }

    /**
     * When true the content is also limited to the inscribed rectangle height
     * (used by screens whose content must never touch the top/bottom arc).
     */
    public void setVerticalInset(boolean enabled) {
        verticalInset = enabled;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int d = Math.min(w, h);

        int safeW = (int) (d * safeWidthRatio);
        int safeH;
        if (verticalInset) {
            double v = (double) d * d - (double) safeW * safeW;
            safeH = (int) Math.sqrt(Math.max(1.0, v));
        } else {
            safeH = h;
        }

        int padX = Math.max(0, (w - safeW) / 2);
        int padY = Math.max(0, (h - safeH) / 2);

        // setPadding is idempotent because we always start from the captured base.
        setPadding(baseLeft + padX, baseTop + padY, baseRight + padX, baseBottom + padY);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
