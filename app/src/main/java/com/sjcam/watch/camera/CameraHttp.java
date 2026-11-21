package com.sjcam.watch.camera;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thin HTTP/CGI client for the SJCAM camera Wi-Fi AP.
 *
 * The camera runs a single-threaded HTTP server. A ReentrantLock wraps EVERY
 * request (including thumbnails and file downloads) so nothing is ever sent
 * concurrently; this is the single most important compatibility rule.
 */
public final class CameraHttp {

    public static final String DEFAULT_HOST = "192.168.1.254";
    public static final String ALT_HOST = "192.168.8.1";

    /** Progress callback used by the downloader. */
    public interface Progress {
        void onProgress(long done, long total);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String host = DEFAULT_HOST;

    public void setHost(String h) {
        if (h != null && !h.trim().isEmpty()) {
            host = h.trim();
        }
    }

    public String getHost() {
        return host;
    }

    public String hostUrl() {
        return "http://" + host + "/";
    }

    public String commandUrl(int cmd, Integer par, String str) {
        StringBuilder b = new StringBuilder(hostUrl());
        b.append("?custom=").append(CameraCmd.CUSTOM).append("&cmd=").append(cmd);
        if (par != null) {
            b.append("&par=").append(par);
        }
        if (str != null) {
            b.append("&str=").append(encode(str));
        }
        return b.toString();
    }

    public String command(int cmd, Integer par, String str, int timeoutMs) throws IOException {
        return get(commandUrl(cmd, par, str), timeoutMs);
    }

    /**
     * 3015 returns device paths like {@code A:\CARDV\MOVIE\x.TS}.
     * Strip the drive letter, switch slashes, and serve from the gateway root.
     */
    public String fileUrl(String devicePath) {
        String p = devicePath == null ? "" : devicePath.trim();
        int colon = p.indexOf(':');
        if (colon >= 0 && colon <= 2) {
            p = p.substring(colon + 1);
        }
        p = p.replace('\\', '/');
        while (p.startsWith("//")) {
            p = p.substring(1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return "http://" + host + p;
    }

    /** The firmware returns an embedded JPEG when 4001 is appended to a file URL. */
    public String thumbUrl(String fileUrl) {
        return fileUrl + "?custom=1&cmd=" + CameraCmd.THUMBNAIL;
    }

    /** 4002 asks for a screen-sized JPEG of the shot, much cheaper than the raw file. */
    public String previewImageUrl(String fileUrl) {
        return fileUrl + "?custom=1&cmd=" + CameraCmd.PREVIEW_IMAGE;
    }

    /**
     * Novatek exposes TWO RTSP endpoints: the main stream (full sensor
     * resolution) and a low-bitrate sub-stream meant for phone preview. Only the
     * sub-stream is affordable on a 466 px watch panel - decoding the main
     * stream is what made the preview lag behind and heat the case.
     */
    public String rtspUrlSub() {
        return "rtsp://" + host + "/novatek/sub";
    }

    /** Novatek firmware's documented main stream. */
    public String rtspUrl() {
        return "rtsp://" + host + "/novatek/main";
    }

    /** Older SJCAM firmware path, kept as the last fallback. */
    public String rtspUrlLegacy() {
        return "rtsp://" + host + ":554/xxx.mp4";
    }

    public String get(String url, int timeoutMs) throws IOException {
        byte[] data = getBytes(url, timeoutMs);
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return new String(data);
        }
    }

    public byte[] getBytes(String url, int timeoutMs) throws IOException {
        return getBytes(url, timeoutMs, 4 * 1024 * 1024);
    }

    /** Same, with an explicit cap: full-size photos can exceed the default 4 MB. */
    public byte[] getBytes(String url, int timeoutMs, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        streamTo(url, timeoutMs, bos, null, maxBytes);
        return bos.toByteArray();
    }

    /**
     * Streams a response into {@code out}. Holds the serial lock for the whole
     * transfer, because the camera cannot serve two connections at once.
     */
    public long streamTo(String url, int timeoutMs, OutputStream out,
                         Progress progress, int maxBytes) throws IOException {
        lock.lock();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("Accept", "*/*");
            try {
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                long total = contentLength(c);
                InputStream in = c.getInputStream();
                try {
                    byte[] buf = new byte[16 * 1024];
                    long done = 0;
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (maxBytes > 0 && done > maxBytes) {
                            throw new IOException("响应过大");
                        }
                        if (progress != null && (done - lastReport > 64 * 1024 || n < 0)) {
                            lastReport = done;
                            progress.onProgress(done, total);
                        }
                    }
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                    out.flush();
                    return done;
                } finally {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                c.disconnect();
            }
        } finally {
            lock.unlock();
        }
    }

    private static long contentLength(HttpURLConnection c) {
        try {
            String v = c.getHeaderField("Content-Length");
            return v == null ? -1L : Long.parseLong(v.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------- minimal XML helpers (the camera replies are tiny XML fragments) ----------

    public static String firstTag(String xml, String tag) {
        List<String> v = allTags(xml, tag);
        return v.isEmpty() ? null : v.get(0);
    }

    public static List<String> allTags(String xml, String tag) {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Pattern p = Pattern.compile("<" + tag + "\\b[^>]*>\\s*([^<]*?)\\s*</" + tag + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        while (m.find()) {
            String v = m.group(1) == null ? "" : m.group(1).trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    public static int status(String xml) {
        String s = firstTag(xml, "Status");
        if (s == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** An HTML page here means a captive portal / IP conflict, not a camera. */
    public static boolean isHtml(String xml) {
        if (xml == null) {
            return false;
        }
        String s = xml.toLowerCase(Locale.US);
        return s.contains("<html") || s.contains("<head") || s.contains("<!doctype html");
    }

    public static boolean isCameraResponse(String xml) {
        if (xml == null || isHtml(xml)) {
            return false;
        }
        String s = xml.toLowerCase(Locale.US);
        return s.contains("<function") || s.contains("<status") || s.contains("<fpath")
                || s.contains("<cmd") || s.contains("<string") || s.contains("<value");
    }

    /** Read the actual Wi-Fi gateway so non-default camera models also work. */
    public static String detectGateway(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) {
                return null;
            }
            int g = d.gateway;
            return (g & 0xff) + "." + ((g >> 8) & 0xff) + "."
                    + ((g >> 16) & 0xff) + "." + ((g >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }
}
