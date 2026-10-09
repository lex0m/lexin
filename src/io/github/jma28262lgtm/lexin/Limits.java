package io.github.jma28262lgtm.lexin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 本地限制放宽：只改宿主在发送前检查的客户端上限，不发请求、不改协议。 */
final class Limits {

    /** Effectively "no cap" while staying inside int range for arithmetic. */
    private static final int WIDE = 9999;

    static void install() throws Throwable {
        ClassLoader cl = Features.loader;
        int n = 0;

        n += widenMethod(cl, "qr.z", "a");
        n += widenMethod(cl, "com.tencent.mm.ui.chatting.adapter.k", "Z0");
        n += widenField(cl, "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2", "p1");

        Log.d("svc-limits: armed=" + n);
    }

    /** Forces every no-arg int getter of that name to report WIDE. */
    private static int widenMethod(ClassLoader cl, String owner, String name) {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(cl, owner);
            if (c == null) return 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getReturnType() != int.class || m.getParameterCount() != 0) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (r instanceof Integer && ((Integer) r) < WIDE) {
                                p.setResult(WIDE);
                            }
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

    /** Rewrites the value read out of an int field. */
    private static int widenField(ClassLoader cl, String owner, String fieldName) {
        try {
            Class<?> c = Silent.cls(cl, owner);
            if (c == null) return 0;
            Field f = Silent.fieldDeep(c, fieldName);
            if (f == null || f.getType() != int.class) return 0;
            // 字段没法 hook，改成在类加载时固定住
            final Field target = f;
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (((Integer) target.get(p.thisObject)) < WIDE) {
                            target.set(p.thisObject, WIDE);
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            });
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
