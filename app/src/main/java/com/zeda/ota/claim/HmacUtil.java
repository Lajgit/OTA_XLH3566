package com.zeda.ota;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HmacUtil {

    private static final String TAG = "OTA_TEST";

    private static final String HMAC_SHA256 = "HmacSHA256";

    public static String hmacSha256Base64Url(
            String text,
            String secret
    ) {

        try {

            Mac mac =
                    Mac.getInstance(
                            HMAC_SHA256
                    );

            mac.init(
                    new SecretKeySpec(
                            secret.getBytes(
                                    StandardCharsets.UTF_8
                            ),
                            HMAC_SHA256
                    )
            );

            byte[] signatureBytes =
                    mac.doFinal(
                            text.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

            return Base64.encodeToString(
                    signatureBytes,
                    Base64.URL_SAFE
                            | Base64.NO_WRAP
                            | Base64.NO_PADDING
            );

        } catch (Throwable e) {

            Log.e(TAG, "hmacSha256Base64Url fail", e);

            return "";
        }
    }
}