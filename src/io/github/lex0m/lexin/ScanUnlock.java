package io.github.lex0m.lexin;

import android.app.Activity;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 解除扫码限制（改写思路照抄 WeKit 的 RemoveQrCodeScanLimit）。
 *
 * 微信在「从相册选二维码」和「长按图片识别二维码」两条路上做了限制，部分二维码不给用。
 * 微信内部处理扫码结果的方法带两个场景参数：
 *   source     相机 0 / 相册 1 / 长按图片 4
 *   a8KeyScene 相机 4 / 相册 34 / 长按图片 37
 * 把「相册」「长按图片」的场景改写成「相机」，后面的限制判断就都不成立了 ——
 * 既不用改判断条件，也不用碰任何网络/数据接口。
 *
 * 那个方法是混淆名，靠"方法里引用了哪两个字符串"来定位（生成的版本表里已经写好），
 * 运行时会再用参数形状校验一遍：15/16 个参数、[0] 是 Activity、[1] 是 String。
 */
final class ScanUnlock {

    private static final String T = "svc-scan2: ";

    private static final int[] SOURCE = {0, 1, 4};        // 相机 / 相册 / 长按图片
    private static final int[] SCENE = {4, 34, 37};

    static void install() {
        Class<?> owner = Anchors.cls(Anchors.QR_SCAN);
        if (owner == null) {
            Status.set("解除扫码限制", false);
            return;
        }
        Method target = findTarget(owner);
        if (target == null) {
            Status.set("解除扫码限制", false);
            Log.d(T + "signature not found in " + owner.getName());
            return;
        }
        final boolean wide = target.getParameterTypes().length == 16;
        try {
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_SCAN)) return;
                        int si = wide ? 3 : 2;
                        int ci = si + 1;
                        int source = Silent.asInt(param.args[si]);
                        int scene = Silent.asInt(param.args[ci]);
                        for (int i = 1; i < SOURCE.length; i++) {     // 从 1 开始：相册 / 长按图片
                            if (source == SOURCE[i] && scene == SCENE[i]) {
                                param.args[si] = SOURCE[0];           // 改成"相机"
                                param.args[ci] = SCENE[0];
                                Log.d(T + "scene " + source + "/" + scene + " -> camera");
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 不改就是原行为
                    }
                }
            });
            Status.set("解除扫码限制", true);
            Status.note("解除扫码限制", "相册/长按图片识别按相机场景处理");
            Log.d(T + "armed -> " + owner.getName() + "#" + target.getName() + " (" + paramCount(target) + " 参)");
        } catch (Throwable t) {
            Status.set("解除扫码限制", false);
        }
    }

    /** 15/16 参、[0]=Activity、[1]=String 的那个方法。 */
    private static Method findTarget(Class<?> owner) {
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 15 && p.length != 16) continue;
            if (p[0] != Activity.class || p[1] != String.class) continue;
            return m;
        }
        return null;
    }

    private static int paramCount(Method m) {
        return m.getParameterTypes().length;
    }

    private ScanUnlock() {}
}
