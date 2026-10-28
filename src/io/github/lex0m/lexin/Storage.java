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
    /** 诊断日志开关（默认关：发布版一个字都不写）。 */
    static final String K_DIAG = "diag_log";
    /** v1.3 自动语音转文字（默认关：调微信官方转文字，联网识别）。 */
    static final String K_VOICE_TEXT = "voice_text";
    /** v1.3 悬浮聊天底栏（默认关：改输入栏外观）。 */
    static final String K_FLOAT_FOOTER = "float_footer";
    /** v1.3 批量分类标签（默认关：走微信官方 netscene 改标签）。 */
    static final String K_BATCH_LABEL = "batch_label";
    /** v1.3.1 主页悬浮底栏（默认关：改主页 TabBar 外观）。 */
    static final String K_HOME_FLOAT_TAB = "home_float_tab";

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
                    if (hostSide) {
                        migrateLegacy(ctx);
                        importFromHost(ctx);       // 合并设置页那边的改动（按 ts 水位，只合新的一次）
                    }
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
        exportForHost();
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
        exportForHost();
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
        exportForHost();
    }

    /** 诊断日志是否开启（持久化，供下次加载时判断）。 */
    static boolean diag() {
        try {
            String k = physical(K_DIAG);
            if (sp != null && sp.contains(k)) return sp.getBoolean(k, false);
            if (xsp != null) {
                xsp.reload();
                if (xsp.contains(k)) return xsp.getBoolean(k, false);
            }
        } catch (Throwable ignored) {
            // 读不到就按关处理
        }
        return false;
    }

    /** 诊断：当前在哪一侧、用的是哪个文件。 */
    static String side() {
        return "sp=" + (sp != null) + " xsp=" + (xsp != null) + " host=" + hostSide;
    }    static boolean isHostSide() {
        return hostSide;
    }

    // ── 设置页 ⇄ 宿主 的配置同步 ────────────────────────────────────────────
    //
    // 两边写的不是同一个文件：
    //   模块进程（设置页）→ 自己的 svc.xml
    //   微信进程（宿主）  → 微信的 com.tencent.mm_preferences
    // 读的时候又都先读自己那份，所以**设置页里改的开关，微信侧根本读不到**
    // （表现就是"开关点了没反应"，只有微信面板里改的才生效）。
    //
    // 解决：设置页每次改动后，把整份配置导成 push.txt 放在 provider 目录；
    // 宿主进程启动时通过 provider 拉一次，按 ts 水位合并进它自己那份。

    /** 上次合并进宿主的推送时间戳（物理键，Long）。 */
    private static final String K_PUSH_TS = "__push_ts";

    /** 设置页侧：把当前配置整份导出（宿主启动时来拉）。 */
    private static void exportForHost() {
        if (hostSide || sp == null || appCtx == null) return;
        try {
            Map<String, ?> all = sp.getAll();
            StringBuilder sb = new StringBuilder(all.size() * 24 + 32);
            sb.append("ts=").append(System.currentTimeMillis()).append('\n');
            for (Map.Entry<String, ?> en : all.entrySet()) {
                String k = en.getKey();
                if (k == null || !k.startsWith(PREFIX)) continue;   // 只导逻辑配置，不导内部键
                Object v = en.getValue();
                if (v instanceof Boolean) {
                    sb.append("b:").append(k).append('=').append(((Boolean) v) ? 1 : 0).append('\n');
                } else if (v instanceof Integer) {
                    sb.append("i:").append(k).append('=').append(v).append('\n');
                } else if (v instanceof String) {
                    sb.append("s:").append(k).append('=').append(escape((String) v)).append('\n');
                }
            }
            java.io.File f = new java.io.File(appCtx.getFilesDir(), BgProvider.PUSH_FILE);
            java.io.FileOutputStream os = new java.io.FileOutputStream(f, false);
            try {
                os.write(sb.toString().getBytes("UTF-8"));
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 推送失败不影响本地设置
        }
    }

    /** 宿主侧：启动时拉一次设置页的推送，按 ts 水位合并。 */
    private static void importFromHost(Context ctx) {
        if (sp == null) return;
        try {
            android.net.Uri u = android.net.Uri.parse(
                    "content://" + BgProvider.AUTHORITY + BgProvider.PUSH_PATH);
            java.io.InputStream in = ctx.getContentResolver().openInputStream(u);
            if (in == null) return;
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(4096);
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 128 * 1024) bo.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            String text = new String(bo.toByteArray(), "UTF-8");
            String[] lines = text.split("\n");
            if (lines.length == 0) return;

            long ts = 0L;
            long applied = 0L;
            try {
                applied = sp.getLong(physical(K_PUSH_TS), 0L);
            } catch (Throwable ignored) {
                // 类型不符就当没合并过
            }
            for (String line : lines) {
                if (line.startsWith("ts=")) {
                    try {
                        ts = Long.parseLong(line.substring(3).trim());
                    } catch (Throwable ignored) {
                        ts = 0L;
                    }
                    break;
                }
            }
            if (ts <= 0L || ts <= applied) return;      // 没有新东西

            SharedPreferences.Editor e = sp.edit();
            for (String line : lines) {
                if (line.length() < 4 || line.startsWith("ts=")) continue;
                char type = line.charAt(0);
                if (line.charAt(1) != ':') continue;
                int eq = line.indexOf('=', 2);
                if (eq <= 2) continue;
                String key = line.substring(2, eq);
                String val = line.substring(eq + 1);
                try {
                    if (type == 'b') e.putBoolean(key, "1".equals(val));
                    else if (type == 'i') e.putInt(key, Integer.parseInt(val));
                    else if (type == 's') e.putString(key, unescape(val));
                } catch (Throwable ignored) {
                    // 单条坏行跳过
                }
            }
            e.putLong(physical(K_PUSH_TS), ts);
            e.apply();
            log("imported push ts=" + ts);
        } catch (Throwable t) {
            // provider 没起来 / 文件不存在：正常，下次进微信再拉
            log("import skipped: " + t.getClass().getSimpleName());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void log(String s) {
        try {
            Log.d(T + s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
