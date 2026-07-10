package com.zeda.ota;

import android.content.Context;
import android.provider.Settings;

import java.util.UUID;

public class DeviceUtil {

    public static String getDeviceId(Context context) {

        String id = "";

        try {
            id = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Throwable ignored) {
        }

        if (id == null || id.trim().isEmpty()) {
            id = PendingStore.get(context, "fallback_device_id");
        }

        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
            PendingStore.save(context, "fallback_device_id", id);
        }

        return id
                .toUpperCase()
                .replace(":", "")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }
}
