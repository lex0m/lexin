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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!HOST.equals(lp.packageName)) return;
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
