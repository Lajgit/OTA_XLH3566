package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class WifiCredentialStore {

    private static final String TAG = "OTA_TEST";
    private static final String PREF = "wifi_credential";

    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";

    public static void save(
            Context context,
            String ssid,
            String password
    ) {

        if (ssid == null ||
                ssid.trim().isEmpty()) {

            Log.e(TAG, "WifiCredentialStore save fail: ssid empty");
            return;
        }

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                        KEY_SSID,
                        ssid
                )
                .putString(
                        KEY_PASSWORD,
                        password == null ? "" : password
                )
                .apply();

        Log.e(
                TAG,
                "WifiCredentialStore save ssid="
                        + ssid
        );
    }

    public static String getSsid(
            Context context
    ) {

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_SSID,
                        ""
                );
    }

    public static String getPassword(
            Context context
    ) {

        return context.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .getString(
                        KEY_PASSWORD,
                        ""
                );
    }

    public static boolean hasSavedWifi(
            Context context
    ) {

        String ssid =
                getSsid(
                        context
                );

        return ssid != null &&
                !ssid.trim().isEmpty();
    }

    public static void clear(
            Context context
    ) {

        context.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .apply();

        Log.e(TAG, "WifiCredentialStore cleared");
    }
}
