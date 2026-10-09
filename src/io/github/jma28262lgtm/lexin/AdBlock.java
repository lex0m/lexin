package io.github.jma28262lgtm.lexin;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 去广告：只拦本地决策点。没抄常见开源做法（在渲染处过滤），而是在广告数据写下来之前拒绝。 */
final class AdBlock {

    static void install() throws Throwable {
        ClassLoader cl = Features.loader;
        int n = 0;

        // ── 开屏 / 小程序广告位 ──
        n += deny(cl, "com.tencent.mm.plugin.appbrand.d7", "K2");
        n += deny(cl, "lc1.j", "a");

        // ── 朋友圈评论广告：不构造那一行 ──
        n += skip(cl, "ah2.v", "h");
        n += skip(cl, "com.tencent.mm.plugin.finder.convert.w4", "X");

        Log.d("svc-ad: armed=" + n);
    }

    /** 所有 boolean 重载都答「否」，跳过宿主的方法体。 */
    private static int deny(ClassLoader cl, String owner, String name) {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(cl, owner);
            if (c == null) return 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            p.setResult(Boolean.FALSE);
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 这个版本里没有
        }
        return hits;
    }

    /** 让调用通过但跳过方法体，广告行就不会被组装。 */
    private static int skip(ClassLoader cl, String owner, String name) {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(cl, owner);
            if (c == null) return 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getReturnType() != void.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            p.setResult(null);
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }
}
