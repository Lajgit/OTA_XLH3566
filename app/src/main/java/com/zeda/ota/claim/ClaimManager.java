package com.zeda.ota;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.UUID;

import java.util.concurrent.atomic.AtomicBoolean;

public class ClaimManager {

    private static final String TAG = "OTA_TEST";

    private static final String BATCH_NO =
            "BATCH-202606-A";

    private static final String BATCH_SECRET =
            "PXD_AUTO_CLAIM_BATCH_202606_A_SECRET";

    private final ClaimApiClient apiClient =
            new ClaimApiClient();

    private final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    private final AtomicBoolean polling =
            new AtomicBoolean(false);

    private Runnable pollRunnable;

    private String lastQrContent = "";
    private String lastClaimCode = "";

    public interface Callback {

        void onWaitingClaim(
                String qrContent,
                String claimCode
        );

        void onActivated(
                ActivationData data
        );

        void onError(
                Exception e
        );
    }

    private void fillBatchSignature(
            EnrollData req
    ) {

        req.batchNo =
                BATCH_NO;

        String signRaw =
                req.deviceNo
                        + "|"
                        + req.batchNo
                        + "|"
                        + req.nonce
                        + "|"
                        + req.timestamp;

        req.batchSignature =
                HmacUtil.hmacSha256Base64Url(
                        signRaw,
                        BATCH_SECRET
                );

        Log.e(
                TAG,
                "auto claim batch sign"
                        + ", deviceNo="
                        + req.deviceNo
                        + ", batchNo="
                        + req.batchNo
                        + ", nonce="
                        + req.nonce
                        + ", timestamp="
                        + req.timestamp
                        + ", signRaw="
                        + signRaw
                        + ", batchSignature="
                        + req.batchSignature
        );
    }

    public void start(
            Context context,
            Callback callback
    ) {

        if (polling.get()) {
            Log.e(TAG, "claim already polling");
            return;
        }

        polling.set(true);

        startEnroll(
                context.getApplicationContext(),
                callback
        );
    }

    private void startEnroll(
            Context context,
            Callback callback
    ) {

        String deviceNo =
                DeviceUtil.getDeviceId(
                        context
                );

        EnrollData req =
                new EnrollData();

        req.deviceNo =
                deviceNo;

        req.firmwareVersion =
                PackageUtil.getVersion(context, "com.zeda.ota");

        req.apkVersion =
                req.apkVersion = PackageUtil.getVersion(context, "com.zeda");

        req.nonce =
                UUID.randomUUID()
                        .toString()
                        .replace(
                                "-",
                                ""
                        );

        req.timestamp =
                System.currentTimeMillis();

        req.signature =
                "";

        fillBatchSignature(
                req
        );

        Log.e(TAG, "claim enroll deviceNo=" + deviceNo);

        apiClient.enroll(
                req,
                new ClaimApiClient.Callback<ApiResponse<EnrollData>>() {

                    @Override
                    public void onSuccess(
                            ApiResponse<EnrollData> resp
                    ) {

                        if (!polling.get()) {
                            return;
                        }

                        logApiResponse(
                                "enroll",
                                resp
                        );

                        if (resp == null ||
                                resp.data == null) {

                            postError(
                                    callback,
                                    new Exception(
                                            "enroll response empty,msg="
                                            + (resp == null ? "null" : resp.msg)
                                    )
                            );

                            return;
                        }

                        EnrollData enrollData =
                                resp.data;

                        Log.e(
                                TAG,
                                "enroll response"
                                        + ", code=" + resp.code
                                        + ", success=" + resp.success
                                        + ", msg=" + resp.msg
                                        + ", accepted=" + enrollData.accepted
                                        + ", deviceNo=" + enrollData.deviceNo
                                        + ", claimToken=" + enrollData.claimToken
                                        + ", claimCode=" + enrollData.claimCode
                                        + ", claimQrContent=" + enrollData.claimQrContent
                                        + ", claimStatus=" + enrollData.claimStatus
                                        + ", claimStatusDesc=" + enrollData.claimStatusDesc
                                        + ", claimed=" + enrollData.claimed
                                        + ", message=" + enrollData.message
                        );

                        lastQrContent =
                                enrollData.claimQrContent == null
                                        ? ""
                                        : enrollData.claimQrContent;

                        lastClaimCode =
                                enrollData.claimCode == null
                                        ? ""
                                        : enrollData.claimCode;

                        mainHandler.post(
                                () -> callback.onWaitingClaim(
                                        lastQrContent,
                                        lastClaimCode
                                )
                        );

                        /*
                         * 关键：
                         * 即使 enroll 返回 claimed=true，
                         * 也不能直接 onActivated。
                         *
                         * 因为 MQTT 凭证在 activation 接口里，
                         * 必须继续调用 activation。
                         */
                        requestActivationOnce(
                                context,
                                req,
                                callback,
                                true
                        );
                    }

                    @Override
                    public void onFailure(
                            Exception e
                    ) {

                        postError(
                                callback,
                                e
                        );
                    }
                }
        );
    }

    private void requestActivationOnce(
            Context context,
            EnrollData req,
            Callback callback,
            boolean startPollingIfNotClaimed
    ) {

        req.timestamp =
                System.currentTimeMillis();

        fillBatchSignature(req);

        Log.e(TAG, "activation request once");

        apiClient.activation(
                req,
                new ClaimApiClient.Callback<ApiResponse<ActivationData>>() {

                    @Override
                    public void onSuccess(
                            ApiResponse<ActivationData> resp
                    ) {

                        if (!polling.get()) {
                            return;
                        }

                        logApiResponse(
                                "activation once",
                                resp
                        );


                        if (resp == null ||
                                resp.data == null) {

                            Log.e(TAG, "activation response empty"+ ", msg="
                                    + (resp == null ? "null" : resp.msg)
                            );

                            if (startPollingIfNotClaimed) {
                                startActivationPolling(
                                        context,
                                        req,
                                        callback
                                );
                            }

                            return;
                        }

                        boolean handled =
                                handleActivationData(
                                        context,
                                        resp.data,
                                        resp.msg,
                                        resp.code,
                                        resp.success,
                                        callback
                                );

                        if (!handled &&
                                startPollingIfNotClaimed) {

                            startActivationPolling(
                                    context,
                                    req,
                                    callback
                            );
                        }
                    }

                    @Override
                    public void onFailure(
                            Exception e
                    ) {

                        Log.e(TAG, "activation once fail", e);

                        if (startPollingIfNotClaimed) {
                            startActivationPolling(
                                    context,
                                    req,
                                    callback
                            );
                        }
                    }
                }
        );
    }

    private void startActivationPolling(
            Context context,
            EnrollData req,
            Callback callback
    ) {

        if (!polling.get()) {
            return;
        }

        if (pollRunnable != null) {
            mainHandler.removeCallbacks(
                    pollRunnable
            );
        }

        pollRunnable =
                new Runnable() {

                    @Override
                    public void run() {

                        if (!polling.get()) {
                            return;
                        }

                        req.timestamp =
                                System.currentTimeMillis();

                        fillBatchSignature(req);

                        Log.e(TAG, "activation polling...");

                        apiClient.activation(
                                req,
                                new ClaimApiClient.Callback<ApiResponse<ActivationData>>() {

                                    @Override
                                    public void onSuccess(
                                            ApiResponse<ActivationData> resp
                                    ) {

                                        if (!polling.get()) {
                                            return;
                                        }

                                        logApiResponse(
                                                "activation polling",
                                                resp
                                        );


                                        if (resp == null ||
                                                resp.data == null) {
                                            Log.e(
                                                    TAG,
                                                    "activation polling response empty"
                                                            + ", msg="
                                                            + (resp == null ? "null" : resp.msg)
                                            );

                                            scheduleNext();
                                            return;
                                        }

                                        boolean handled =
                                                handleActivationData(
                                                        context,
                                                        resp.data,
                                                        resp.msg,
                                                        resp.code,
                                                        resp.success,
                                                        callback
                                                );

                                        if (!handled) {

                                            mainHandler.post(
                                                    () -> callback.onWaitingClaim(
                                                            lastQrContent,
                                                            lastClaimCode
                                                    )
                                            );

                                            scheduleNext();
                                        }
                                    }

                                    @Override
                                    public void onFailure(
                                            Exception e
                                    ) {

                                        Log.e(TAG, "activation polling fail", e);

                                        if (!polling.get()) {
                                            return;
                                        }

                                        mainHandler.post(
                                                () -> callback.onWaitingClaim(
                                                        lastQrContent,
                                                        lastClaimCode
                                                )
                                        );

                                        scheduleNext();
                                    }

                                    private void scheduleNext() {

                                        if (!polling.get()) {
                                            return;
                                        }

                                        mainHandler.postDelayed(
                                                pollRunnable,
                                                5000
                                        );
                                    }
                                }
                        );
                    }
                };

        mainHandler.post(
                pollRunnable
        );
    }

    private boolean handleActivationData(
            Context context,
            ActivationData actData,
            String msg,
            int code,
            boolean success,
            Callback callback
    ) {

        Log.e(
                TAG,
                "activation result"
                        + ", code=" + code
                        + ", success=" + success
                        + ", msg=" + msg
                        + ", claimed=" + actData.claimed
                        + ", deviceNo=" + actData.deviceNo
                        + ", mqttBrokerUrl=" + actData.mqttBrokerUrl
                        + ", mqttClientId=" + actData.mqttClientId
                        + ", mqttUsername=" + actData.mqttUsername
                        + ", heartbeatInterval=" + actData.heartbeatInterval
                        + ", keepAliveSeconds=" + actData.keepAliveSeconds
        );

        if (!actData.claimed) {

            Log.e(
                    TAG,
                    "auto claim waiting"
                            + ", claimCode=" + lastClaimCode
                            + ", msg=" + msg
            );
            return false;
        }

        boolean credentialSaved =
                MqttCredentialStore.save(
                        context,
                        actData
                );

        if (!credentialSaved) {

            postError(
                    callback,
                    new Exception(
                            "activation claimed but mqtt credential invalid"
                    )
            );

            return false;
        }

        boolean claimSaved =
                ClaimStore.saveClaimed(
                        context
                );

        if (!claimSaved) {

            postError(
                    callback,
                    new Exception(
                            "save claimed flag failed"
                    )
            );

            return false;
        }

        polling.set(false);

        if (pollRunnable != null) {
            mainHandler.removeCallbacks(
                    pollRunnable
            );
        }

        mainHandler.post(
                () -> callback.onActivated(
                        actData
                )
        );

        return true;
    }

    private void postError(
            Callback callback,
            Exception e
    ) {

        Log.e(TAG, "claim error", e);

        mainHandler.post(
                () -> callback.onError(
                        e
                )
        );
    }

    private void logApiResponse(
            String name,
            ApiResponse<?> resp
    ) {

        if (resp == null) {

            Log.e(
                    TAG,
                    name + " api response null"
            );

            return;
        }

        Log.e(
                TAG,
                name
                        + " api response"
                        + ", code=" + resp.code
                        + ", success=" + resp.success
                        + ", msg=" + resp.msg
                        + ", dataNull=" + (resp.data == null)
        );
    }

    public void stop() {

        polling.set(false);

        if (pollRunnable != null) {
            mainHandler.removeCallbacks(
                    pollRunnable
            );
        }
    }
}