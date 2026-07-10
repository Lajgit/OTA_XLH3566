package com.zeda.ota;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.zeda.ota.wifi.QrUtil;

public class ClaimActivity extends Activity {

    private static final String TAG = "OTA_TEST";

    private ImageView ivQr;
    private TextView tvCode;
    private TextView tvHint;

    private ClaimManager claimManager;

    private boolean started = false;
    private boolean activated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // 先去掉标题栏
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);

        // 全屏
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        hideSystemUI();

        setContentView(R.layout.activity_claim);

        ivQr = findViewById(R.id.ivQr);
        tvCode = findViewById(R.id.tvCode);
        tvHint = findViewById(R.id.tvHint);

        tvCode.setText("认领码：");
        tvHint.setText("正在向服务器报到...");

        claimManager = new ClaimManager();

        startClaimFlow();
    }

    private void hideSystemUI() {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                getWindow().setDecorFitsSystemWindows(false);

                WindowInsetsController controller =
                        getWindow().getInsetsController();

                if (controller != null) {

                    controller.hide(
                            WindowInsets.Type.statusBars()
                                    | WindowInsets.Type.navigationBars()
                    );

                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }

            } else {

                View decorView = getWindow().getDecorView();

                decorView.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }

        } catch (Throwable e) {

            Log.e("OTA_TEST", "hideSystemUI fail", e);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void startClaimFlow() {

        if (started) {
            return;
        }

        started = true;

        claimManager.start(
                this,
                new ClaimManager.Callback() {

                    @Override
                    public void onWaitingClaim(
                            String qrContent,
                            String claimCode
                    ) {

                        runOnUiThread(
                                () -> {

                                    tvCode.setText(
                                            "认领码：" +
                                                    (
                                                            claimCode == null
                                                                    ? ""
                                                                    : claimCode
                                                    )
                                    );

                                    tvHint.setText(
                                            "请使用商户端扫码完成认领"
                                    );

                                    if (qrContent != null &&
                                            !qrContent.isEmpty()) {

                                        Bitmap qr =
                                                QrUtil.create(
                                                        qrContent,
                                                        600
                                                );

                                        ivQr.setImageBitmap(
                                                qr
                                        );
                                    }
                                }
                        );
                    }

                    @Override
                    public void onActivated(
                            ActivationData data
                    ) {

                        if (activated) {
                            return;
                        }

                        activated = true;

                        runOnUiThread(
                                () -> {

                                    Log.e(
                                            TAG,
                                            "claim activated, continue MainService"
                                    );

                                    tvHint.setText(
                                            "设备已认领，正在启动服务..."
                                    );

                                    /*
                                     * 不要在这里连接 MQTT。
                                     * MQTT、定时任务、pending 检查、启动主APP
                                     * 全部交给 MainService.init()。
                                     */
                                    MainService.continueAfterClaim(
                                            getApplicationContext()
                                    );

                                    finish();
                                }
                        );
                    }

                    @Override
                    public void onError(
                            Exception e
                    ) {

                        runOnUiThread(
                                () -> {

                                    String msg =
                                            e == null
                                                    ? "未知错误"
                                                    : e.getMessage();

                                    tvHint.setText(
                                            "认领失败：" + msg
                                    );

                                    Log.e(
                                            TAG,
                                            "claim error",
                                            e
                                    );
                                }
                        );
                    }
                }
        );
    }

    @Override
    protected void onDestroy() {

        if (claimManager != null) {
            claimManager.stop();
        }

        super.onDestroy();
    }
}