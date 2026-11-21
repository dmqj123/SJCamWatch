package com.sjcam.watch;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraCmd;
import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.camera.MediaFile;
import com.sjcam.watch.camera.MediaRepository;
import com.sjcam.watch.camera.VideoProbe;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.Ui;

/**
 * Full-screen preview of one history item.
 *
 * Photos: cmd=4002 screen-sized JPEG, decoded with inSampleSize so a 12 MP file
 * never allocates a 48 MB bitmap.
 *
 * Videos: the camera records 2.7K/4K, which a wearable SoC cannot decode and
 * cannot even stream (the Wi-Fi AP tops out well below the clip's bitrate).
 * Handing such a URL straight to MediaPlayer produced either an error or a
 * decoder crawling at a few fps - the latter is what cooks the watch. So the
 * order is inverted:
 *
 *   1. fetch the poster frame first (4002 -> 4001 -> raw file). The user sees
 *      the content immediately instead of a black box.
 *   2. probe the container header only (no decoding). If the frame is larger
 *      than 1920x1080 the decoder is never started - the poster stays and the
 *      download button is offered instead.
 *   3. if playback does start, two watchdogs bound the damage: one for "never
 *      prepared", one for "playing but position is stuck", both of which end the
 *      MediaPlayer instead of letting it spin.
 */
public class PreviewActivity extends BaseRoundActivity {

    private static final String E_URL = "url";
    private static final String E_THUMB = "thumb";
    private static final String E_NAME = "name";
    private static final String E_TYPE = "type";

    /** Give up when the player neither prepares nor advances in this window. */
    private static final int PREPARE_TIMEOUT = 10000;
    /** Two seconds per sample, three identical samples = 6 s without progress. */
    private static final int STALL_LIMIT = 3;

    public static void open(Context from, MediaFile f) {
        Intent i = new Intent(from, PreviewActivity.class);
        i.putExtra(E_URL, f.url);
        i.putExtra(E_THUMB, f.thumb);
        i.putExtra(E_NAME, f.name);
        i.putExtra(E_TYPE, f.type);
        from.startActivity(i);
    }

    private final CameraSession session = CameraSession.get();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaFile file;

    /** Centred "loading" hint (photos) and playback hints (videos). */
    private TextView note;
    /** Bottom caption: resolution, or why the video is not being decoded. */
    private TextView caption;
    private TextView downloadBtn;
    private TextView status;

    private ImageView poster;
    private SurfaceView videoSurface;
    private MediaPlayer player;
    private boolean playing;
    private boolean videoTooBig;
    private int lastPos = -1;
    private int stalled;
    private boolean downloading;

    @Override
    protected float shellRatio() {
        return 0.94f;
    }

    @Override
    protected boolean shellScrollable() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        file = new MediaFile("", stringExtra(E_NAME), intExtra(E_TYPE),
                0L, "", "", stringExtra(E_URL), stringExtra(E_THUMB));

        int d = screenDp();
        int box = (int) (d * 0.70f);

        FrameLayout stage = new FrameLayout(this);
        content.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        note = Ui.label(this, "载入中…", 10, Color.parseColor("#FF93A0B4"), false);
        stage.addView(note, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(box, box, Gravity.CENTER);

        if (file.isPhoto()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            stage.addView(iv, mp);
            loadPhoto(iv);
        } else {
            poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            stage.addView(poster, mp);
            // Escape hatch: a few wearable SoCs do have a 4K decoder, so a long
            // press on the poster forces playback anyway. The stall watchdog
            // still stops it after 6 s without progress.
            poster.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (!videoTooBig) {
                        return false;
                    }
                    videoTooBig = false;
                    caption.setText("正在尝试强制播放…");
                    if (videoSurface != null) {
                        videoSurface.setVisibility(View.VISIBLE);
                        if (videoSurface.getHolder().getSurface() != null
                                && videoSurface.getHolder().getSurface().isValid()) {
                            startVideo(videoSurface.getHolder());
                        }
                    }
                    return true;
                }
            });

            videoSurface = new SurfaceView(this);
            videoSurface.setVisibility(View.GONE);
            stage.addView(videoSurface, mp);
            videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    startVideo(holder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    stopVideo();
                }
            });

            loadVideoPoster();
            probeVideo();
        }
        note.bringToFront();

        // Bottom caption: two short lines above the buttons.
        caption = Ui.label(this, "", 9, Color.parseColor("#FF9AA6B8"), false);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = Ui.dp(this, 52);
        stage.addView(caption, cp);

        // Progress / hint line, just above the buttons.
        status = Ui.label(this, "", 9, Color.parseColor("#FF93A0B4"), false);
        status.setSingleLine(true);
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        stp.bottomMargin = Ui.dp(this, 38);
        stage.addView(status, stp);

        // Download / close, inside the wide lower arc of the circle.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        downloadBtn = miniButton(getString(R.string.download), "#FF2C5A88");
        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });
        TextView close = miniButton("关闭", "#FF202836");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 26));
        dlp.setMarginEnd(Ui.dp(this, 6));
        bar.addView(downloadBtn, dlp);
        bar.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 26)));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 26),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 6);
        stage.addView(bar, bp);
    }

    private TextView miniButton(String text, String color) {
        TextView b = Ui.button(this, text, Color.parseColor(color), 26);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setHeight(Ui.dp(this, 26));
        b.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        return b;
    }

    private String stringExtra(String key) {
        String v = getIntent().getStringExtra(key);
        return v == null ? "" : v;
    }

    private int intExtra(String key) {
        return getIntent().getIntExtra(key, MediaFile.TYPE_PHOTO);
    }

    /** 4001/4002 are only honoured in playback mode; guard a cold start too. */
    private void withPlayback(final Runnable then) {
        if (session.getMode() != CameraCmd.MODE_PLAYBACK) {
            session.enterPlayback(then);
        } else {
            then.run();
        }
    }

    // ------------------------------------------------------------------ photo

    private void loadPhoto(final ImageView iv) {
        withPlayback(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap bmp = decodeOrNull(session.http.previewImageUrl(file.url));
                        if (bmp == null) {
                            bmp = decodeOrNull(file.url);
                        }
                        final Bitmap result = bmp;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) {
                                    return;
                                }
                                if (result != null) {
                                    note.setVisibility(View.GONE);
                                    iv.setImageBitmap(result);
                                } else {
                                    note.setText("无法载入原图\n可下载后查看");
                                }
                            }
                        });
                    }
                }, "sjcam-preview").start();
            }
        });
    }

    // ------------------------------------------------------------------ video

    /** 4002/4001 also return an embedded JPEG for clips on most firmwares. */
    private void loadVideoPoster() {
        withPlayback(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap bmp = decodeOrNull(session.http.previewImageUrl(file.url));
                        if (bmp == null) {
                            bmp = decodeOrNull(file.thumb);
                        }
                        final Bitmap result = bmp;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing() || poster == null || result == null) {
                                    return;
                                }
                                poster.setImageBitmap(result);
                                note.setVisibility(View.GONE);
                            }
                        });
                    }
                }, "sjcam-poster").start();
            }
        });
    }

    /** Header only - measures the frame so we can decide before decoding. */
    private void probeVideo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final VideoProbe.Info info = VideoProbe.probe(file.url);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onProbed(info);
                    }
                });
            }
        }, "sjcam-probe").start();
    }

    private void onProbed(VideoProbe.Info info) {
        if (isFinishing() || videoSurface == null) {
            return;
        }
        if (info != null && !info.watchCanDecode()) {
            // Refusing here is the whole point: starting a decoder on a 4K frame
            // gives a frozen picture and a hot case, without ever playing.
            videoTooBig = true;
            caption.setVisibility(View.VISIBLE);
            caption.setText(info.label() + " · 超出解码能力\n长按封面可强制试播，或下载观看");
            note.setVisibility(View.GONE);
            return;
        }
        if (info != null) {
            caption.setVisibility(View.VISIBLE);
            caption.setText(info.label() + " · 播放中");
        }
        videoSurface.setVisibility(View.VISIBLE);
        if (videoSurface.getHolder().getSurface() != null
                && videoSurface.getHolder().getSurface().isValid()) {
            startVideo(videoSurface.getHolder());
        }
    }

    private void startVideo(SurfaceHolder holder) {
        if (videoTooBig || player != null) {
            return;
        }
        note.setText("正在打开视频…");
        note.setVisibility(View.VISIBLE);
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDisplay(holder);
            mp.setDataSource(file.url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (isFinishing()) {
                        return;
                    }
                    main.removeCallbacks(prepareTimeout);
                    playing = true;
                    note.setVisibility(View.GONE);
                    m.start();
                    main.postDelayed(progressWatch, 2000);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    fail("该视频无法在线播放");
                    return true;
                }
            });
            player = mp;
            mp.prepareAsync();
            main.postDelayed(prepareTimeout, PREPARE_TIMEOUT);
        } catch (Exception e) {
            fail("该视频无法在线播放");
        }
    }

    private final Runnable prepareTimeout = new Runnable() {
        @Override
        public void run() {
            if (player != null && !playing) {
                fail("视频打开超时");
            }
        }
    };

    /**
     * Position watchdog. A 4K clip whose decoder is drowning still reports
     * "playing", so watching the clock is the only way to notice - and stopping
     * it is what keeps the watch from heating up for nothing.
     */
    private final Runnable progressWatch = new Runnable() {
        @Override
        public void run() {
            if (player == null || !playing) {
                return;
            }
            int pos = -1;
            try {
                pos = player.getCurrentPosition();
            } catch (Exception ignored) {
            }
            if (pos >= 0 && pos == lastPos) {
                stalled++;
            } else {
                stalled = 0;
            }
            lastPos = pos;
            if (stalled >= STALL_LIMIT) {
                fail("播放卡顿，已停止");
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    /** Stop decoding, keep whatever poster we already have, explain why. */
    private void fail(String why) {
        stopVideo();
        caption.setVisibility(View.VISIBLE);
        caption.setText(why + "\n可下载后观看");
        if (videoSurface != null) {
            videoSurface.setVisibility(View.GONE);
        }
    }

    private void stopVideo() {
        main.removeCallbacks(progressWatch);
        main.removeCallbacks(prepareTimeout);
        playing = false;
        lastPos = -1;
        stalled = 0;
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

    // ----------------------------------------------------------------- decode

    private Bitmap decodeOrNull(String url) {
        try {
            return decode(url);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap decode(String url) throws Exception {
        byte[] data = session.http.getBytes(url, 12000, 12 * 1024 * 1024);
        if (data.length < 128 || !looksLikeImage(data)) {
            return null;
        }
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, probe);
        if (probe.outWidth <= 0) {
            return null;
        }
        int maxPx = screenDp() * 2;
        int sample = 1;
        while (sample < 16 && (probe.outWidth / sample > maxPx
                || probe.outHeight / sample > maxPx)) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        opt.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
    }

    /** A firmware that ignores cmd=4001 replies with the video body; reject it. */
    private static boolean looksLikeImage(byte[] d) {
        if (d.length < 4) {
            return false;
        }
        int b0 = d[0] & 0xff;
        int b1 = d[1] & 0xff;
        if (b0 == 0xFF && b1 == 0xD8) {
            return true; // JPEG
        }
        return b0 == 0x89 && b1 == 0x50; // PNG
    }

    // --------------------------------------------------------------- download

    private void startDownload() {
        if (downloading) {
            return;
        }
        if (file.url == null || file.url.isEmpty()) {
            toast("文件地址无效");
            return;
        }
        downloading = true;
        Ui.setEnabled(downloadBtn, false);
        downloadBtn.setText("下载中");
        MediaRepository.download(this, session, file, new MediaRepository.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                status.setText(percent + "%");
            }

            @Override
            public void onDone(boolean ok, String message) {
                downloading = false;
                Ui.setEnabled(downloadBtn, true);
                downloadBtn.setText("下载");
                status.setText(ok ? "已保存" : "失败");
                toast(message);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Never decode behind another screen: that burned battery for nothing.
        stopVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVideo();
    }
}
