package io.github.lex0m.lexin;

import de.robv.android.xposed.XposedBridge;

/** 日志：发布版静默。写出去的每一行都会落进宿主的模块日志，等于留痕迹。 */
final class Log {

    /** Set true only for a throwaway verification build. */
    private static final boolean DEBUG = false;

    /**
     * 运行时开关：面板里可以打开（排查现场问题用）。
     * **默认关** —— 发布版一个字都不写；要排查时在面板里打开，重启微信后才会开始记录。
     */
    private static volatile boolean runtime = false;

    static void setRuntime(boolean on) {
        runtime = on;
    }

    static boolean runtimeOn() {
        return DEBUG || runtime;
    }

    /** Emits only when DEBUG is on（或用户临时打开了运行时日志）. */
    static void d(String msg) {
        if (!DEBUG && !runtime) return;
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            // 日志永不抛异常
        }
    }
}
