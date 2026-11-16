package io.github.lex0m.lexin;

import android.graphics.Color;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 主题包加载器：包就是一个普通文件夹，所有内容都可选，最小可以只有一个配置文件。 */
final class ThemePack {

    private static final String T = "svc-theme: ";

    /** 主题包存放位置（按优先级；微信外部文件目录是宿主唯一能自由读取的地方）。 */
    static final String[] DIRS = {
            S.WX_P16, S.WX_P17, S.WX_P18,
    };
    /** Kept for the UI: the directory a user should actually use. */
    static final String DIR = DIRS[0];
    private static final String CONFIG = "theme.json";

    private static volatile JSONObject config;
    private static volatile long loadedAt = -1L;
    /** Directory of the pack currently in force (for image lookup). */
    private static volatile File activeDir;

    private static final Map<String, Drawable> images = new ConcurrentHashMap<>();
    private static final Map<String, Integer> colourCache = new ConcurrentHashMap<>();

    // ── loading ──────────────────────────────────────────────────────────────

    static void ensureLoaded() {
        try {
            String active = Prefs.str(KEY_ACTIVE, "");
            File f = null;
            if (!active.isEmpty()) {
                for (String d : DIRS) {
                    File c = new File(new File(new File(d, PACKS), active), CONFIG);
                    if (c.isFile() && c.canRead()) { f = c; break; }
                }
            }
            if (f == null) {
                for (String d : DIRS) {
                    File c = new File(d, CONFIG);
                    if (c.isFile() && c.canRead()) { f = c; break; }
                }
            }
            if (f == null) f = new File(DIRS[0], CONFIG);
            activeDir = f.getParentFile();
            long stamp = f.isFile() ? f.lastModified() : -1L;
            if (stamp == loadedAt) return;
            synchronized (ThemePack.class) {
                if (stamp == loadedAt) return;
                loadedAt = stamp;
                images.clear();
                miss.clear();
                colourCache.clear();
                if (stamp < 0) {
                    config = null;
                    return;
                }
                StringBuilder sb = new StringBuilder();
                // try-with-resources：之前 readLine/JSONObject 一抛异常，这个 fd 就永远不关，
                // 而 ensureLoaded 是所有主题热路径的必经之路 ⇒ 最终 "Too many open files" 崩微信。
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                config = new JSONObject(sb.toString());
                XposedBridgeLog(T + "pack loaded: " + name());
            }
        } catch (Throwable t) {
            config = null;
            XposedBridgeLog(T + "pack load failed: " + t.getClass().getSimpleName());
        }
    }

    static boolean active() {
        ensureLoaded();
        return config != null;
    }

    static String name() {
        try {
            return config == null ? "(none)" : config.optString("name", "(unnamed)");
        } catch (Throwable ignored) {
            return "(unnamed)";
        }
    }

    // ── colours ──────────────────────────────────────────────────────────────

    /** Reads { "colors": { key: "#RRGGBB" } }; 0 means "leave it to the host". */
    static int colour(String key, int def) {
        ensureLoaded();
        Integer cached = colourCache.get(key);
        if (cached != null) return cached;
        int value = def;
        try {
            JSONObject colours = config == null ? null : config.optJSONObject("colors");
            if (colours != null && colours.has(key)) {
                value = Color.parseColor(colours.getString(key));
            }
        } catch (Throwable ignored) {
            // 保持默认
        }
        colourCache.put(key, value);
        return value;
    }

    // ── numbers (widget metrics, font scale, radii ...) ──────────────────────

    /** Reads { "widgets": { key: 12 } }; falls back to the whole doc. */
    static int number(String key, int def) {
        ensureLoaded();
        try {
            if (config == null) return def;
            JSONObject group = config.optJSONObject("widgets");
            if (group != null && group.has(key)) return group.getInt(key);
            if (config.has(key)) return config.getInt(key);
        } catch (Throwable ignored) {
            // 保持默认
        }
        return def;
    }

    static float decimal(String key, float def) {
        ensureLoaded();
        try {
            if (config == null) return def;
            JSONObject group = config.optJSONObject("widgets");
            if (group != null && group.has(key)) return (float) group.getDouble(key);
            if (config.has(key)) return (float) config.getDouble(key);
        } catch (Throwable ignored) {
            // 保持默认
        }
        return def;
    }

    // ── images ───────────────────────────────────────────────────────────────

    /** 解析过但确实没有素材的资源名（负缓存）：避免每次 setImageResource 都去探盘。 */
    private static final java.util.Set<String> miss = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 按宿主短资源名解析主题素材，包根和 images/ 子目录都查。
     *
     * v1.6.2：
     *   · 加负缓存。主题包里没覆盖的资源是绝大多数，以前每次 setImageResource 都要走
     *     4 个扩展名 × 2 个根目录的 isFile/canRead + ZipFile.getEntry，滑动明显发涩。
     *   · 返回**新实例**。Drawable 是有状态的（bounds / callback），同一个实例塞给多个
     *     ImageView 会互相抢 bounds，表现为图标尺寸跳变、偶发错图，还会让旧界面回收不掉。
     */
    static Drawable image(String shortName) {
        if (shortName == null || shortName.isEmpty()) return null;
        if (miss.contains(shortName)) return null;
        Drawable cached = images.get(shortName);
        if (cached != null) return copy(cached);

        // 1) 直接从模块 APK 读——磁盘上不留东西，这正是把素材打进 APK 的意义。
        Drawable fromApk = Assets.drawable(shortName);
        if (fromApk != null) {
            images.put(shortName, fromApk);
            return copy(fromApk);
        }

        // 2) 回退：用户自己放的文件，只有刻意放进去时才用（会留下文件，权衡后保留）。
        try {
            java.util.List<String> roots = new java.util.ArrayList<>();
            if (activeDir != null) roots.add(activeDir.getAbsolutePath());
            for (String d : DIRS) {
                String act = Prefs.str(KEY_ACTIVE, "");
                roots.add(act.isEmpty() ? d : new File(new File(d, PACKS), act).getAbsolutePath());
            }
            for (String dir : roots) {
                File root = new File(dir);
                for (String sub : new String[]{"", "images/"}) {
                    for (String ext : new String[]{"", ".png", ".webp", ".jpg", ".jpeg"}) {
                        File f = new File(root, sub + shortName + ext);
                        if (!f.isFile() || !f.canRead()) continue;
                        Drawable d = Drawable.createFromPath(f.getAbsolutePath());
                        if (d != null) {
                            images.put(shortName, d);
                            return copy(d);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        miss.add(shortName);
        return null;
    }

    /** 复制一个 Drawable 实例（ConstantState 共享像素，不额外占内存）。 */
    private static Drawable copy(Drawable d) {
        try {
            if (d == null) return null;
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                Drawable n = cs.newDrawable();
                if (n != null) {
                    n.setAlpha(d.getAlpha());
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // 复制失败就退回原实例（比没有强）
        }
        return d;
    }

    /** A path referenced from the config (e.g. a background or font file). */
    static File asset(String relative) {
        try {
            if (relative == null || relative.isEmpty()) return null;
            File f = new File(relative);
            if (f.isAbsolute()) return f.isFile() && f.canRead() ? f : null;
            for (String d : DIRS) {
                File c = new File(d, relative);
                if (c.isFile() && c.canRead()) return c;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reads a nested string, e.g. string("background.chat"). */
    static String string(String path) {
        ensureLoaded();
        try {
            if (config == null || path == null || path.isEmpty()) return null;
            String[] parts = path.split("\\.");
            JSONObject node = config;
            for (int i = 0; i < parts.length - 1; i++) {
                node = node.optJSONObject(parts[i]);
                if (node == null) return null;
            }
            String v = node.optString(parts[parts.length - 1], "");
            return v.isEmpty() ? null : v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── multiple packs ───────────────────────────────────────────────────────
    //
    // 目录结构：<dir>/theme.json（默认包）、<dir>/packs/<名>/theme.json（可切换的包）
    //
    // 当前生效的包名存在配置里，保证界面进程与 hook 进程看到同一个。

    private static final String PACKS = "packs";
    private static final String KEY_ACTIVE = "theme_pack";

    /** Names of every pack under packs/, sorted; empty when there are none. */
    static java.util.List<String> listPacks() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (String d : DIRS) {
                File packs = new File(d, PACKS);
                if (!packs.isDirectory()) continue;
                File[] kids = packs.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isDirectory() && new File(k, CONFIG).isFile()) {
                        String n = k.getName();
                        if (!out.contains(n)) out.add(n);
                    }
                }
            }
            java.util.Collections.sort(out);
        } catch (Throwable ignored) {
            // 没有主题包
        }
        return out;
    }

    static String activePack() {
        return Prefs.str(KEY_ACTIVE, "");
    }

    static void activate(String name) {
        Prefs.setStr(KEY_ACTIVE, name == null ? "" : name);
        invalidate();
    }

    // ── switches ─────────────────────────────────────────────────────────────

    /** Reads { "switches": { key: true } } so a pack can enable its own parts. */
    static boolean toggle(String key, boolean def) {
        ensureLoaded();
        try {
            JSONObject group = config == null ? null : config.optJSONObject("switches");
            if (group != null && group.has(key)) return group.getBoolean(key);
        } catch (Throwable ignored) {
            // 保持默认
        }
        return def;
    }

    static void invalidate() {
        loadedAt = -1L;
        images.clear();
        colourCache.clear();
    }

    private static void XposedBridgeLog(String s) {
        try {
            Log.d(s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
