package io.github.lex0m.lexin;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 活性自检（v1.6.0）。
 *
 * 只回答一个问题：**框架的 hook 机制现在还活着吗？**
 *
 * 做法：hook 模块自己的 {@code Probe.pulse()}，然后每 15 秒反射调一次。
 *   · 反射调用 → R8 不会把调用点内联，hook 一定经过方法入口；
 *   · 命中数 +1  → hook 机制在用；
 *   · 命中数不动 → hook 被整体还原（热修替换类 / 反注入卸掉 trampoline）。
 *
 * 这不能证明某个具体功能还活着（那是 Probe 的逐功能命中数），但能一刀切开
 * 「功能没生效」和「整个模块被端了」——8.0.80 上这两种情况的表象完全一样。
 */
final class SelfCheck {

    private static final String F = "hook 活性";
    private static final long PERIOD = 15000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static volatile Method pulse;

    static void install() {
        try {
            // ★ 不能按名字找：R8 会把 Probe.pulse 改名（mapping 里是 -> le），
            //   以前 getDeclaredMethod("pulse") 直接抛 NoSuchMethodException ⇒
            //   自检 armed=0、面板永远「hook 活性 未命中」，判据本身失真。
            //   现在按签名找：static void、零参数 —— R8 改不了签名。
            Method m = findPulse();
            if (m == null) {
                Status.armed(F, 0);
                Status.note(F, "自检装不上：找不到 pulse");
                return;
            }
            m.setAccessible(true);
            pulse = m;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Probe.hit(F);
                }
            });
            Status.armed(F, 1);
            Status.note(F, "hook 机制在用");
            Log.d("svc-selfcheck: armed, period=" + PERIOD);
        } catch (Throwable t) {
            Status.armed(F, 0);
            Status.note(F, "自检装不上：" + t.getClass().getSimpleName());
            Storage.diag("selfcheck: " + t.getClass().getSimpleName());
            return;
        }
        H.postDelayed(tick, PERIOD);
    }

    /** 按签名（static void、零参数、声明在 Probe 里）找 pulse，避开 R8 改名。 */
    private static Method findPulse() {
        for (Method m : Probe.class.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != void.class) continue;
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            return m;
        }
        return null;
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                int before = Probe.hits(F);
                Method m = pulse;
                Log.d("svc-selfcheck: tick before=" + before + " pulse=" + (m != null));
                if (m != null) m.invoke(null);      // 反射调用：绕过内联
                int after = Probe.hits(F);
                Log.d("svc-selfcheck: tick after=" + after);
                if (after == before && before > 0) {
                    // 之前命中过、这次没命中 → 不是"没被调用"，是 hook 被拿掉了
                    Status.note(F, "hook 机制被还原");
                    Storage.diag("selfcheck: hook callback lost");
                } else if (after > before) {
                    Status.note(F, "hook 机制在用");
                }
            } catch (Throwable ignored) {
                // 自检本身绝不打扰宿主
            }
            H.postDelayed(this, PERIOD);
        }
    };

    private SelfCheck() {}
}
