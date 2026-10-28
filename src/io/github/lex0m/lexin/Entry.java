package io.github.lex0m.lexin;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 框架入口。静默：不写日志，异常不向外抛。 */
public final class Entry implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    static final String HOST = "com.tencent.mm";

    /** 最早的入口回调：框架在这里把模块 APK 路径交给我们，主题资源才能直接从 APK 读。 */
    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam param) {
        try {
            Assets.init(param.modulePath);
        } catch (Throwable ignored) {
            // 素材不可用就不管了
        }
    }

    /**
     * 每个宿主进程**只写一行**"我还活着"。
     *
     * **默认不写**（发布版零日志）；只有在面板里打开过「诊断日志」之后，重启微信才会写这一行。
     * 有了它就能分清"框架没加载模块"和"加载了但没生效"：
     *   有这行 → 模块进了这个进程（问题在功能层，看后面的适配数）
     *   没这行 → 框架层面就没加载（去查作用域/重启/框架版本，跟模块无关）
     * 只放版本号 + 系统版本 + 适配数，不含任何内部结构。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean SAID =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static void alive(String where) {
        if (!Log.runtimeOn()) return;       // 默认关：不写日志就不写，一个字都不留
        if (!SAID.compareAndSet(false, true)) return;
        try {
            XposedBridge.log("lexin v" + Features.MODULE_VERSION + " alive in " + where
                    + " (Android " + android.os.Build.VERSION.SDK_INT + ") · " + Status.summary());
        } catch (Throwable ignored) {
            // 日志永不抛异常
        }
    }

    /**
     * 这些微信子进程里装 hook 没有意义，但会重复跑一遍「抓 dex / 装十几个功能」，
     * 白耗启动时间和电量（微信冷启时会同时拉起下面这些进程）：
     *   :xweb_*               内置浏览器的沙箱进程（只跑网页，没有微信界面）
     *   :sandboxed_process*   小程序 / 网页沙箱
     *   :warmup               预热空进程
     * 其余进程（主进程 / :push / :appbrand0、1 / :tools 等）照旧装 —— 去广告的小程序
     * 开屏拦截就在 :appbrand 里，不能一刀切。
     */
    private static boolean skipProcess(String processName) {
        if (processName == null) return false;          // 老框架拿不到进程名：维持原行为
        int i = processName.indexOf(':');
        if (i < 0) return false;                        // 主进程
        String sub = processName.substring(i + 1);
        return sub.startsWith("xweb") || sub.startsWith("sandboxed_process")
                || sub.startsWith("warmup");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!HOST.equals(lp.packageName)) return;
        if (skipProcess(lp.processName)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Features.install((Context) param.args[0], lp.classLoader);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                            alive(android.app.Application.getProcessName());
                        }
                    });
        } catch (Throwable ignored) {
            // 静默
        }
    }
}
