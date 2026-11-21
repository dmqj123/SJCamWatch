package com.sjcam.watch.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Common shell for every screen.
 *
 *  - RoundSafeLayout as root: content limited to the wide part of the circle.
 *  - Crown: intercepted at the activity level (ScrollView/ListView would apply
 *    the desktop mouse-wheel sign and scroll backwards; ListView ignores it
 *    entirely), then forwarded to a {@link ScrollTarget}.
 *  - Back: OPPO's own gesture, a swipe to the right. There is deliberately NO
 *    big on-screen back button any more - it ate a quarter of a 212dp screen
 *    and the stock remit on ColorOS Watch is the swipe.
 */
public abstract class BaseRoundActivity extends Activity {

    protected LinearLayout content;
    protected ScrollView scroller;
    /** Set by the screen: what the crown / volume keys scroll. */
    protected ScrollTarget scrollTarget;

    private float downX;
    private float downY;
    private boolean backSwiped;
    private Toast toast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildRoundShell();
    }

    /** Width of the usable column as a fraction of the screen diameter. */
    protected float shellRatio() {
        return 0.74f;
    }

    /** List screens scroll themselves, so they opt out of the wrapping ScrollView. */
    protected boolean shellScrollable() {
        return true;
    }

    /**
     * True keeps the content inside the inscribed rectangle (short, centred
     * screens). False (default) uses the full height and lets the screen manage
     * its own top spacing - required for "header on top, list below" layouts.
     */
    protected boolean shellVerticalInset() {
        return false;
    }

    protected void buildRoundShell() {
        RoundSafeLayout root = new RoundSafeLayout(this);
        root.setSafeWidthRatio(shellRatio());
        root.setVerticalInset(shellVerticalInset());
        root.setBackgroundColor(Color.parseColor("#FF080A0E"));

        content = Ui.column(this);
        android.widget.FrameLayout.LayoutParams fill =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);

        if (shellScrollable()) {
            CrownScrollView sv = Ui.scroller(this, content, 26);
            scroller = sv;
            scrollTarget = sv;
            root.addView(sv, fill);
        } else {
            root.addView(content, fill);
        }
        setContentView(root);
    }

    // ------------------------------------------------------------------ crown

    /**
     * The crown arrives either as ACTION_SCROLL (AXIS_VSCROLL / AXIS_SCROLL) or,
     * on some ColorOS builds, as volume / dpad key events. Both are handled here
     * so the watch behaves the same no matter how it reports the rotation.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0f) {
                v = ev.getAxisValue(MotionEvent.AXIS_SCROLL);
            }
            if (v != 0f) {
                onCrown(v > 0 ? 1 : -1);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (scrollTarget != null) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    onCrown(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    onCrown(-1);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /** @param dir +1 = next page of content, -1 = previous. */
    protected void onCrown(int dir) {
        if (scrollTarget == null) {
            return;
        }
        scrollTarget.scrollByPx(dir * CrownDirection.sign(this) * Ui.dp(this, 30));
    }

    /** Long press on the given view flips the crown direction, persisted. */
    protected void enableDirectionToggle(View anchor) {
        anchor.setLongClickable(true);
        anchor.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                int s = CrownDirection.toggle(BaseRoundActivity.this);
                toast(s > 0 ? "滚动方向 1（默认）" : "滚动方向 2（已反转）");
                return true;
            }
        });
    }

    // ------------------------------------------------------------------- back

    /**
     * Swipe right anywhere to go back, as ColorOS Watch does natively. A swipe
     * starting near the left edge needs less distance, so it feels like the
     * system gesture instead of a full-width drag.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            backSwiped = false;
        } else if (backSwiped) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                backSwiped = false;
            }
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            float w = getResources().getDisplayMetrics().widthPixels;
            float threshold = w * (downX < w * 0.25f ? 0.16f : 0.32f);
            if (dx > threshold && dx > Math.abs(dy) * 1.5f) {
                backSwiped = true;
                onBackPressed();
                return true;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- helpers

    protected void toast(String msg) {
        if (toast != null) {
            toast.cancel();
        }
        toast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
        toast.show();
    }

    protected void runOnMain(Runnable r) {
        runOnUiThread(r);
    }

    protected Context ctx() {
        return getApplicationContext();
    }

    /** Height of the screen, in pixels. */
    protected int screenDp() {
        return Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
    }

    /**
     * A block that is safe near the very top of the circle: the visible width at
     * the top edge of a row placed at {@code topPx} is 2*sqrt(R^2-(R-topPx)^2).
     * Returns the largest width (px) that fits when the block occupies
     * [topPx, topPx+heightPx], minus a small margin.
     */
    protected int fitWidthAt(int topPx, int heightPx) {
        int d = screenDp();
        double r = d / 2.0;
        int worst = topPx; // narrowest point of a top-aligned block is its top edge
        double dy = Math.max(0, r - worst);
        double half = Math.sqrt(Math.max(0, r * r - dy * dy));
        int w = (int) (half * 2) - Ui.dp(this, 4);
        return Math.max(Ui.dp(this, 80), Math.min(d, w));
    }

    protected TextView titleLabel(String text) {
        TextView t = Ui.label(this, text, 13, Color.WHITE, true);
        return t;
    }

    /** Small centred pill of text, used for status / hint lines. */
    protected TextView hintLabel(String text, float sizeSp) {
        return Ui.label(this, text, sizeSp, Color.parseColor("#FF93A0B4"), false);
    }

    protected LinearLayout.LayoutParams lpTop(int gapDp) {
        return Ui.lpMatch(gapDp, this);
    }

    protected LinearLayout.LayoutParams lpFixed(int widthDp, int heightDp, int gapDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                Ui.dp(this, widthDp), Ui.dp(this, heightDp));
        p.topMargin = Ui.dp(this, gapDp);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }
}
