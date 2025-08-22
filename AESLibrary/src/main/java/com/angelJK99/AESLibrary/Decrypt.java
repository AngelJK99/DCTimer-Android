package com.angelJK99.AESLibrary;

import android.util.Log;

import java.util.Arrays;

public class Decrypt {
    private static AES128 aes128;

    private static final byte[][] KEYS = { { -58, -54, 21, -33, 79, 110, 19, -74, 119, 13, -26, 89, 58, -81, -70, -94 },
            { 67, -30, 91, -42, 125, -36, 120, -40, 7, 96, -93, -38, -126, 60, 1, -15 } };

    public static void initAES(byte[] key) {
        aes128 = new AES128(key);
    }
    public static byte[] getKey(int version, byte[] value) {
        int index = version >> 8 & 0xff;
        if (index > 1) return null;
        byte[] key = Arrays.copyOf(KEYS[index], KEYS[index].length);
        for (int i = 0; i < 6; i++) {
            key[i] = (byte) (key[i] + (value[5 - i] & 0xff));
        }
        return key;
    }



    public static byte[] decode(byte[] value) {
        byte[] ret = Arrays.copyOf(value, value.length);
        if (aes128 == null) {
            Log.e("dct", "aes key为null");
            return ret;
        }
        try {
            if (ret.length > 16) {
                byte[] slice = Arrays.copyOfRange(ret, ret.length - 16, ret.length);
                aes128.decrypt(slice);
                System.arraycopy(slice, 0, ret, ret.length - 16, 16);
            }
            aes128.decrypt(ret);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ret;
    }

    public static byte[] toHexValue(byte[] value) {
        byte[] raw = Arrays.copyOf(value, 20);
        if (raw[18] == -89) { // decrypt
            int[] key = {176, 81, 104, 224, 86, 137, 237, 119, 38, 26, 193, 161, 210, 126, 150, 81, 93, 13, 236, 249, 89, 235, 88, 24, 113, 81, 214, 131, 130, 199, 2, 169, 39, 165, 171, 41};
            int k1 = raw[19] >> 4 & 0xf;
            int k2 = raw[19] & 0xf;
            for (int i = 0; i < 18; i++) {
                raw[i] += (byte) (key[i + k1] + key[i + k2]);
            }
            raw = Arrays.copyOf(raw, 18);
        }
        byte[] valhex = new byte[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            valhex[i * 2] = (byte) (raw[i] >> 4 & 0xf);
            valhex[i * 2 + 1] = (byte) (raw[i] & 0xf);
        }
        return valhex;
    }
}
