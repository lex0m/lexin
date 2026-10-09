package com.sysprobe.svc;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.lang.reflect.Method;

/** 直接从模块 APK 里读资源。以前把主题图放进微信自己的存储，能用，但等于在对方目录留下文件。 */
final class Assets {

    private static final String T = "svc-assets: ";
    private static final String ROOT = "theme/";

    private static volatile AssetManager am;

    /** 由 Entry.initZygote 调用一次，APK 路径由框架给出。 */
    static void init(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return;
        try {
            AssetManager m = AssetManager.class.newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object r = addAssetPath.invoke(m, modulePath);
            if (r instanceof Integer && ((Integer) r) != 0) {
                am = m;
                Log.d(T + "mounted");
            }
        } catch (Throwable t) {
            Log.d(T + "mount failed: " + t.getClass().getSimpleName());
        }
    }

    /** 按宿主自己的短资源名加载主题素材，没有就返回 null */
    static Drawable drawable(String shortName) {
        if (shortName == null || shortName.isEmpty()) return null;
        AssetManager m = am;
        if (m == null) return null;

        for (String ext : new String[]{"", ".png", ".webp", ".jpg"}) {
            InputStream in = null;
            try {
                in = m.open(ROOT + shortName + ext);
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) return new BitmapDrawable(null, bmp);
            } catch (Throwable ignored) {
                // 试下一个扩展名
            } finally {
                close(in);
            }
        }
        return null;
    }

    /** APK 里是否有这个名字的主题素材。 */
    static boolean has(String shortName) {
        return drawable(shortName) != null;
    }

    private static void close(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
