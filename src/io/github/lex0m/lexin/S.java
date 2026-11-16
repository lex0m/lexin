package io.github.lex0m.lexin;

import android.util.Base64;

/** 字符串表：类名片段不落明文，避免被直接扫出来。 */
final class S {
    static final String CHAT = d("GTI7Li4zND0=");
    static final String CHAT_UI = d("GTI7Li4zND0PExY7IzUvLg==");
    static final String CHAT_SCROLL = d("GTI7Li4zND0JOSg1NjYWOyM1Ly4=");
    static final String CHAT_IMG_BG = d("GTI7Li4zND0TNzs9PxgdDDM/LQ==");
    static final String MMCHAT_LIST = d("FxcZMjsuLjM0PRYzKS4MMz8t");
    static final String TBAR_BOTTOM = d("DjspMRg7KBg1Li41NwwzPy0=");
    static final String TBAR_CONTAINER = d("DjspMRg7KBk1NC47MzQ/KA==");
    static final String TBAR = d("DjspMRg7KA==");
    static final String ABRAND_DESK = d("GyoqGCg7ND4ePykxLjUqGTU0LjszND8oDDM/LQ==");
    static final String ABRAND = d("GyoqGCg7ND4=");
    static final String ABAR_CONTAINER = d("GzkuMzU0GDsoGTU0LjszND8o");
    static final String ABAR_OVERLAY = d("GzkuMzU0GDsoFSw/KDY7IxY7IzUvLg==");
    static final String ABAR_VIEW = d("GzkuMzU0GDsoDDM/LQ==");
    static final String ABAR = d("GzkuMzU0GDso");
    static final String BTAB_VIEW = d("FjsvNDkyPygPExg1Li41Nw47OAwzPy0=");
    static final String BTAB_SHORT = d("FjsvNDkyPygPExg1Li41Nw47OA==");
    static final String BTAB = d("GDUuLjU3Djs4");
    static final String LAUNCHER = d("FjsvNDkyPygPEw==");
    static final String BOUNCE = d("FxcNPw8TGDUvNDk/DDM/LQ==");
    static final String LAYOUT_LISTENER = d("FjsjNS8uFjMpLj80PygMMz8t");
    static final String MULTITASK = d("KjYvPTM0dDcvNi4zLjspMQ==");
    static final String CLS_CONV_LIST = d("OTU3dC4/NDk/NC50Nzd0LzN0OTU0LD8oKTsuMzU0dBk1NCw/KCk7LjM1NBYzKS4MMz8t");
    static final String CLS_CONV_RECYCLER = d("OTU3dC4/NDk/NC50Nzd0LzN0OTU0LD8oKTsuMzU0dCg/OSM5Nj8odBk1NCw/KCk7LjM1NAg/OSM5Nj8oDDM/LQ==");
    static final String CLS_BOUNCE = d("OTU3dC4/NDk/NC50Nzd0LzN0LTM+PT8udCovNjY+NS00dBcXDT8PExg1LzQ5PwwzPy0=");
    static final String CLS_LAYOUT_LISTENER = d("OTU3dC4/NDk/NC50Nzd0LzN0FjsjNS8uFjMpLj80PygMMz8t");
    static final String CLS_LAUNCHER = d("OTU3dC4/NDk/NC50Nzd0LzN0FjsvNDkyPygPEw==");
    static final String CLS_BTAB = d("OTU3dC4/NDk/NC50Nzd0LzN0FjsvNDkyPygPExg1Li41Nw47OAwzPy0=");
    static final String CLS_TBAR_BOTTOM = d("OTU3dC4/NDk/NC50Nzd0KjYvPTM0dC47KTE4Oyh0LzN0DjspMRg7KBg1Li41NwwzPy0=");
    static final String CLS_ABAR = d("OzQ+KDUzPiJ0OyoqOTU3KjsudC0zPj0/LnQbOS4zNTQYOygZNTQuOzM0Pyg=");
    static final String CLS_HOME = d("OTU3dC4/NDk/NC50Nzd0LzN0EjU3Pw8T");
    static final String CLS_FRAGMENT = d("OzQ+KDUzPiJ0PCg7PTc/NC50OyoqdBwoOz03PzQu");

    static final String WX_P16 = d("dSk+OTsoPnUbND4oNTM+dT47Ljt1OTU3dC4/NDk/NC50Nzd1PDM2Pyl1NzcFKD8p");
    static final String WX_P17 = d("dSkuNSg7PT91PzcvNjsuPz51anUbND4oNTM+dT47Ljt1OTU3dC4/NDk/NC50Nzd1PDM2Pyl1NzcFKD8p");
    static final String WX_P18 = d("dSk+OTsoPnUbND4oNTM+dT47Ljt1OTU3dC4/NDk/NC50Nzd1PDM2Pyl1dCg/KQ==");
    static final String WX_P19 = d("OTU3dC4/NDk/NC50NzcFKig/PD8oPzQ5Pyk=");
    static final String WX_P12 = d("OTU3dC4/NDk/NC50Nzd0LzN0Lig7NCk3My50CT82PzkuGTU0LD8oKTsuMzU0DxM=");
    static final String WX_P13 = d("OTU3dC4/NDk/NC50Nzd0LzN0FjsvNDkyPygPExg1Li41Nw47OAwzPy0=");
    static final String WX_P14 = d("OTU3dC4/NDk/NC50Nzd0KjYvPTM0dCk/Li4zND10LzN0KT8uLjM0PXQJPy4uMzQ9KRk7KD8XNT4/EzQuKDU=");
    static final String WX_P15 = d("OTU3dC4/NDk/NC50Nzd0LzN0LjU1Nil0CTI7KD8TNz0PEw==");
    static final String WX_P9 = d("OTU3dC4/NDk/NC50Nzd0LzN0OTI7Li4zND10OTU3KjU0PzQudDtr");
    static final String WX_P10 = d("OTU3dC4/NDk/NC50Nzd0LzN0OTI7Li4zND10Oz47Ki4/KHQx");
    static final String WX_P11 = d("OTU3dC4/NDk/NC50Nzd0LzN0OTI7Li4zND10OTU3KjU0PzQudCxo");
    static final String WX_P1 = d("OTU3dC4/NDk/NC50Nzd0LzN0OTI7Li4zND10LDM/LTMuPzcp");
    static final String WX_P2 = d("OTU3dC4/NDk/NC50Nzd0KS41KDs9P3Q/Yw==");
    static final String WX_P3 = d("OTU3dC4/NDk/NC50Nzd0LzN0OTI7Li4zND10OTU3KjU0PzQu");
    static final String WX_P4 = d("OTU3dC4/NDk/NC50Nzd0KS41KDs9P3Q=");
    static final String WX_P5 = d("OTU3dC4/NDk/NC50Nzd0KjYvPTM0KT4xdC8zdDkyOy50GTI7Lhw1NS4/KA==");
    static final String WX_P6 = d("OTU3dC4/NDk/NC50Nzd0OyozdBMfNzUwMxM0PDU=");
    static final String WX_P7 = d("OTU3dC4/NDk/NC50Nzd0KjYvPTM0dC4/Ii4pLjsuLyl0LzN0Dj8iLgkuOy4vKR41DTI7Lhs5LjMsMy4jDGg=");
    static final String WX_P8 = d("OTU3dC4/NDk/NC50Nzd0MzQuPzQudBsZDhMVFAUXFwUPEwUSExQO");

    static String d(String s) {
        try {
            byte[] b = Base64.decode(s, Base64.DEFAULT);
            for (int i = 0; i < b.length; i++) b[i] ^= 0x5A;
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
