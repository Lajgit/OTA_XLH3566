package com.zeda.ota;

import android.content.Context;

public class OtaManager {

    public static void start(
            Context context,
            String version,
            String url,
            String md5
    ) {

        android.util.Log.e("OTA_TEST", "ota start");
        android.util.Log.e("OTA_TEST", "download url = " + url);

    }
}