package com.sjcam.watch.camera;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One entry returned by cmd=3015. */
public final class MediaFile {

    public static final int TYPE_PHOTO = 0;
    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_OTHER = 2;

    public final String devicePath;
    public final String name;
    public final int type;
    public final long time;
    public final String timeText;
    public final String dateText;
    public final String url;
    public final String thumb;

    /** -1 = idle, 0..100 = downloading, 101 = done, -2 = failed. */
    public volatile int progress = -1;
    public volatile boolean downloading = false;

    public MediaFile(String devicePath, String name, int type, long time,
                     String timeText, String dateText, String url, String thumb) {
        this.devicePath = devicePath;
        this.name = name;
        this.type = type;
        this.time = time;
        this.timeText = timeText;
        this.dateText = dateText;
        this.url = url;
        this.thumb = thumb;
    }

    public boolean isPhoto() {
        return type == TYPE_PHOTO;
    }

    public boolean isVideo() {
        return type == TYPE_VIDEO;
    }

    public String shortName() {
        return name.length() > 18 ? name.substring(0, 16) + "…" : name;
    }

    public String mime() {
        if (isVideo()) {
            return "video/mp4";
        }
        if (isPhoto()) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    static MediaFile create(CameraHttp http, String path, int order) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String upper = name.toUpperCase(Locale.US);
        int type;
        if (upper.endsWith(".JPG") || upper.endsWith(".JPEG") || upper.endsWith(".PNG")) {
            type = TYPE_PHOTO;
        } else if (upper.endsWith(".MP4") || upper.endsWith(".MOV") || upper.endsWith(".TS")
                || upper.endsWith(".AVI")) {
            type = TYPE_VIDEO;
        } else {
            type = TYPE_OTHER;
        }

        long t = parseTime(name);
        if (t <= 0) {
            // Unknown time: keep original list order, but place these after
            // files whose filenames contain a real timestamp.
            t = -1L - order;
        }
        String timeText;
        String dateText;
        if (t > 0) {
            timeText = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
            dateText = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(t));
        } else {
            timeText = "时间未知";
            dateText = "未标日期";
        }
        String url = http.fileUrl(path);
        return new MediaFile(path, name, type, t, timeText, dateText, url, http.thumbUrl(url));
    }

    /** Handles 20260901194216_000002.TS / SJ_20260901_194216.JPG styles. */
    private static long parseTime(String name) {
        Matcher m = Pattern.compile("(\\d{14})").matcher(name);
        if (m.find()) {
            long v = toMillis(m.group(1), "yyyyMMddHHmmss");
            if (v > 0) {
                return v;
            }
        }
        m = Pattern.compile("(\\d{8})[_\\-](\\d{6})").matcher(name);
        if (m.find()) {
            long v = toMillis(m.group(1) + m.group(2), "yyyyMMddHHmmss");
            if (v > 0) {
                return v;
            }
        }
        return -1;
    }

    private static long toMillis(String digits, String pattern) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setLenient(false);
            return f.parse(digits).getTime();
        } catch (Exception e) {
            return -1;
        }
    }
}
