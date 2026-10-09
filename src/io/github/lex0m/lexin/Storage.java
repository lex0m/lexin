package io.github.lex0m.lexin;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XSharedPreferences;

/** 配置存储：早期放在微信外部媒体目录，会被扫到；现在用宿主自己的 SharedPreferences。 */
final class Storage {

    private static final String T = "svc-store: ";
    private static final String MODULE_PKG = "io.github.lex0m.lexin";
    private static final String FILE = "svc";

    private static volatile SharedPreferences sp;
    private static volatile XSharedPreferences xsp;

    // ── keys ─────────────────────────────────────────────────────────────────

    static final String K_RECALL = "recall";
    static final String K_MOMENTS = "moments";
    static final String K_ADS = "ads";
    static final String K_MEDIA = "media";
    static final String K_LIMITS = "limits";
    static final String K_EMOJI = "emoji";
    static final String K_DICE = "dice";
    static final String K_FIST = "fist";
    static final String K_THEME = "theme";
    static final String K_BG = "bg_chat";
    static final String K_BG_HOME = "bg_home";

    // ── init ─────────────────────────────────────────────────────────────────

    static void init(Context ctx) {
        try {
            if (sp != null || xsp != null) return;

            // 两边都初始化，不靠进程名判断：曾用 Application.getProcessName() 分支，判断异常时可写存储根本没建起来，写入静默失败，所有设置看着都是坏的。
            //
            // sp：普通 prefs。宿主进程里它就是微信自己的存储（面板写这里）；模块进程里是模块自己的。
            // xsp：LSPosed 读模块 prefs 的通道，只作兜底——本机上读不到东西（重定位后权限是 -rw-rw----）。
            if (ctx != null) {
                try {
                    sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
                } catch (Throwable ignored) {
                    // 没有可写存储
                }
            }
            try {
                XSharedPreferences candidate = new XSharedPreferences(MODULE_PKG, FILE);
                candidate.reload();
                xsp = candidate;
            } catch (Throwable ignored) {
                // 没注入到这里
            }
        } catch (Throwable t) {
            log("init: " + t.getClass().getSimpleName());
        }
    }

    /** 故意的空实现（诊断输出会留下指向模块的痕迹）。 */
    static void diag(String msg) {
        // 故意留空
    }

    // ── accessors ────────────────────────────────────────────────────────────
    //
    // 宿主进程里 sp 就是微信自己的 SharedPreferences，是权威存储；xsp 只在兜底时用，本机上它读不到东西（重定位后权限 -rw-rw----）。

    static boolean on(String key, boolean def) {
        try {
            if (sp != null && sp.contains(key)) return sp.getBoolean(key, def);
            if (xsp != null) {
                xsp.reload();
                if (xsp.contains(key)) return xsp.getBoolean(key, def);
            }
        } catch (Throwable ignored) {
            // 继续往下 to the default
        }
        return def;
    }

    static void setOn(String key, boolean value) {
        try {
            if (sp != null) sp.edit().putBoolean(key, value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static String str(String key, String def) {
        try {
            if (sp != null) {
                String v = sp.getString(key, null);
                if (v != null && !v.isEmpty()) return v;
            }
            if (xsp != null) {
                xsp.reload();
                String v = xsp.getString(key, null);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return def;
    }

    static void setStr(String key, String value) {
        try {
            if (sp != null) sp.edit().putString(key, value == null ? "" : value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static int num(String key, int def) {
        try {
            if (sp != null && sp.contains(key)) return sp.getInt(key, def);
            if (xsp != null) {
                xsp.reload();
                if (xsp.contains(key)) return xsp.getInt(key, def);
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return def;
    }

    static void setNum(String key, int value) {
        try {
            if (sp != null) sp.edit().putInt(key, value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 诊断：当前在哪一侧。 */
    static String side() {
        return "sp=" + (sp != null) + " xsp=" + (xsp != null);
    }

    private static void log(String s) {
        try {
            Log.d(T + s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
