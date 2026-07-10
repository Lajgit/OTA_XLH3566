package com.zeda.ota.wifi;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public class QrUtil {

    public static Bitmap create(String text, int size) {

        try {

            BitMatrix matrix =
                    new MultiFormatWriter().encode(
                            text,
                            BarcodeFormat.QR_CODE,
                            size,
                            size
                    );

            Bitmap bitmap =
                    Bitmap.createBitmap(
                            size,
                            size,
                            Bitmap.Config.RGB_565
                    );

            for (int x = 0; x < size; x++) {

                for (int y = 0; y < size; y++) {

                    bitmap.setPixel(
                            x,
                            y,
                            matrix.get(x, y)
                                    ? Color.BLACK
                                    : Color.WHITE
                    );
                }
            }

            return bitmap;

        } catch (Exception e) {

            e.printStackTrace();
        }

        return null;
    }
}