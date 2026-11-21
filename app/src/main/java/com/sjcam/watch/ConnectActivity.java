package com.sjcam.watch;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.Ui;

/**
 * Step 1 - the app ALWAYS starts here and decides whether a camera is reachable.
 * The check probes the current Wi-Fi gateway, then 192.168.1.254 / 192.168.8.1.
 */
public class ConnectActivity extends BaseRoundActivity {

    private TextView status;
    private ProgressBar progress;
    private TextView retry;
    private TextView wifi;
    private boolean navigating = false;
    private final Runnable goHome = new Runnable() {
        @Override
        public void run() {
            if (!navigating || isFinishing()) {
                return;
            }
            startActivity(new Intent(ConnectActivity.this, HomeActivity.class));
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.app_name));
        content.setGravity(android.view.Gravity.CENTER);

        TextView title = Ui.label(this, "SJCAM 相机", 16, Color.WHITE, true);
        content.addView(title, Ui.lpMatch(6, this));

        status = Ui.label(this, getString(R.string.checking), 12,
                Color.parseColor("#FF93A0B4"), false);
        content.addView(status, Ui.lpMatch(14, this));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        content.addView(progress, Ui.lpWrap(12, this));

        TextView hint = Ui.label(this, "请先在 手表设置 → WLAN\n连接相机的 Wi-Fi 热点", 11,
                Color.parseColor("#FF93A0B4"), false);
        content.addView(hint, Ui.lpMatch(14, this));

        retry = Ui.button(this, getString(R.string.retry),
                Color.parseColor("#FF4DA3FF"), 44);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                check();
            }
        });
        content.addView(retry, Ui.lpMatch(12, this));

        wifi = Ui.button(this, getString(R.string.wifi_settings),
                Color.parseColor("#FF202836"), 44);
        wifi.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        wifi.setVisibility(View.GONE);
        wifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    toast("请在手表设置中打开 WLAN");
                }
            }
        });
        content.addView(wifi, Ui.lpMatch(8, this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getWindow().getDecorView().removeCallbacks(goHome);
    }

    private void check() {
        navigating = false;
        getWindow().getDecorView().removeCallbacks(goHome);
        progress.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        wifi.setVisibility(View.GONE);
        status.setText(getString(R.string.checking));
        status.setTextColor(Color.parseColor("#FF93A0B4"));

        CameraSession.get().check(this, new CameraSession.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok, String detail) {
                progress.setVisibility(View.GONE);
                if (ok) {
                    status.setText("已连接相机  " + detail);
                    status.setTextColor(Color.parseColor("#FF57D9A3"));
                    navigating = true;
                    getWindow().getDecorView().postDelayed(goHome, 700);
                } else {
                    status.setText("未检测到相机\n请连接相机热点后重试");
                    status.setTextColor(Color.parseColor("#FFFFC46B"));
                    retry.setVisibility(View.VISIBLE);
                    wifi.setVisibility(View.VISIBLE);
                }
            }
        });
    }
}
