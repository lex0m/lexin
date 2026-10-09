package com.sysprobe.svc;

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
                        }
                    });
        } catch (Throwable ignored) {
            // 静默
        }
    }
}
