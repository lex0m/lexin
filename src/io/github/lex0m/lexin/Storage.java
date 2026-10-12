package io.github.lex0m.lexin;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 配置存储。
 *
 * 隐蔽性安排（v1.1 收紧）：
 *   1. 宿主进程里**不再新建自己的配置文件**：直接写微信自己的 com.tencent.mm_preferences。
 *      v1.0 会生成 shared_prefs/svc.xml —— 一个以模块命名的文件躺在微信目录里，
 *      是最好被抓的证据。迁移完成后那个文件会被删掉。
 *   2. 键名不落语义：一律 ap_+hashCode(key) 的十六进制，混在微信自己的几百个键里认不出来。
 *   3. 模块进程（自己的设置页）仍写模块自己的 prefs，两边互不影响。
 */
final class Storage {

    private static final String T = "svc-store: ";
    private static final String MODULE_PKG = "io.github.lex0m.lexin";
    private static final String FILE = "svc";
    /** 宿主侧：用微信自己的配置文件当容器。 */
    private static final String HOST_FILE = "com.tencent.mm_preferences";
    /** 键名前缀（短，和微信自己的键风格一致）。 */
    private static final String PREFIX = "ap_";

    private static volatile SharedPreferences sp;
    private static volatile XSharedPreferences xsp;
    private static volatile Context appCtx;
    private static volatile boolean hostSide = false;

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
    static final String K_SCAN = "scan_free";

    // ── init ─────────────────────────────────────────────────────────────────

    static void init(Context ctx) {
        try {
            if (sp != null || xsp != null) return;
            appCtx = ctx;

            // 两边都初始化，不靠进程名判断：曾用 Application.getProcessName() 分支，
            // 判断异常时存储根本没建起来，写入静默失败，所有设置看着都是坏的。
            if (ctx != null) {
                try {
                    String pkg = ctx.getPackageName();
                    hostSide = Entry.HOST.equals(pkg);
                    String file = hostSide ? HOST_FILE : FILE;
                    sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE);
                    if (hostSide) migrateLegacy(ctx);
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

    /**
     * 把 v1.0 留在 shared_prefs/svc.xml 里的老配置搬进微信自己的文件，然后删掉那个文件。
     * 只在宿主进程跑一次（文件不存在就什么都不做）。
     */
    private static void migrateLegacy(Context ctx) {
        try {
            java.io.File legacy = new java.io.File(
                    ctx.getFilesDir().getParentFile(), "shared_prefs/" + FILE + ".xml");
            if (!legacy.isFile()) return;
            SharedPreferences old = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            Map<String, ?> all = old.getAll();
            SharedPreferences.Editor e = sp.edit();
            int n = 0;
            for (Map.Entry<String, ?> en : all.entrySet()) {
                Object v = en.getValue();
                String k = physical(en.getKey());
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else if (v instanceof Float) e.putFloat(k, (Float) v);
                else if (v instanceof String) e.putString(k, (String) v);
                else continue;
                n++;
            }
            e.apply();
            // 老文件必须消失：它就是以模块命名的那个"证据"
            boolean deleted = ctx.deleteSharedPreferences(FILE);
            if (!deleted) legacy.delete();
            log("migrated " + n + " keys, legacy file removed=" + !legacy.exists());
        } catch (Throwable ignored) {
            // 迁移失败不影响使用
        }
    }

    /** 逻辑键 -> 物理键：不落语义，混进微信自己的键里。 */
    private static String physical(String key) {
        return key == null ? "" : PREFIX + Integer.toHexString(key.hashCode());
    }

    /** 故意的空实现（诊断输出会留下指向模块的痕迹）。 */
    static void diag(String msg) {
        try {
            if (sp != null) sp.edit().putString(physical("diag"), msg == null ? "" : msg).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 面板自己也能显示这行：出问题时不用连电脑。 */
    static String lastDiag() {
        return str("diag", "");
    }

    // ── accessors ────────────────────────────────────────────────────────────
    //
    // 宿主进程里 sp 就是微信自己的 SharedPreferences，是权威存储；
    // xsp 只在兜底时用（本机上重定位后权限是 -rw-rw----，读不到东西）。

    static boolean on(String key, boolean def) {
        try {
            String k = physical(key);
            if (sp != null && sp.contains(k)) return sp.getBoolean(k, def);
            if (xsp != null) {
                xsp.reload();
                if (xsp.contains(k)) return xsp.getBoolean(k, def);
            }
        } catch (Throwable ignored) {
            // 继续往下 to the default
        }
        return def;
    }

    static void setOn(String key, boolean value) {
        try {
            if (sp != null) sp.edit().putBoolean(physical(key), value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static String str(String key, String def) {
        try {
            String k = physical(key);
            if (sp != null) {
                String v = sp.getString(k, null);
                if (v != null && !v.isEmpty()) return v;
            }
            if (xsp != null) {
                xsp.reload();
                String v = xsp.getString(k, null);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return def;
    }

    static void setStr(String key, String value) {
        try {
            if (sp != null) sp.edit().putString(physical(key), value == null ? "" : value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    static int num(String key, int def) {
        try {
            String k = physical(key);
            if (sp != null && sp.contains(k)) return sp.getInt(k, def);
            if (xsp != null) {
                xsp.reload();
                if (xsp.contains(k)) return xsp.getInt(k, def);
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return def;
    }

    static void setNum(String key, int value) {
        try {
            if (sp != null) sp.edit().putInt(physical(key), value).apply();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 诊断：当前在哪一侧、用的是哪个文件。 */
    static String side() {
        return "sp=" + (sp != null) + " xsp=" + (xsp != null) + " host=" + hostSide;
    }

    static boolean isHostSide() {
        return hostSide;
    }

    private static void log(String s) {
        try {
            Log.d(T + s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
