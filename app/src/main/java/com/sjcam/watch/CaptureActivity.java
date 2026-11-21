package com.sjcam.watch;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraCmd;
import com.sjcam.watch.camera.CameraHttp;
import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.camera.GestureSupport;
import com.sjcam.watch.camera.PreviewController;
import com.sjcam.watch.camera.QualityOptions;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.Ui;

/**
 * Step 3 - shooting screen.
 *
 * Photo : 3001 par=0 (photo mode) -> 1001 (shutter) -> <FPATH> result
 * Video : 3001 par=1 (video mode) -> 2001 par=1 / par=0, 2016 gives elapsed time
 * Preview: 2015 par=1/0 + RTSP sub-stream, rendered in a full-screen SurfaceView
 *
 * Preview is an OVERLAY, not a row inside the scrolling list. Two reasons:
 *  - a SurfaceView (hardware overlay) cannot live inside a scroll container
 *    without smearing, and TextureView - which can - costs a GPU copy per frame
 *    and was the dominant heat source on the watch;
 *  - when the preview is on, the user wants the whole panel for framing, with
 *    the shutter still reachable. The overlay provides exactly that.
 * Leaving the screen (onPause) turns the preview off, so the decoder never runs
 * behind another activity or with the screen off.
 */
public class CaptureActivity extends BaseRoundActivity {

    @Override
    protected float shellRatio() {
        return 0.72f;
    }

    private final CameraSession session = CameraSession.get();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView statusLine;
    private TextView photoBtn;
    private TextView videoBtn;
    private TextView previewBtn;
    private TextView qualityBtn;
    private TextView shutter;

    private PreviewController preview;
    private FrameLayout previewLayer;
    private SurfaceView previewSurface;
    private TextView previewInfo;
    private TextView previewShutter;

    private GestureSupport.Controller gesture;

    private int mode = 0; // 0 photo, 1 video
    private boolean recording = false;
    private boolean busy = false;
    private boolean qualityLoading = false;
    private boolean previewOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        content.addView(Ui.spacer(this, 10));

        statusLine = Ui.label(this, "相机已连接", 10, Color.parseColor("#FF93A0B4"), false);
        content.addView(statusLine, Ui.lpMatch(2, this));

        // Mode selector: two compact buttons sharing the narrow column.
        LinearLayout row = Ui.row(this);
        photoBtn = compactButton(getString(R.string.photo), Color.parseColor("#FF4DA3FF"));
        videoBtn = compactButton(getString(R.string.video), Color.parseColor("#FF202836"));
        row.addView(photoBtn, Ui.rowItem(this, 0));
        row.addView(videoBtn, Ui.rowItem(this, 4));
        content.addView(row, Ui.lpMatch(6, this));

        photoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseMode(0);
            }
        });
        videoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseMode(1);
            }
        });

        previewBtn = compactButton(getString(R.string.preview_off), Color.parseColor("#FF202836"));
        previewBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePreview();
            }
        });
        content.addView(previewBtn, Ui.lpMatch(6, this));

        // Photo / video resolution, same feature as the phone app's 分辨率 row.
        qualityBtn = compactButton("清晰度", Color.parseColor("#FF202836"));
        qualityBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QualityActivity.open(CaptureActivity.this, mode == 0);
            }
        });
        content.addView(qualityBtn, Ui.lpMatch(6, this));

        shutter = Ui.circleButton(this, "拍照", Color.parseColor("#FF4DA3FF"), 58);
        LinearLayout.LayoutParams sp = Ui.lpWrap(8, this);
        sp.width = Ui.dp(this, 58);
        sp.height = Ui.dp(this, 58);
        shutter.setLayoutParams(sp);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shutterAction();
            }
        });
        content.addView(shutter, sp);

        content.addView(Ui.label(this, "上下滑动查看全部", 9,
                Color.parseColor("#FF5A6577"), false), Ui.lpMatch(6, this));

        buildPreviewLayer();
        updateButtons();
    }

    // ------------------------------------------------------------- preview UI

    /**
     * Full-screen overlay holding the only SurfaceView in the app.
     * Everything is centred in the inscribed square (0.70 * diameter), which is
     * the largest shape that never crosses the round bezel, and the shutter sits
     * on its bottom edge like a normal camera app.
     */
    private void buildPreviewLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(Color.parseColor("#FF000000"));
        // Swallow taps so they cannot fall through to the list underneath.
        layer.setClickable(true);

        int box = (int) (screenDp() * 0.70f);

        FrameLayout boxFrame = new FrameLayout(this);
        layer.addView(boxFrame, new FrameLayout.LayoutParams(box, box, Gravity.CENTER));

        previewSurface = new SurfaceView(this);
        boxFrame.addView(previewSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewInfo = Ui.label(this, "", 9, Color.parseColor("#FFD4DCE8"), false);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ip.bottomMargin = Ui.dp(this, 44);
        boxFrame.addView(previewInfo, ip);

        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER);

        previewShutter = Ui.circleButton(this, "拍照", Color.parseColor("#FF4DA3FF"), 44);
        previewShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shutterAction();
            }
        });
        LinearLayout.LayoutParams psp = new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44));
        psp.setMarginEnd(Ui.dp(this, 8));

        TextView exit = Ui.button(this, "退出", Color.parseColor("#B3202836"), 28);
        exit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10);
        exit.setMinHeight(0);
        exit.setHeight(Ui.dp(this, 28));
        exit.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hidePreview();
            }
        });

        bar.addView(previewShutter, psp);
        bar.addView(exit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 28)));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(this, 4);
        boxFrame.addView(bar, bp);

        layer.setVisibility(View.GONE);
        // Added to the decor, outside the round safe column, so the overlay can
        // use the entire panel (the preview square is already inside the circle).
        addContentView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewLayer = layer;

        preview = new PreviewController(session);
        preview.attach(this, previewSurface, new PreviewController.Listener() {
            @Override
            public void onInfo(String text) {
                previewInfo.setText(text);
            }
        });
    }

    private void togglePreview() {
        if (previewOn) {
            hidePreview();
        } else {
            showPreview();
        }
    }

    private void showPreview() {
        if (previewOn) {
            return;
        }
        previewOn = true;
        if (previewLayer != null) {
            previewLayer.setVisibility(View.VISIBLE);
        }
        updateButtons();
        preview.setEnabled(true);
    }

    private void hidePreview() {
        if (!previewOn) {
            return;
        }
        previewOn = false;
        if (preview != null) {
            preview.setEnabled(false);
        }
        if (previewLayer != null) {
            previewLayer.setVisibility(View.GONE);
        }
        updateButtons();
    }

    @Override
    public void onBackPressed() {
        if (previewOn) {
            hidePreview();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        statusLine.setText(session.isConnected() ? "● 相机已连接" : "● 连接可能已断开");
        // Picks up a resolution chosen on the QualityActivity.
        refreshQualityLabel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the screen must kill the live stream: a decoder left running
        // behind another activity is pure heat, and the album needs the camera
        // out of recording/live mode anyway.
        hidePreview();
        stopRecording();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(recordPoll);
        stopRecording();
        if (preview != null) {
            preview.release();
        }
        if (gesture != null) {
            gesture.stop();
        }
    }

    private void stopRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        main.removeCallbacks(recordPoll);
        session.exec(CameraCmd.RECORD, CameraCmd.RECORD_STOP, null, 8000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String v, String e) {
                    }
                });
    }

    private TextView compactButton(String text, int bg) {
        TextView b = Ui.button(this, text, bg, 38);
        b.setMinHeight(0);
        b.setHeight(Ui.dp(this, 38));
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        return b;
    }

    private void updateButtons() {
        photoBtn.setBackground(Ui.rounded(this,
                Color.parseColor(mode == 0 ? "#FF4DA3FF" : "#FF202836"), 18));
        videoBtn.setBackground(Ui.rounded(this,
                Color.parseColor(mode == 1 ? "#FF4DA3FF" : "#FF202836"), 18));
        shutter.setText(mode == 0 ? "拍照" : (recording ? "停止" : "录像"));
        shutter.setBackground(Ui.oval(Color.parseColor(
                recording ? "#FFFF5A5F" : "#FF4DA3FF")));
        previewBtn.setText(previewOn ? getString(R.string.preview_on)
                : getString(R.string.preview_off));
        if (previewShutter != null) {
            previewShutter.setText(mode == 0 ? "拍照" : (recording ? "停止" : "录像"));
            previewShutter.setBackground(Ui.oval(Color.parseColor(
                    recording ? "#FFFF5A5F" : "#FF4DA3FF")));
        }
    }

    private void shutterAction() {
        if (mode == 0) {
            takePhoto();
        } else {
            toggleRecord();
        }
    }

    // --------------------------------------------------------- camera quality

    /**
     * Shows the resolution the camera actually reports and loads it once when
     * the label is still unknown. The camera itself is the source of truth:
     * cmd=3031 lists the options, cmd=3014 gives the current index.
     */
    private void refreshQualityLabel() {
        if (qualityBtn == null) {
            return;
        }
        final int cmd = mode == 0 ? QualityOptions.CMD_PHOTO : QualityOptions.CMD_VIDEO;
        String label = QualityOptions.cachedLabel(cmd);
        qualityBtn.setText((qualityLoading && label.isEmpty() ? "读取清晰度…" : qualityText(label)));
        if (!label.isEmpty() || qualityLoading) {
            return;
        }
        qualityLoading = true;
        qualityBtn.setText("读取清晰度…");
        QualityOptions.load(session, cmd, new QualityOptions.LoadCallback() {
            @Override
            public void onResult(QualityOptions.Options options, String error) {
                qualityLoading = false;
                if (isFinishing() || mode != (cmd == QualityOptions.CMD_PHOTO ? 0 : 1)) {
                    return;
                }
                if (options == null) {
                    qualityBtn.setText("清晰度不可用");
                    return;
                }
                String current = options.currentLabel();
                if (!current.isEmpty()) {
                    QualityOptions.setCachedLabel(cmd, current);
                }
                qualityBtn.setText(qualityText(current));
            }
        });
    }

    private String qualityText(String label) {
        String prefix = mode == 0 ? "照片 · " : "视频 · ";
        return prefix + (label == null || label.isEmpty()
                ? "清晰度" : QualityOptions.shortLabel(label));
    }

    private void chooseMode(final int m) {
        if (mode == m || busy || recording) {
            if (recording) {
                toast("请先停止录像");
            }
            updateButtons();
            return;
        }
        busy = true;
        statusLine.setText("切换模式中…");
        session.exec(CameraCmd.MODE_CHANGE,
                m == 0 ? CameraCmd.MODE_PHOTO : CameraCmd.MODE_VIDEO, null, 9000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String xml, String error) {
                        busy = false;
                        if (error != null) {
                            statusLine.setText("切换失败：" + error);
                            return;
                        }
                        mode = m;
                        statusLine.setText(m == 0 ? "照片模式" : "视频模式");
                        updateButtons();
                        // Photo and video keep separate resolutions on the camera.
                        refreshQualityLabel();
                    }
                });
    }

    private void takePhoto() {
        if (busy) {
            return;
        }
        busy = true;
        Ui.setEnabled(shutter, false);
        statusLine.setText("拍照中…");
        session.exec(CameraCmd.MODE_CHANGE, CameraCmd.MODE_PHOTO, null, 9000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String a, String e1) {
                        session.exec(CameraCmd.CAPTURE, null, null, 15000,
                                new CameraSession.Callback<String>() {
                                    @Override
                                    public void onResult(String xml, String error) {
                                        busy = false;
                                        Ui.setEnabled(shutter, true);
                                        if (error != null) {
                                            statusLine.setText("拍照失败：" + error);
                                            return;
                                        }
                                        String path = CameraHttp.firstTag(xml, "FPATH");
                                        int st = CameraHttp.status(xml);
                                        if (path != null) {
                                            int i = path.replace('\\', '/').lastIndexOf('/');
                                            statusLine.setText("已拍摄 " + (i >= 0
                                                    ? path.substring(i + 1) : path));
                                            toast("照片已保存到相机");
                                        } else if (st == 0) {
                                            statusLine.setText("已拍照");
                                        } else if (st != Integer.MIN_VALUE) {
                                            statusLine.setText("失败：" + CameraCmd.statusText(st));
                                        } else {
                                            statusLine.setText("已发送拍照命令");
                                        }
                                    }
                                });
                    }
                });
    }

    private void toggleRecord() {
        if (busy) {
            return;
        }
        busy = true;
        Ui.setEnabled(shutter, false);
        final boolean start = !recording;
        statusLine.setText(start ? "开始录像…" : "停止录像…");
        final int par = start ? CameraCmd.RECORD_START : CameraCmd.RECORD_STOP;
        session.exec(start ? CameraCmd.MODE_CHANGE : CameraCmd.RECORD,
                start ? CameraCmd.MODE_VIDEO : par, null, 9000,
                new CameraSession.Callback<String>() {
                    @Override
                    public void onResult(String a, String e1) {
                        if (!start) {
                            finishRecord(false, null);
                            return;
                        }
                        session.exec(CameraCmd.RECORD, CameraCmd.RECORD_START, null, 12000,
                                new CameraSession.Callback<String>() {
                                    @Override
                                    public void onResult(String xml, String error) {
                                        if (error == null) {
                                            int st = CameraHttp.status(xml);
                                            if (st != Integer.MIN_VALUE && st != 0) {
                                                finishRecord(false, CameraCmd.statusText(st));
                                                return;
                                            }
                                        }
                                        finishRecord(error == null, error);
                                    }
                                });
                    }
                });
    }

    private void finishRecord(boolean started, String error) {
        busy = false;
        recording = started;
        Ui.setEnabled(shutter, true);
        if (error != null) {
            statusLine.setText("录像失败：" + error);
        } else {
            statusLine.setText(recording ? "● 正在录像" : "录像已停止");
        }
        updateButtons();
        main.removeCallbacks(recordPoll);
        if (recording) {
            main.postDelayed(recordPoll, 1500);
        }
    }

    private final Runnable recordPoll = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            session.exec(CameraCmd.RECORD_TIME, null, null, 6000,
                    new CameraSession.Callback<String>() {
                        @Override
                        public void onResult(String xml, String error) {
                            if (!recording) {
                                return;
                            }
                            String v = CameraHttp.firstTag(xml, "Value");
                            if (v != null) {
                                statusLine.setText("● 录像 " + v + " s");
                            }
                            main.postDelayed(recordPoll, 2500);
                        }
                    });
        }
    };

    /**
     * Extension point for a future OPPO gesture SDK. Not called by default:
     * see GestureSupport for why (no public third-party gesture API today).
     */
    public void bindGesture(GestureSupport.Controller controller) {
        if (gesture != null) {
            gesture.stop();
        }
        gesture = controller;
        gesture.start(this, new GestureSupport.Listener() {
            @Override
            public void onShutter() {
                takePhoto();
            }

            @Override
            public void onToggleMode() {
                chooseMode(mode == 0 ? 1 : 0);
            }
        });
    }
}
