package com.zeda.ota;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;

import java.util.Map;
import java.util.HashMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okhttp3.Callback;
import okhttp3.Call;
import okhttp3.Response;
import java.io.IOException;

public class ClaimApiClient {

    private static final String TAG = "OTA_CLAIM_API";
    private static final String BASE_URL = "https://api.dzxd.top";

    private final OkHttpClient client;
    private final Gson gson = new Gson();

    public ClaimApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public interface Callback<T> {
        void onSuccess(T data);
        void onFailure(Exception e);
    }

    public void enroll(EnrollData req, Callback<ApiResponse<EnrollData>> cb) {
        postJson("/api/device/enroll", req, new TypeToken<ApiResponse<EnrollData>>(){}.getType(), cb);
    }

//    public void activation(String deviceNo, ActivationRawCallback callback) {
//        // 构建请求体
//        Map<String, Object> params = new HashMap<>();
//        params.put("deviceNo", deviceNo);
//        params.put("firmwareVersion", getFirmwareVersion());
//        params.put("apkVersion", getApkVersion());
//        params.put("nonce", generateNonce());
//        params.put("timestamp", System.currentTimeMillis());
//        params.put("signature", "optional-signature");
//
//        String json = new Gson().toJson(params);
//
//        OkHttpClient client = new OkHttpClient();
//        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
//        Request request = new Request.Builder()
//                .url(BASE_URL + "/api/device/activation")
//                .post(body)
//                .build();
//
//        client.newCall(request).enqueue(new Callback() {
//            @Override
//            public void onFailure(Call call, IOException e) {
//                if (callback != null) {
//                    callback.onError(e);
//                }
//            }
//
//            @Override
//            public void onResponse(Call call, Response response) throws IOException {
//                if (callback == null) return;
//
//                String responseBody = response.body().string();
//
//                // 直接返回字符串，无解析
//                callback.onSuccess(responseBody);
//            }
//        });
//    }
//    // 回调接口
//    public interface ActivationRawCallback {
//        void onSuccess(String rawResponse);
//        void onError(Exception e);
//    }
    public void activation(EnrollData req, Callback<ApiResponse<ActivationData>> cb) {
        postJson("/api/device/activation", req, new TypeToken<ApiResponse<ActivationData>>(){}.getType(), cb);
    }

    private <T> void postJson(String path, Object body, Type type, Callback<T> cb){
        try {
            String json = gson.toJson(body);
            RequestBody requestBody = RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(BASE_URL + path)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    cb.onFailure(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody rb = response.body()) {
                        if (!response.isSuccessful() || rb == null) {
                            cb.onFailure(new IOException("HTTP " + response.code()));
                            return;
                        }
                        String respStr = rb.string();
                        Log.e(TAG, "resp=" + respStr);
                        T data = gson.fromJson(respStr, type);
                        cb.onSuccess(data);
                    } catch (Exception e) {
                        cb.onFailure(e);
                    }
                }
            });
        } catch (Exception e) {
            cb.onFailure(e);
        }
    }
}