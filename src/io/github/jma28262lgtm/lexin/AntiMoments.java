package io.github.jma28262lgtm.lexin;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 朋友圈保护：和防撤回同一思路——在本地那个删除调用处直接拒绝，内容就留下来了。 */
final class AntiMoments {

    static void install() throws Throwable {
        ClassLoader cl = Features.loader;
        int n = 0;

        n += denyBoolean(cl, "com.tencent.mm.plugin.sns.storage.w1", "t1");
        n += denyBoolean(cl, "com.tencent.mm.plugin.sns.storage.p2", "A");
        n += denyBoolean(cl, "com.tencent.mm.plugin.sns.storage.p2", "H");

        Log.d("svc-moments: armed gates=" + n);
    }

    /** 直接拦掉那个删除调用。 */
    private static int denyBoolean(ClassLoader cl, String owner, String name) {
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
                            // 回一个「没删任何东西」，并跳过方法体。
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
}
