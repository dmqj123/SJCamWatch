package com.sjcam.watch.camera;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

/**
 * Live view panel.
 *
 * Original app used ijkplayer on the RTSP stream; we only have platform
 * MediaPlayer, so the whole design is about staying inside what a wearable can
 * actually sustain:
 *
 *  1. SurfaceView, never TextureView. A TextureView has to be copied into the
 *     GPU texture pipeline and composited with the window every frame, which on
 *     a watch is a constant CPU+GPU load; a SurfaceView is a hardware overlay
 *     and costs almost nothing beyond the decoder itself.
 *  2. Low-resolution sub-stream FIRST (rtsp://host/novatek/sub), main stream
 *     only as a fallback. Decoding 1080p+ to fill a 326 px panel was the main
 *     reason the preview was both laggy and hot.
 *  3. Audio muted. Live view has no use for it and it removes one full decode
 *     path from the pipeline.
 *  4. The 3016 heartbeat is suspended while streaming (the RTSP session keeps
 *     the AP awake by itself) and no HTTP polling runs while frames arrive, so
 *     the radio is not being woken by two sources at once.
 *  5. A WifiLock during playback. Without it the Wi-Fi stack enters power save
 *     between RTSP packets, which shows up as 1-2 s of extra latency.
 *  6. Hard timeouts: if no stream prepares within PREPARE_TIMEOUT the stream is
 *     switched off again (2015 par=0) so the camera stops encoding - otherwise
 *     a failed attempt leaves both ends burning power.
 */
public final class PreviewController {

    public interface Listener {
        void onInfo(String text);
    }

    /** Give up on a URL that never reaches onPrepared. */
    private static final int PREPARE_TIMEOUT = 9000;
    /** Status polling only runs when the stream is unavailable. */
    private static final int FALLBACK_POLL_MS = 6000;

    private final CameraSession session;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SurfaceView view;
    private SurfaceHolder holder;
    private Listener listener;
    private WifiManager.WifiLock wifiLock;

    private MediaPlayer player;
    private boolean enabled;
    private boolean surfaceReady;
    private boolean playing;
    private boolean fallback;
    private int urlIndex;
    private String[] urls;

    public PreviewController(CameraSession session) {
        this.session = session;
    }

    public void attach(Context ctx, SurfaceView sv, Listener l) {
        view = sv;
        holder = sv.getHolder();
        listener = l;
        sv.setVisibility(android.view.View.GONE);
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                if (enabled) {
                    startPlayer();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                stopPlayer();
            }
        });
        acquireLock(ctx);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        if (!on) {
            stopPlayer();
            main.removeCallbacks(fallbackPoll);
            main.removeCallbacks(retry);
            fallback = false;
            session.setLiveView(false);
            releaseLock();
            if (view != null) {
                view.setVisibility(android.view.View.GONE);
            }
            session.exec(CameraCmd.LIVE_VIEW, CameraCmd.LIVE_OFF, null, 8000,
                    new CameraSession.Callback<String>() {
                        @Override
                        public void onResult(String v, String e) {
                        }
                    });
            info("预览已关闭");
            return;
        }

        // Sub-stream first: it is usually 640x360 and decodes in real time.
        urls = new String[]{session.http.rtspUrlSub(), session.http.rtspUrl(),
                session.http.rtspUrlLegacy()};
        urlIndex = 0;
        fallback = false;
        acquireLockIfNeeded();
        // The RTSP session keeps the AP alive; 3016 on top of it only wakes the
        // radio and steals HTTP slots.
        session.setLiveView(true);
        if (view != null) {
            view.setVisibility(android.view.View.VISIBLE);
        }
        info("正在打开预览…");
        session.exec(CameraCmd.LIVE_VIEW, CameraCmd.LIVE_ON, null, 8000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String v, String e) {
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                startPlayer();
                            }
                        }, 300);
                    }
                });
    }

    private void startPlayer() {
        if (!enabled || !surfaceReady || holder == null || player != null || urls == null) {
            return;
        }
        if (urlIndex >= urls.length) {
            enterFallback("实时流不可用");
            return;
        }
        final String url = urls[urlIndex];
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        playing = false;
        try {
            mp.setDataSource(url);
            mp.setSurface(holder.getSurface());
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    if (player != p || !enabled) {
                        return;
                    }
                    main.removeCallbacks(watchdog);
                    playing = true;
                    info(urlIndex == 0 ? "预览中 · 低码率" : "预览中 · 主码流");
                    try {
                        p.start();
                    } catch (IllegalStateException ignored) {
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    if (player == p) {
                        stopPlayer();
                        nextUrl();
                    }
                    return true;
                }
            });
            mp.prepareAsync();
            main.postDelayed(watchdog, PREPARE_TIMEOUT);
        } catch (IOException e) {
            stopPlayer();
            nextUrl();
        } catch (IllegalStateException e) {
            stopPlayer();
            nextUrl();
        }
    }

    private void nextUrl() {
        if (!enabled) {
            return;
        }
        urlIndex++;
        if (urlIndex < urls.length) {
            main.removeCallbacks(retry);
            main.postDelayed(retry, 250);
        } else {
            enterFallback("实时流不可用");
        }
    }

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            startPlayer();
        }
    };

    /** Fires when a URL never prepared: drop it and try the next one. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (enabled && !playing) {
                stopPlayer();
                nextUrl();
            }
        }
    };

    /**
     * No stream at all. Switching 2015 off is important: leaving it on keeps
     * the camera encoder running for nobody.
     */
    private void enterFallback(String why) {
        if (!enabled) {
            return;
        }
        fallback = true;
        stopPlayer();
        session.exec(CameraCmd.LIVE_VIEW, CameraCmd.LIVE_OFF, null, 6000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String v, String e) {
                    }
                });
        session.setLiveView(false);
        info(why + "，显示相机状态");
        main.removeCallbacks(fallbackPoll);
        main.postDelayed(fallbackPoll, 800);
    }

    /** Text panel: the only thing left when the platform has no RTSP support. */
    private final Runnable fallbackPoll = new Runnable() {
        @Override
        public void run() {
            if (!enabled || !fallback) {
                return;
            }
            session.exec(CameraCmd.RECORD_TIME, null, null, 6000,
                    new CameraSession.Callback<String>() {
                        @Override
                        public void onResult(String xml, String error) {
                            if (!enabled || !fallback) {
                                return;
                            }
                            String v = CameraHttp.firstTag(xml, "Value");
                            if (error != null || v == null) {
                                info("相机状态：等待响应");
                            } else {
                                info("录制时长 " + v + " s");
                            }
                            main.postDelayed(fallbackPoll, FALLBACK_POLL_MS);
                        }
                    });
        }
    };

    private void stopPlayer() {
        main.removeCallbacks(watchdog);
        main.removeCallbacks(retry);
        playing = false;
        if (player != null) {
            try {
                player.reset();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void info(String s) {
        if (listener != null) {
            listener.onInfo(s);
        }
    }

    // ------------------------------------------------------------ wifi lock

    private void acquireLock(Context ctx) {
        if (wifiLock != null) {
            return;
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return;
            }
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "sjcam:liveview");
            wifiLock.setReferenceCounted(false);
        } catch (Exception ignored) {
        }
    }

    private void acquireLockIfNeeded() {
        try {
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    public void release() {
        if (enabled) {
            setEnabled(false);
        }
        enabled = false;
        main.removeCallbacks(fallbackPoll);
        main.removeCallbacks(retry);
        stopPlayer();
        releaseLock();
        view = null;
        holder = null;
    }
}
