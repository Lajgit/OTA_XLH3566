package com.zeda.ota;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class ClaimStore {

    private static final String TAG = "OTA_TEST";
    private static final String PREF = "claim";

    public static boolean isClaimed(Context c) {

        boolean claimed =
                c.getApplicationContext()
                        .getSharedPreferences(
                                PREF,
                                Context.MODE_PRIVATE
                        )
                        .getBoolean(
                                "claimed",
                                false
                        );

        Log.e(TAG, "ClaimStore isClaimed=" + claimed);

        return claimed;
    }

    public static boolean saveClaimed(Context c) {

        boolean ok =
                c.getApplicationContext()
                        .getSharedPreferences(
                                PREF,
                                Context.MODE_PRIVATE
                        )
                        .edit()
                        .putBoolean(
                                "claimed",
                                true
                        )
                        .commit();

        Log.e(TAG, "ClaimStore saveClaimed result=" + ok);

        return ok;
    }

    public static void clear(Context c) {

        c.getApplicationContext()
                .getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                )
                .edit()
                .clear()
                .commit();

        Log.e(TAG, "ClaimStore cleared");
    }
}