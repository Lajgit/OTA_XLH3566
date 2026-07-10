package com.zeda.ota;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class Md5Util {

    public static boolean check(File file, String md5) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance("MD5");

            FileInputStream fis =
                    new FileInputStream(file);

            byte[] buffer = new byte[4096];

            int len;

            while ((len = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }

            fis.close();

            byte[] bytes = digest.digest();

            StringBuilder sb = new StringBuilder();

            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString().equalsIgnoreCase(md5);

        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}