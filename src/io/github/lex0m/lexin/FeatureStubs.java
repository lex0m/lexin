package io.github.lex0m.lexin;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 反取证（v1.5）。
 *
 * 微信自己装了三个探针，每一个都会把我们这套 hook 照出来：
 *   ① 栈帧自检 —— TinkerCrashProtect 遍历调用栈找 {@code de.robv...XposedBridge}，
 *      命中就记 "have xposed" 并清掉自己的补丁（8.0.80 = com.tencent.mm.app.j3#a）。
 *   ② Matrix checkHook —— 往 libwechatcrash / libwxperf-tkill / libtrace-canary /
 *      libwechatbacktrace 里装 native 自查，看这些 so 有没有被改过（8.0.80 = pv0.u0#a）。
 *   ③ 包名自证 —— sysmsg 里的 ClientCheckGetAppList 会让客户端上报已安装应用列表，
 *      模块自己的包名在里面等于自报家门。这里让微信查不到我们这一个包。
 *
 * 三个都按锚点槽位定位，微信改名不影响；一个装不上不影响另外两个。
 * **必须随启动装**（不能放 defer）：①②都在启动阶段就可能跑过一次。
 */
final class Stealth {

    private static final String T = "svc-stealth: ";

    /** 模块自己的包名（唯一要在微信眼里"不存在"的东西）。 */
    private static final String OURS = "io.github.lex0m.lexin";

    static void install() throws Throwable {
        int x = stackCheck();
        int m = matrixCheck();
        int p = hideOwnPackage();
        int n = x + m + p;
        Status.set("环境隐蔽", n > 0);
        Status.note("环境隐蔽", n > 0
                ? "栈帧自检 " + x + " · native 自查 " + m + " · 包名隐藏 " + p
                : "当前微信版本未适配");
        Log.d(T + "armed stack=" + x + " matrix=" + m + " pkg=" + p);
    }

    // ── ① 栈帧自检：让「有没有 Xposed」永远答 false ────────────────────────

    private static int stackCheck() {
        int n = 0;
        for (final Class<?> c : Anchors.all(Anchors.XPOSED_CHECK)) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != StackTraceElement[].class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                                p.setResult(Boolean.FALSE);
                            } catch (Throwable ignored) {
                                // 不打扰宿主
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                    // 下一个方法
                }
            }
        }
        return n;
    }

    // ── ② Matrix native 自查：不让它去查 so ────────────────────────────────

    private static int matrixCheck() {
        String mn = Anchors.methodNameOf(Anchors.entryWithMethod(Anchors.MATRIX_HOOKCHECK));
        int n = 0;
        for (final Class<?> c : Anchors.all(Anchors.MATRIX_HOOKCHECK)) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() != void.class || m.getParameterCount() != 0) continue;
                if (mn != null && !mn.isEmpty() && !mn.equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                                p.setResult(null);          // 整个方法体跳过：不装自查
                            } catch (Throwable ignored) {
                                // 不打扰宿主
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                    // 下一个方法
                }
            }
        }
        return n;
    }

    // ── ③ 包名自证：让「查已装应用」查不到我们 ──────────────────────────────

    private static int hideOwnPackage() {
        int n = 0;
        Class<?> apm;
        try {
            apm = Class.forName("android.app.ApplicationPackageManager", false,
                    Stealth.class.getClassLoader());
        } catch (Throwable t) {
            return 0;
        }

        try {
            XposedBridge.hookAllMethods(apm, "getInstalledPackages", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    stripPackages(p.getResult());
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 该方法不存在就跳过
        }

        try {
            XposedBridge.hookAllMethods(apm, "getInstalledApplications", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    stripApps(p.getResult());
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 同上
        }

        try {
            XposedBridge.hookAllMethods(apm, "getPackageInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                        if (p.args == null || p.args.length == 0) return;
                        if (!OURS.equals(p.args[0])) return;
                        // 微信问"乐信装了吗" → 答"没这个包"
                        p.setThrowable(new PackageManager.NameNotFoundException(OURS));
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 同上
        }

        try {
            XposedBridge.hookAllMethods(apm, "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                        if (p.args == null || p.args.length == 0) return;
                        if (!OURS.equals(p.args[0])) return;
                        p.setThrowable(new PackageManager.NameNotFoundException(OURS));
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 同上
        }
        return n;
    }

    private static void stripPackages(Object result) {
        if (!(result instanceof List)) return;
        try {
            if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
            Iterator<?> it = ((List<?>) result).iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof PackageInfo && OURS.equals(((PackageInfo) o).packageName)) it.remove();
            }
        } catch (Throwable ignored) {
            // 结果集不可改就放着（自报家门但至少不崩）
        }
    }

    private static void stripApps(Object result) {
        if (!(result instanceof List)) return;
        try {
            if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
            Iterator<?> it = ((List<?>) result).iterator();
            while (it.hasNext()) {
                Object o = it.next();
                if (o instanceof ApplicationInfo && OURS.equals(((ApplicationInfo) o).packageName)) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
