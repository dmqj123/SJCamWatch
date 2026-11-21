package com.sjcam.watch.camera;

import android.media.MediaMetadataRetriever;

/**
 * Reads only the container header of a remote file - never decodes a frame.
 *
 * Why this exists: the watch panel is 466x466 px. A 4K clip is 30x more pixels
 * than the screen and 4-8x more than the SoC decoder can sustain, so handing
 * such a URL to MediaPlayer produces either an immediate error or a decoder
 * stuck at 2 fps (which is exactly what makes the watch hot). Probing first
 * lets the UI show the poster frame and refuse to start the decoder.
 *
 * The retriever walks the container sequentially, so it is fast when "moov" is
 * near the front (.TS always is, .MP4 usually is) and can be slow when the
 * index sits at the end. Callers run it on a worker thread and ignore late
 * results.
 */
public final class VideoProbe {

    /** Long edge the watch decoder can comfortably sustain. */
    public static final int MAX_LONG_SIDE = 1920;
    /** 1920x1080 - above this the frame rate collapses on a wearable SoC. */
    public static final long MAX_PIXELS = 1920L * 1080L;

    public static final class Info {
        public final int width;
        public final int height;
        public final int rotation;
        public final long durationMs;

        Info(int width, int height, int rotation, long durationMs) {
            this.width = width;
            this.height = height;
            this.rotation = rotation;
            this.durationMs = durationMs;
        }

        public String label() {
            return width + "×" + height;
        }

        /** False means "do not start a decoder for this file". */
        public boolean watchCanDecode() {
            if (width <= 0 || height <= 0) {
                return false;
            }
            int longSide = Math.max(width, height);
            return longSide <= MAX_LONG_SIDE && (long) width * height <= MAX_PIXELS;
        }
    }

    private VideoProbe() {
    }

    /** @return null when the header cannot be read (unsupported container, timeout, offline). */
    public static Info probe(String url) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(url);
            int w = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            long dur = longOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (w <= 0 || h <= 0) {
                return null;
            }
            // Metadata reports the stored frame; a 90/270 flag means the viewer
            // sees the short edge horizontally, which does not change the area.
            return new Info(w, h, rot, dur);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int intOf(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longOf(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
