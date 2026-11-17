package io.github.lex0m.lexin;

import android.view.GestureDetector;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 拍一拍双击入口探针（v1.7.2，只在诊断模式工作）。
 *
 * 为什么需要它：微信 8.0.80 的拍一拍发送链（`PatMsgExtension` / CGI 849 `sendpat`）
 * 在 Tinker 补丁里，静态扫不到；UI 层能看到的 `AvatarPatImageView` 也不是真正的双击入口
 * （挂它 `getDoubleClickListener()` 返回 null，真机照常拍得出去）。
 *
 * 做法：**不认类名**，认"双击一定来自 GestureDetector"这个结构性事实 ——
 *   hook `GestureDetector` 的所有构造函数 → 反射拿 `mListener` 字段 →
 *   给这个 listener 的 `onDoubleTap(MotionEvent)` 挂一笔。
 * 你双击一次头像，这里就会把**真实类名**写进 status.txt。
 */
final class PatProbe {

    private static final String T = "svc-pprobe: ";
    static final String NAME = "拍一拍探针";

    /** 已经挂过的 listener 类（避免同一个类重复挂）。 */
    private static final Set<String> hooked = new HashSet<>();
    /** 强引用住 listener，防止被 GC 掉导致 hook 失效。 */
    private static final Set<Object> refs = new HashSet<>();

    static void install() {
        if (!Log.runtimeOn()) return;
        if (Status.ok(NAME)) return;
        int n = 0;
        try {
            Class<?> gd = Class.forName("android.view.GestureDetector", false,
                    PatProbe.class.getClassLoader());
            XposedBridge.hookAllConstructors(gd, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object listener = field(p.thisObject, "mListener");
                        if (listener == null) return;
                        hookListener(listener);
                    } catch (Throwable ignored) {
                    }
                }
            });
            n++;
        } catch (Throwable t) {
            Log.d(T + "GestureDetector hook failed: " + t);
        }
        Status.armed(NAME, n);
        Status.note(NAME, n > 0
                ? "已装 · 双击一次头像就能看到真实类名"
                : "装不上（框架类不可 hook）");
    }

    private static void hookListener(final Object listener) {
        final String cls = listener.getClass().getName();
        synchronized (hooked) {
            if (!hooked.add(cls)) return;
            refs.add(listener);          // 防 GC
        }
        // 沿父类链找 onDoubleTap(MotionEvent)，它能返回 void 或 boolean
        for (Class<?> c = listener.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!"onDoubleTap".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != android.view.MotionEvent.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Status.note(NAME, "双击命中：" + shortName(cls) + "#" + m.getName()
                                        + " → " + c2s(p.getResult()));
                                Status.hit(NAME);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    Log.d(T + "hooked " + cls + "#" + m.getName());
                    return;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
    }

    private static String c2s(Object o) {
        if (o == null) return "void/null";
        return String.valueOf(o);
    }

    private static String shortName(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? n : n.substring(d + 1);
    }

    private static Object field(Object o, String name) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private PatProbe() {}
}
