package com.sjcam.watch.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Scroll direction for the crown, in one place and adjustable at runtime.
 *
 * Default is +1: a positive AXIS_VSCROLL (clockwise) moves the content up, i.e.
 * forward - the same convention Wear OS uses for RotaryEncoder. AOSP's
 * ScrollView/ListView do the opposite (they treat the crown like a mouse wheel),
 * which is what made the list scroll backwards on this watch.
 *
 * Because different ColorOS builds can report the axis either way, the value is
 * persisted and can be flipped with a long press on the history header, so a
 * wrong guess is a two-second fix on the device instead of a rebuild.
 */
public final class CrownDirection {

    private static final String PREFS = "sjcam_crown";
    private static final String KEY_SIGN = "sign";

    private CrownDirection() {
    }

    public static int sign(Context c) {
        return prefs(c).getInt(KEY_SIGN, 1) >= 0 ? 1 : -1;
    }

    /** @return the new sign. */
    public static int toggle(Context c) {
        int next = -sign(c);
        prefs(c).edit().putInt(KEY_SIGN, next).apply();
        return next;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
