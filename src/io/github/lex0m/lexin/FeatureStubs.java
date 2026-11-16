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
        // 2026-11-16 补丁 dex 复核：`pv0.u0#a` 不是检测，是微信自己的崩溃回溯安装器
        // （往 libwechatcrash/libwxperf-tkill 装 signal hook）。跳过它只会废掉微信的崩溃上报，
        // 还会改变崩溃路径行为 —— 已经撤掉。
        int m = 0;
        int p = hideOwnPackage();
        int e = tinkerBranch() + extraChecks();
        int n = x + m + p + e;
        Status.armed("环境隐蔽", n);
        Status.note("环境隐蔽", n > 0
                ? "栈帧自检 " + x + " · Tinker 破坏链 " + e + " · 包名隐藏 " + p
                : "当前微信版本未适配");
        Log.d(T + "armed stack=" + x + " matrix=" + m + " pkg=" + p);
    }

    /**
     * Tinker 反制链的第一段：`com.tencent.mm.app.j3#c(Throwable)`（tag TinkerCrashProtect）。
     *
     * **不整段跳过**——那会把微信自己的崩溃保护一起跳过，等于制造新的异常行为。
     * 只在异常文案命中检测分支时才跳过这一次处理：
     *   ① "Class ref in pre-verified class resolved to unexpected implementation"（Xposed 典型症状）
     *   ② "tag requires a 'drawable' attribute"（ZUK 机型分支，反汇编确认它不走 j3#a）
     * 其余崩溃照常走微信自己的逻辑。
     */
    private static int tinkerBranch() {
        int n = 0;
        try {
            Class<?> j3 = Loaders.load("com.tencent.mm.app.j3");
            if (j3 == null) return 0;
            for (final Method m : j3.getDeclaredMethods()) {
                if (!"c".equals(m.getName()) || m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || !Throwable.class.isAssignableFrom(pt[0])) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                            Object t = p.args != null && p.args.length > 0 ? p.args[0] : null;
                            String msg = t instanceof Throwable ? String.valueOf(((Throwable) t).getMessage()) : "";
                            if (msg.contains("pre-verified class")
                                    || msg.contains("tag requires a 'drawable'")) {
                                Status.hit("环境隐蔽");
                                p.setResult(null);      // 只跳过这一次检测分支
                            }
                        } catch (Throwable ignored) {
                            // 不打扰宿主
                        }
                    }
                });
                n++;
            }
        } catch (Throwable ignored) {
            // 拿不到就不挂
        }
        return n;
    }

    // ── ⓪ 8.0.80 补丁 dex 里额外的检测入口 ─────────────────────────────────

    /**
     * 实测（2026-11-16，补丁 dex classes13）：
     *   com.tencent.mm.app.j3#c(Throwable)->void   记 "have xposed"
     *   zh.b0/e4/j3/m2/m#c()->void                 checkHook 家族
     *   com.tencent.matrix.mallctl.MallCtl#a(ni.b)  读 /proc/self/maps
     * 这些都是 void 方法，跳过方法体即可；拿不到就跳过，不影响其它探针。
     */
    private static int extraChecks() {
        int n = 0;
        for (String entry : Anchors.raw(Anchors.STEALTH_EXTRA)) {
            String cn = Anchors.classNameOf(entry);
            String mn = Anchors.methodNameOf(entry);
            if (cn == null || mn == null) continue;
            Class<?> c;
            try {
                c = Loaders.load(cn);
            } catch (Throwable t) {
                continue;
            }
            if (c == null) continue;
            for (final Method m : c.getDeclaredMethods()) {
                if (!mn.equals(m.getName())) continue;
                if (m.getReturnType() != void.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_STEALTH)) return;
                                Status.hit("环境隐蔽");
                                p.setResult(null);       // 整个方法体跳过
                            } catch (Throwable ignored) {
                                // 不打扰宿主
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        Log.d(T + "extra checks=" + n);
        return n;
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
                                Status.hit("环境隐蔽");     // 微信真的来查了（说明探针存在且被拦）
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

    // ── ② 已废弃：pv0.u0#a 是崩溃回溯安装器，不是检测（保留代码仅供对照） ─────────

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
