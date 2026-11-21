package com.sjcam.watch.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.sjcam.watch.camera.CameraHttp;
import com.sjcam.watch.camera.MediaFile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async thumbnail loader.
 *
 * Why three sources: cmd=4001 (embedded JPEG, ~28 KB) is the cheapest path but
 * the Novatek server only honours it IN PLAYBACK MODE - otherwise it silently
 * ignores the query string and returns the whole file body, which is not a JPEG
 * and used to leave every tile empty. 4002 (screen-sized JPEG) behaves the same
 * way, so the loader falls back photo -> 4002 -> raw file, and gives up on 4001
 * after a few failures so a firmware that does not support it is not hammered
 * once per tile.
 *
 * Every transfer holds CameraHttp's serial lock, and there is exactly ONE worker
 * thread: a second thread would only block on that lock while the camera can
 * serve a single connection anyway.
 */
public final class ThumbnailLoader {

    /** A 4001 thumbnail is ~28 KB; anything past 1 MB is the video body. */
    private static final int MAX_THUMB_BYTES = 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 3 * 1024 * 1024;
    /** Stop asking for 4001 after this many misses and go straight to 4002. */
    private static final int GIVE_UP_AFTER = 3;

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraHttp http;

    private volatile int thumbFailures;
    private Bitmap glyphPhoto;
    private Bitmap glyphVideo;

    public ThumbnailLoader(CameraHttp http) {
        this.http = http;
        cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public void load(final MediaFile f, final ImageView target) {
        load(f, target, 160);
    }

    public void load(final MediaFile f, final ImageView target, final int maxPx) {
        if (f == null || target == null || f.url == null || f.url.isEmpty()) {
            return;
        }
        final String key = maxPx + "|" + f.url;
        target.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        // A neutral film/photo glyph instead of a plain hole, so a tile that is
        // still loading (or whose thumbnail the firmware cannot produce) looks
        // intentional rather than broken.
        target.setImageBitmap(glyph(f.isVideo()));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = fetch(f, maxPx);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!key.equals(target.getTag())) {
                            return; // view was recycled
                        }
                        if (b != null) {
                            cache.put(key, b);
                            target.setImageBitmap(b);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ fetch

    private Bitmap fetch(MediaFile f, int maxPx) {
        // 1) firmware thumbnail - smallest and fastest when it works.
        if (thumbFailures < GIVE_UP_AFTER) {
            Bitmap b = decode(read(f.thumb, MAX_THUMB_BYTES, 6000), maxPx);
            if (b != null) {
                thumbFailures = 0;
                return b;
            }
            thumbFailures++;
        }
        // 2) screen-sized JPEG (works for both photos and the first video frame).
        Bitmap b = decode(read(http.previewImageUrl(f.url), MAX_PREVIEW_BYTES, 9000), maxPx);
        if (b != null) {
            return b;
        }
        // 3) last resort for photos: the original file itself.
        if (f.isPhoto()) {
            return decode(read(f.url, 4 * 1024 * 1024, 12000), maxPx);
        }
        return null;
    }

    private byte[] read(String url, int cap, int timeoutMs) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return http.getBytes(url, timeoutMs, cap);
        } catch (Exception e) {
            return null;
        }
    }

    /** Rejects the video body that the server sends when the query is ignored. */
    private static boolean isImage(byte[] d) {
        if (d == null || d.length < 512) {
            return false;
        }
        if ((d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8 && (d[2] & 0xff) == 0xFF) {
            return true; // JPEG
        }
        return (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
    }

    private static Bitmap decode(byte[] data, int maxPx) {
        if (!isImage(data)) {
            return null;
        }
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (sample < 16 && (probe.outWidth / sample > maxPx
                    || probe.outHeight / sample > maxPx)) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------------- glyphs

    private Bitmap glyph(boolean video) {
        Bitmap b = video ? glyphVideo : glyphPhoto;
        if (b != null) {
            return b;
        }
        b = drawGlyph(video);
        if (video) {
            glyphVideo = b;
        } else {
            glyphPhoto = b;
        }
        return b;
    }

    /** Small outlined icon drawn once and reused by every empty tile. */
    private static Bitmap drawGlyph(boolean video) {
        int size = 64;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setColor(Color.parseColor("#FF45505F"));
        c.drawRoundRect(new RectF(10, 16, 54, 48), 7, 7, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#FF4A5666"));
        if (video) {
            Path tri = new Path();
            tri.moveTo(27, 23);
            tri.lineTo(43, 32);
            tri.lineTo(27, 41);
            tri.close();
            c.drawPath(tri, p);
        } else {
            c.drawCircle(32, 32, 8f, p);
        }
        return bmp;
    }

    public void clear() {
        cache.evictAll();
    }
}
