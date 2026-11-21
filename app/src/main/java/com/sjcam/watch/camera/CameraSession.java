package com.sjcam.watch.camera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide camera connection state.
 *
 * - one single-threaded executor => every CGI call is serialised
 * - 3016 heartbeat every 3.5s while connected (camera drops Wi-Fi otherwise)
 * - three consecutive heartbeat failures => considered disconnected
 */
public final class CameraSession {

    public interface Callback<T> {
        void onResult(T value, String detail);
    }

    private static final CameraSession INSTANCE = new CameraSession();

    public static CameraSession get() {
        return INSTANCE;
    }

    public final CameraHttp http = new CameraHttp();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private volatile String firmware = "";
    private volatile int failures = 0;
    /** Last mode we asked the camera for (0 photo, 1 video, 2 playback). */
    private volatile int mode = CameraCmd.MODE_VIDEO;
    /** True while an RTSP live view is streaming. */
    private volatile boolean liveView = false;

    private CameraSession() {
    }

    public boolean isConnected() {
        return connected;
    }

    public String getFirmware() {
        return firmware;
    }

    public String getHost() {
        return http.getHost();
    }

    /** Try the current Wi-Fi gateway first, then the two known SJCAM defaults. */
    public void check(final Context ctx, final Callback<Boolean> cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                List<String> hosts = new ArrayList<>();
                addOnce(hosts, CameraHttp.detectGateway(ctx));
                addOnce(hosts, CameraHttp.DEFAULT_HOST);
                addOnce(hosts, CameraHttp.ALT_HOST);

                String found = null;
                String fw = "";
                for (String h : hosts) {
                    http.setHost(h);
                    try {
                        String r = http.command(CameraCmd.GET_VERSION, null, null, 2500);
                        if (CameraHttp.isCameraResponse(r)) {
                            found = h;
                            String s = CameraHttp.firstTag(r, "String");
                            fw = s == null ? "" : s;
                            break;
                        }
                    } catch (IOException ignored) {
                        // try next candidate gateway
                    }
                }

                final boolean ok = found != null;
                if (ok) {
                    connected = true;
                    firmware = fw;
                    failures = 0;
                } else {
                    connected = false;
                    firmware = "";
                    http.setHost(CameraHttp.DEFAULT_HOST);
                }
                final String detail = ok ? ("@" + found + (fw.isEmpty() ? "" : " / " + fw))
                        : "未检测到相机热点";
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(ok, detail);
                        if (ok) {
                            startHeartbeat();
                        }
                    }
                });
            }
        });
    }

    /** Lightweight reconnect check used when returning to a screen. */
    public void ensureAlive(final Callback<Boolean> cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    String r = http.command(CameraCmd.HEARTBEAT, null, null, 2500);
                    ok = r != null && !CameraHttp.isHtml(r);
                } catch (IOException ignored) {
                }
                connected = ok;
                final boolean fOk = ok;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fOk, fOk ? "在线" : "连接已断开");
                    }
                });
            }
        });
    }

    /** Generic serial command. Result is delivered on the UI thread. */
    public void exec(final int cmd, final Integer par, final String str,
                     final int timeoutMs, final Callback<String> cb) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String xml = null;
                String error = null;
                try {
                    xml = http.command(cmd, par, str, timeoutMs);
                } catch (IOException e) {
                    error = e.getMessage() == null ? "网络异常" : e.getMessage();
                }
                final String fXml = xml;
                final String fErr = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResult(fXml, fErr);
                    }
                });
            }
        });
    }

    public void startHeartbeat() {
        stopHeartbeat();
        if (liveView) {
            return;
        }
        main.postDelayed(beat, 3500);
    }

    public void stopHeartbeat() {
        main.removeCallbacks(beat);
    }

    /**
     * While the RTSP stream is running the camera is obviously reachable, so the
     * 3016 heartbeat only costs radio wake-ups and HTTP slots that compete with
     * the stream itself. Suspending it is a measurable heat/battery win on a
     * watch; it is resumed as soon as the stream stops.
     */
    public void setLiveView(boolean on) {
        liveView = on;
        if (on) {
            stopHeartbeat();
        } else if (connected) {
            startHeartbeat();
        }
    }

    // -------------------------------------------------------------- work mode

    /**
     * The album is the only place that needs playback mode, but it needs it
     * absolutely: on Novatek firmwares cmd=4001/4002 are only honoured in
     * playback mode, and 3015 itself is more reliable there. Outside it the
     * server ignores the query string and returns the whole file body, which is
     * why every history thumbnail used to come back empty.
     *
     * {@code done} runs on the UI thread whether or not the switch reported
     * success - a firmware that answers -22 while already in playback must not
     * block the list.
     */
    public void enterPlayback(final Runnable done) {
        exec(CameraCmd.MODE_CHANGE, CameraCmd.MODE_PLAYBACK, null, 9000,
                new Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                        mode = CameraCmd.MODE_PLAYBACK;
                        if (done != null) {
                            done.run();
                        }
                    }
                });
    }

    /** Best effort: put the camera back into video mode when leaving the album. */
    public void leavePlayback() {
        if (mode != CameraCmd.MODE_PLAYBACK) {
            return;
        }
        mode = CameraCmd.MODE_VIDEO;
        exec(CameraCmd.MODE_CHANGE, CameraCmd.MODE_VIDEO, null, 9000,
                new Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                    }
                });
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int m) {
        mode = m;
    }

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try {
                        String r = http.command(CameraCmd.HEARTBEAT, null, null, 3000);
                        ok = r != null && !CameraHttp.isHtml(r);
                    } catch (IOException ignored) {
                    }
                    if (ok) {
                        failures = 0;
                    } else {
                        failures++;
                        if (failures >= 3) {
                            connected = false;
                        }
                    }
                }
            });
            // Back off when the device stops answering; stop entirely while a
            // live view is streaming.
            if (!liveView) {
                main.postDelayed(this, connected ? 3500 : 15000);
            }
        }
    };

    private static void addOnce(List<String> list, String v) {
        if (v != null && !v.isEmpty() && !list.contains(v)) {
            list.add(v);
        }
    }
}
