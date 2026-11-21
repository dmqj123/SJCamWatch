package com.sjcam.watch.camera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Gesture extension point (intentionally NOT wired into the UI yet).
 *
 * Finding: OPPO's open platform documents the X3 mini's "double finger tap"
 * gesture, but it is a SYSTEM-level capability (opens Breeno / answers calls /
 * skips tracks). There is no public third-party gesture SDK or API exposed in
 * the OPPO Watch application-cooperation documentation, and it lists only an
 * accelerometer/gyroscope/magnetometer/barometer/PPG set. The side function
 * key is explicitly "not open to applications".
 *
 * So we leave a clean Controller interface here. When OPPO publishes an SDK
 * (or grants access to the system gesture), implement Controller and call
 * CaptureActivity#bindGesture(). A standard-sensor fallback is provided below
 * as ShakeDetector; it is disabled by default to avoid accidental shutters.
 */
public final class GestureSupport {

    private GestureSupport() {
    }

    public interface Listener {
        void onShutter();

        void onToggleMode();
    }

    public interface Controller {
        void start(Context ctx, Listener listener);

        void stop();
    }

    /** Two quick wrist shakes within 800 ms => shutter. Uses only AOSP sensors. */
    public static final class ShakeDetector implements Controller, SensorEventListener {

        private static final float THRESHOLD = 13.5f;
        private SensorManager manager;
        private Listener listener;
        private long lastShake;

        @Override
        public void start(Context ctx, Listener l) {
            stop();
            listener = l;
            manager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (manager == null) {
                return;
            }
            Sensor s = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (s != null) {
                manager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
            }
        }

        @Override
        public void stop() {
            if (manager != null) {
                try {
                    manager.unregisterListener(this);
                } catch (Exception ignored) {
                }
                manager = null;
            }
            listener = null;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float g = (float) Math.sqrt(x * x + y * y + z * z) - 9.81f;
            if (Math.abs(g) > THRESHOLD) {
                long now = System.currentTimeMillis();
                if (now - lastShake < 800 && listener != null) {
                    listener.onShutter();
                    lastShake = 0;
                } else {
                    lastShake = now;
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }
}
