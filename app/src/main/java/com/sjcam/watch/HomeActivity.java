package com.sjcam.watch;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.sjcam.watch.camera.CameraSession;
import com.sjcam.watch.ui.BaseRoundActivity;
import com.sjcam.watch.ui.Ui;

/** Step 2 - two choices: shoot, or browse history. */
public class HomeActivity extends BaseRoundActivity {

    private TextView statusLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content.setGravity(android.view.Gravity.CENTER);

        statusLine = Ui.label(this, "● 检查中", 10, Color.parseColor("#FF93A0B4"), false);
        content.addView(statusLine, Ui.lpMatch(2, this));

        TextView title = Ui.label(this, "相机控制", 15, Color.WHITE, true);
        content.addView(title, Ui.lpMatch(8, this));

        TextView shoot = Ui.button(this, getString(R.string.shoot),
                Color.parseColor("#FF4DA3FF"), 50);
        shoot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        shoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, CaptureActivity.class));
            }
        });
        content.addView(shoot, Ui.lpMatch(16, this));

        TextView history = Ui.button(this, getString(R.string.history),
                Color.parseColor("#FF202836"), 50);
        history.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, HistoryActivity.class));
            }
        });
        content.addView(history, Ui.lpMatch(10, this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        CameraSession.get().ensureAlive(new CameraSession.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok, String detail) {
                statusLine.setText(ok ? "● 相机已连接" : "● 连接已断开");
                statusLine.setTextColor(ok
                        ? Color.parseColor("#FF57D9A3")
                        : Color.parseColor("#FFFF5A5F"));
            }
        });
    }
}
