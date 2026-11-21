package com.sjcam.watch;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.camera.QualityOptions;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.Ui;

import java.util.List;

/**
 * Resolution picker for photo or video ("清晰度"), mirroring the original app.
 *
 * The option list is whatever the camera reports for cmd 2002 / 1002 (see
 * QualityOptions), so it stays correct on every model; tapping a row sends
 * that index as `par`, then 3021 to persist it.
 */
public class QualityActivity extends BaseRoundActivity {

    private static final String E_TYPE = "type";

    public static void open(Context from, boolean photo) {
        Intent i = new Intent(from, QualityActivity.class);
        i.putExtra(E_TYPE, photo ? 0 : 1);
        from.startActivity(i);
    }

    private final CameraSession session = CameraSession.get();

    private boolean photo;
    private int cmd;
    private TextView title;
    private TextView status;
    private LinearLayout rows;
    private boolean busy;

    @Override
    protected float shellRatio() {
        return 0.76f;
    }

    @Override
    protected boolean shellScrollable() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        photo = getIntent().getIntExtra(E_TYPE, 1) == 0;
        cmd = photo ? QualityOptions.CMD_PHOTO : QualityOptions.CMD_VIDEO;

        content.addView(Ui.spacer(this, 8));

        title = Ui.label(this, photo ? "拍照清晰度" : "录像清晰度", 12, Color.WHITE, true);
        content.addView(title, Ui.lpMatch(0, this));

        status = Ui.label(this, "读取相机设置…", 9, Color.parseColor("#FF93A0B4"), false);
        content.addView(status, Ui.lpMatch(4, this));

        rows = Ui.column(this);
        content.addView(rows, Ui.lpMatch(4, this));

        load();
    }

    private void load() {
        status.setText("读取相机设置…");
        rows.removeAllViews();
        QualityOptions.load(session, cmd, new QualityOptions.LoadCallback() {
            @Override
            public void onResult(final QualityOptions.Options options, String error) {
                if (isFinishing()) {
                    return;
                }
                if (options == null || options.options.isEmpty()) {
                    status.setText(error == null ? "无法读取清晰度" : error);
                    return;
                }
                status.setText((options.current >= 0
                        ? "当前 " + options.currentLabel()
                        : "相机未报告当前值")
                        + (options.fromCamera ? "" : " · 内置表"));                render(options);
            }
        });
    }

    private void render(final QualityOptions.Options options) {
        rows.removeAllViews();
        List<QualityOptions.Option> list = options.options;
        for (int i = 0; i < list.size(); i++) {
            final QualityOptions.Option o = list.get(i);
            boolean selected = o.index == options.current;
            TextView row = Ui.label(this,
                    (selected ? "✓ " : "") + o.shortLabel, 10,
                    selected ? Color.WHITE : Color.parseColor("#FFC6D0DE"), selected);
            row.setBackground(Ui.rounded(this, Color.parseColor(
                    selected ? "#FF2C5A88" : "#FF141A24"), 15));
            row.setHeight(Ui.dp(this, 30));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choose(o);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lpMatch(4, this);
            rows.addView(row, lp);
        }
    }

    private void choose(final QualityOptions.Option option) {
        if (busy) {
            return;
        }
        busy = true;
        status.setText("切换到 " + option.shortLabel + " …");
        QualityOptions.apply(session, cmd, option.index, new QualityOptions.ApplyCallback() {
            @Override
            public void onDone(boolean ok, String message) {
                busy = false;
                if (isFinishing()) {
                    return;
                }
                if (!ok) {
                    status.setText("切换失败：" + message);
                    return;
                }
                QualityOptions.setCachedLabel(cmd, option.label);
                toast("已切换为 " + option.shortLabel);
                finish();
            }
        });
    }
}
