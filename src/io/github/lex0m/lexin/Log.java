package io.github.lex0m.lexin;

import de.robv.android.xposed.XposedBridge;

/** 日志：发布版静默。写出去的每一行都会落进宿主的模块日志，等于留痕迹。 */
final class Log {

    /** 由 build.sh 按 --debug 生成（见 BuildFlags），正式包恒为 false。 */
    private static final boolean DEBUG = BuildFlags.DEBUG;

    /**
     * 运行时开关：面板里可以打开（排查现场问题用）。
     * **默认关** —— 发布版一个字都不写；要排查时在面板里打开，重启微信后才会开始记录。
     */
    private static volatile boolean runtime = false;

    /**
     * 内存环形缓冲（v1.7.11）：只为「一键诊断导出」留现场。
     *
     * **不落盘、不打日志**，只在进程里留最近 800 行。以前排查要么手抄 logcat、
     * 要么让用户去 LSPosed 里翻日志 —— 真到了现场，这两条都不现实。
     * 记录动作在开关之前执行：发布版不打印，但导出时仍然拿得到最近发生了什么。
     */
    private static final java.util.ArrayDeque<String> RING =
            new java.util.ArrayDeque<String>();
    private static final int RING_MAX = 800;

    /** SimpleDateFormat 不是线程安全的，而且 android.text.format.DateFormat 不认 "SSS"（会原样输出）。 */
    private static final java.text.SimpleDateFormat STAMP =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    private static void record(String level, String msg) {
        try {
            String ts;
            synchronized (STAMP) {
                ts = STAMP.format(new java.util.Date());
            }
            String line = level + " " + ts + " " + msg;
            synchronized (RING) {
                RING.addLast(line);
                while (RING.size() > RING_MAX) RING.removeFirst();
            }
        } catch (Throwable ignored) {
            // 记不下也不影响主流程
        }
    }

    /** 导出用：把环形缓冲整份取出来（旧 → 新）。 */
    static String dumpRing() {
        StringBuilder sb = new StringBuilder(RING_MAX * 48);
        synchronized (RING) {
            for (String s : RING) sb.append(s).append('\n');
        }
        return sb.toString();
    }

    static void setRuntime(boolean on) {
        runtime = on;
    }

    static boolean runtimeOn() {
        return DEBUG || runtime;
    }

    /** App 进程用（那里没有 XposedBridge）：只在 DEBUG 构建里输出。 */
    static void a(String msg) {
        record("A", msg);                       // 环形缓冲先记：导出时才有现场
        if (!DEBUG && !runtime) return;
        try {
            android.util.Log.i("lexin", msg);
        } catch (Throwable ignored) {
            // 日志永不抛异常
        }
    }

    /** Emits only when DEBUG is on（或用户临时打开了运行时日志）. */
    static void d(String msg) {
        record("D", msg);                       // 同上：打印与记录是两条独立的路
        if (!DEBUG && !runtime) return;
        try {
            XposedBridge.log(msg);
        } catch (Throwable ignored) {
            // 日志永不抛异常
        }
    }
}
