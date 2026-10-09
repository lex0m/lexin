package io.github.jma28262lgtm.lexin;

import de.robv.android.xposed.XposedBridge;

/** 日志：发布版静默。写出去的每一行都会落进宿主的模块日志，等于留痕迹。 */
final class Log {

    /** Set true only for a throwaway verification build. */
    private static final boolean DEBUG = false;

    /** Emits only when DEBUG is on. */
    static void d(String msg) {
        if (!DEBUG) return;
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            // 日志永不抛异常
        }
    }
}
