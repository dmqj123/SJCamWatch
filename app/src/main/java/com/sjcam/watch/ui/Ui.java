package com.sjcam.watch.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Small helper factory for watch-sized widgets.
 *
 * OPPO Watch X3 mini: 466x466 px @352ppi -> about 212dp x 212dp of logical space.
 * Every widget is therefore kept narrow, uses 10-14sp text and >=44dp touch targets.
 * All UI is built in Java (no XML layouts) so the round safe column can be
 * computed from the real measured screen size at runtime.
 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable rounded(Context c, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    public static TextView label(Context c, String text, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setSingleLine(false);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Compact rectangular button. Height is fixed so it always fits the safe column. */
    public static TextView button(Context c, String text, int bg, int heightDp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Color.WHITE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setMinHeight(dp(c, 44));
        t.setHeight(dp(c, heightDp));
        t.setPadding(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
        t.setBackground(rounded(c, bg, 22));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Circular shutter button. Diameter stays well inside the inscribed circle. */
    public static TextView circleButton(Context c, String text, int bg, int diameterDp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Color.WHITE);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        int d = dp(c, diameterDp);
        t.setMinWidth(d);
        t.setMinHeight(d);
        t.setBackground(oval(bg));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(0, dp(c, 3), 0, dp(c, 3));
        return l;
    }

    public static LinearLayout.LayoutParams lpMatch(int gapDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, gapDp);
        return p;
    }

    public static LinearLayout.LayoutParams lpWrap(int gapDp, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, gapDp);
        return p;
    }

    /** A horizontal row whose children share the narrow safe column equally. */
    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams rowItem(Context c, int gapDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMarginStart(dp(c, gapDp));
        return p;
    }

    /**
     * Scroll container with extra bottom padding and clipToPadding=false:
     * the first/last list rows can be scrolled into the round centre where
     * they are fully visible instead of being cut off by the bezel.
     */
    public static CrownScrollView scroller(Context c, View child, int extraBottomDp) {
        CrownScrollView s = new CrownScrollView(c);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setPadding(0, 0, 0, dp(c, extraBottomDp));
        s.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** Fixed-height transparent gap. */
    public static View spacer(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    public static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }
}
