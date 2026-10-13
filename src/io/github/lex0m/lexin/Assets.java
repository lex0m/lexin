package io.github.lex0m.lexin;

import android.content.Context;
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
    private static volatile String apkPath;
    private static final String MODULE_PKG = "io.github.lex0m.lexin";

    /**
     * 由 Entry.initZygote 调用一次，APK 路径由框架给出。
     *
     * 但**不能只依赖框架给路径**：免 root 框架（LSPatch / Vector 之类）不一定调 initZygote，
     * 或者给的路径是空的；那种情况下以前会一路返回，asset 挂不上 → anchors.json 读不到 →
     * 所有功能安静失效。所以这里只记路径，真要用的时候再自己找。
     */
    static void init(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return;
        apkPath = modulePath;          // 先记下来，挂载失败也还有 zip 直读这条路
        mount(modulePath);
        Log.d(T + "init from framework: " + modulePath + " mounted=" + (am != null));
    }

    /** 拿不到 AssetManager 时的直读路径：把模块 APK 当 zip 打开。 */
    private static InputStream openDirect(String name) {
        String path = resolvePath();
        if (path == null) return null;
        try {
            java.util.zip.ZipFile zf = new java.util.zip.ZipFile(path);
            java.util.zip.ZipEntry e = zf.getEntry("assets/" + name);
            if (e == null) {
                zf.close();
                return null;
            }
            // ZipEntry 的流依赖 ZipFile，读完整段再关，避免流被抽走
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            InputStream raw = zf.getInputStream(e);
            byte[] buf = new byte[8192];
            int n;
            while ((n = raw.read(buf)) > 0) out.write(buf, 0, n);
            raw.close();
            zf.close();
            return new java.io.ByteArrayInputStream(out.toByteArray());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 模块 APK 路径：框架没给就自己去系统里查。
     * 模块本身是个普通已安装应用，PackageManager 一定查得到 —— 免 root 场景下这条路才是主力。
     */
    private static String resolvePath() {
        String p = apkPath;
        if (p != null && !p.isEmpty()) return p;
        try {
            Context c = Features.app;
            if (c == null) return null;
            p = c.getPackageManager().getApplicationInfo(MODULE_PKG, 0).sourceDir;
            if (p != null && !p.isEmpty()) {
                apkPath = p;
                mount(p);
                Log.d(T + "resolved from pm: " + p);
                return p;
            }
        } catch (Throwable t) {
            Log.d(T + "resolve failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private static void mount(String path) {
        try {
            AssetManager m = AssetManager.class.newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object r = addAssetPath.invoke(m, path);
            if (r instanceof Integer && ((Integer) r) != 0) am = m;
        } catch (Throwable ignored) {
            // 挂不上就走 openDirect
        }
    }

    /** 按宿主自己的短资源名加载主题素材，没有就返回 null */
    static Drawable drawable(String shortName) {
        if (shortName == null || shortName.isEmpty()) return null;
        if (am == null) resolvePath();
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

    /** 读模块 APK 里的文本资源（锚点表 anchors.json 走这里）。 */
    static String text(String path) {
        InputStream in = null;
        try {
            if (am == null) resolvePath();
            AssetManager m = am;
            if (m == null) in = openDirect(path);
            else in = m.open(path);
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
        }
    }

    /** 模块 APK 是否可用（面板用它判断能不能读锚点表）。 */
    static boolean ready() {
        if (am != null) return true;
        return resolvePath() != null;
    }

    private static void close(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
