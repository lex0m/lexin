package io.github.lex0m.lexin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 帮用户选图并存到 hook 侧能读的位置（面板跑在宿主进程里，存图位置要两边都可达）。 */
final class ImagePicker {

    private static final String T = "svc-ui: ";
    private static final int REQ = 0x5C01;      // our request code

    /** Where picked images live. */
    static final String DIR = "/sdcard/Android/data/com.tencent.mm/files/mm_res";
    private static final String CHAT_NAME = "user_chat_bg.img";
    private static final String HOME_NAME = "user_home_bg.img";

    private static volatile boolean hooked = false;
    private static volatile String pendingTarget = null;

    /** Copies into the shared dir; returns the absolute path or null. */
    static String pathFor(String which) {
        return DIR + "/" + CHAT_NAME;
    }

    static void pick(final Activity host, final String which) {
        if (host == null) return;
        try {
            ensureHook();
            pendingTarget = which;
            // 用 ACTION_GET_CONTENT（与 Wex 一致）：ACTION_OPEN_DOCUMENT 需要文档 Provider 响应，实测取不到图
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            host.startActivityForResult(i, REQ);
        } catch (Throwable t) {
            pendingTarget = null;
            toast(host, "无法打开选择器：" + t.getClass().getSimpleName());
        }
    }

    /** Global hook: the result comes back to the HOST activity, not to us. */
    private static void ensureHook() {
        if (hooked) return;
        synchronized (ImagePicker.class) {
            if (hooked) return;
            try {
                for (java.lang.reflect.Method m : Activity.class.getDeclaredMethods()) {
                    if (!"onActivityResult".equals(m.getName())) continue;
                    if (m.getParameterCount() != 3) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!(p.args[0] instanceof Integer)) return;
                                if (((Integer) p.args[0]) != REQ) return;
                                int result = p.args[1] instanceof Integer
                                        ? (Integer) p.args[1] : 0;
                                Intent data = p.args[2] instanceof Intent
                                        ? (Intent) p.args[2] : null;
                                onResult((Activity) p.thisObject, result, data);
                            } catch (Throwable ignored) {
                                pendingTarget = null;
                            }
                        }
                    });
                    hooked = true;
                    break;
                }
            } catch (Throwable ignored) {
                // 选择器不可用
            }
        }
    }

    private static void onResult(final Activity host, int result, Intent data) {
        final String which = pendingTarget;
        pendingTarget = null;
        if (result != Activity.RESULT_OK || data == null || which == null) return;

        Uri src = data.getData();
        if (src == null) return;

        try {
            // 读取选中的图：Android 13+ 上 ACTION_GET_CONTENT 由系统 PhotoPicker 提供，它的授权只覆盖该次回调
            InputStream in = null;
            String how = "openInputStream";
            try {
                in = host.getContentResolver().openInputStream(src);
            } catch (Throwable t) {
                how = "openInputStream(" + t.getClass().getSimpleName() + ")";
            }
            if (in == null) {
                try {
                    android.content.res.AssetFileDescriptor fd =
                            host.getContentResolver().openAssetFileDescriptor(src, "r");
                    if (fd != null) {
                        in = fd.createInputStream();
                        how = "openAssetFileDescriptor";
                    }
                } catch (Throwable t) {
                    how = "openAssetFileDescriptor(" + t.getClass().getSimpleName() + ")";
                }
            }
            if (in == null) {
                toast(host, "无法读取所选图片（" + how + "）");
                Log.d(T + "read failed via " + how + " uri=" + src);
                return;
            }

            // 面板跑在微信进程里，所以选中的图直接放进该进程自己的私有目录
            String targetFile = Beautify.K_CARD_IMG.equals(which)
                    ? Beautify.CARD_FILE : Beautify.BG_FILE;
            java.io.File out = new java.io.File(host.getFilesDir(), targetFile);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            if (out.length() <= 0) {
                toast(host, "保存失败");
                return;
            }
            Log.d(T + "background saved via " + how + " -> " + out.length() + "B");

            Storage.setStr(which, "host");
            Storage.setOn(Storage.K_THEME, true);
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    toast(host, "已设置，重进界面生效");
                }
            });
        } catch (Throwable t) {
            Log.d(T + "save failed: " + t.getClass().getSimpleName());
            toast(host, "保存失败：" + t.getClass().getSimpleName());
        }
    }

    private static void toast(Context ctx, String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
