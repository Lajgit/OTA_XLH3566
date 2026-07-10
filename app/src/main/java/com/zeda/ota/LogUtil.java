package com.zeda.ota;

import android.util.Log;

public class LogUtil {

    public static void d(String msg) {
        Log.d("OTA_APP", msg);
    }

    public static void e(String msg) {
        Log.e("OTA_APP", msg);
    }
}