package io.github.lex0m.lexin;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置存储（v1.5 重构：微信进程零落盘）。
 *
 * 设计约束（用户口径：数据不进微信目录 / 自隐藏 / 跨进程 / 无痕 / 稳定）：
 *   1. **唯一数据源 = 模块私有目录 {@code files/.cfg}**，微信进程只读内存镜像，一个字节都不往
 *      {@code /data/data/com.tencent.mm} 里写。老版本把 24 个 {@code ap_*} 键写进微信自己的
 *      {@code com.tencent.mm_preferences.xml}，那是最好抓的证据，已彻底移除。
 *   2. 跨进程只走 {@link BgProvider}（ContentProvider）：读 = {@code call("getAll")} 一次拿全量，
 *      写 = {@code call("put")}。不走文件共享、不走 XSharedPreferences（后者实测在 Android 15 上
 *      根本读不到，还会把 {@code de.robv.android.xposed.XSharedPreferences} 这个类拉进微信进程）。
 *   3. 微信侧带内存缓存（首次一次 IPC，之后零 IPC），模块侧改完发一条中性广播让缓存失效。
 *   4. 失败一律降级为默认值：provider 没起来 / 被禁 / 抛异常都不影响微信正常运行。
 *   5. 模块侧文件做字节混淆（XOR），即使被 root 工具或备份提取也读不出功能开关语义。
 */
final class Storage {

    private static final String T = "svc-store: ";

    // ── 数据位置（全部在模块私有目录，微信读不到） ─────────────────────────
    /** 配置文件名（模块私有目录下，点号开头）。 */
    private static final String DATA_FILE = ".cfg";
    /** 模块侧包名：宿主侧据此判断"我在哪一边"。 */
    private static final String MODULE_PKG = "io.github.lex0m.lexin";

    // ── keys（对外 API 不变，其它 50 个文件零改动） ────────────────────────

    static final String K_RECALL = "recall";
    /** 防撤回自己（默认关：拦自己撤回会变成"我撤不回自己的消息"，属于异常行为）。 */
    static final String K_RECALL_SELF = "recall_self";
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
    /**
     * v1.5 环境隐蔽（默认开）：拦微信自己的三道探针 + 让「已装应用」里查不到本模块。
     * 默认开是刻意的——它不是功能，是我们能一直在的前提。
     */
    static final String K_STEALTH = "stealth";

    // ── 状态 ─────────────────────────────────────────────────────────────────

    private static volatile Context appCtx;
    private static volatile boolean hostSide = false;
    private static volatile boolean inited = false;
    /** 微信侧：从 provider 拉来的全量镜像；模块侧：本地文件的镜像。 */
    /**
     * 配置内存镜像。**必须是并发容器**：读路径（hook 回调线程）不加锁，
     * 写路径（面板/广播线程）在 synchronized 里 clear+put —— 用 HashMap 的话，
     * 读线程会在 clear 与 put 之间读到半张空表，配置瞬间回默认值。
     */
    private static final Map<String, String> mem = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static volatile boolean loading = false;
    private static volatile long stamp = 0L;
    private static volatile boolean watching = false;
    /** 只在模块侧用：脏标记，写操作后立即落盘（低频，开销可忽略）。 */
    private static volatile boolean dirty = false;
    /**
     * 配置是否已经真的拿到（模块镜像读到了，或广播推送到了）。
     *
     * 懒挂载必须看这个：拿不到配置时按默认值"不挂"会把用户开着的功能饿死，
     * 所以只有"确认拿到配置且明确是关"才跳过挂载。
     */
    private static volatile boolean confident = false;

    /** 诊断用的最后一条内部消息（只存内存，绝不落盘）。 */
    private static volatile String lastDiag = "";

    // ── init ─────────────────────────────────────────────────────────────────

    static void init(Context ctx) {
        if (inited) return;
        synchronized (Storage.class) {
            if (inited) return;
            inited = true;
            try {
                appCtx = ctx;
                if (ctx != null) {
                    String pkg = ctx.getPackageName();
                    hostSide = Entry.HOST.equals(pkg);
                }
                if (hostSide) {
                    // v1.6：宿主侧零落盘、零读宿主文件。先读模块侧镜像（只读），
                    // 读不到就等广播，再没有就用默认值。
                    loadModulePrefs(ctx);
                    loaded = true;
                    watch();
                    prefetch();          // 内含：迁移旧键 → 拉最新全量
                } else {
                    loadFromDisk();
                    loaded = true;
                    healBackground();
                    // 模块侧：顺手把微信目录里的历史痕迹清掉（v1.4 及更早写入的 ap_* 键）。
                    // 这一步需要微信目录可写 —— 模块进程没有那个权限，改由下次宿主侧启动时清。
                }
            } catch (Throwable t) {
                log("init: " + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * 后台预取：不阻塞调用方。拿不到就继续用默认值，下一次读会再触发一次预取。
     */
    private static void prefetch() {
        if (!hostSide || loading) return;
        loading = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Context c = appCtx;
                        if (c != null) {
                            migrateLegacyPrefs(c);      // 先把老键搬走
                            CfgBus.requestAll(c);       // 再请模块推全量（异步到）
                        }
                    } finally {
                        loading = false;
                    }
                }
            }, "s" + Integer.toHexString((int) (System.nanoTime() & 0xFFF)));
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            loading = false;
        }
    }

    /** 微信侧：挂广播总线（CfgBus）。失败不影响任何功能。 */
    private static void watch() {
        if (watching || appCtx == null) return;
        watching = true;
        CfgBus.watchHost(appCtx);
    }

    // ── 读 ───────────────────────────────────────────────────────────────────

    private static void ensureLoaded() {
        if (loaded) return;
        if (hostSide) {
            prefetch();          // 只触发，不等 —— 本次调用用默认值返回
            return;
        }
        synchronized (Storage.class) {
            if (loaded) return;
            loadFromDisk();
            loaded = true;
        }
    }

    /** 缓存失效（模块侧改完 / 收到广播）。下一次读重新拉。 */
    static void invalidate() {
        synchronized (Storage.class) {
            loaded = false;
            mem.clear();
            stamp = 0L;
            confident = false;      // 配置失效 → 懒挂载闸门回到"未知"，维持旧行为
        }
    }

    /**
     * 迁移并清理 v1.4 及更早写进微信自己 prefs 的痕迹（{@code ap_<hex(hashCode)>} 键）。
     *
     * v1.5 第一版直接 remove() 掉，结果**把用户的配置也删了**（键名是 hashCode，删了不可反解）。
     * 现在改成：先把已知键的值按类型读出、经 provider 写进模块侧，再删微信侧的键。
     * 未知的 ap_ 键（反解不出来）只能删除 —— 那部分本来也用不上。
     * 只在宿主侧的后台线程跑：拿不到 provider 就留着不删，下次启动再试。
     */
    private static final String[] LEGACY_KEYS = {
            "bf_all_pages",
            "bf_alpha",
            "bf_bg",
            "bf_blur_on",
            "bf_blur_r",
            "bf_card_alpha",
            "bf_card_blur_on",
            "bf_card_blur_r",
            "bf_card_corner",
            "bf_card_dark",
            "bf_card_gap",
            "bf_card_glass",
            "bf_card_h",
            "bf_card_img",
            "bf_card_inset",
            "bf_card_on",
            "bf_cch",
            "bf_ccw",
            "bf_ccx",
            "bf_ccy",
            "bf_ch",
            "bf_corner_dp",
            "bf_corner_on",
            "bf_cw",
            "bf_cx",
            "bf_cy",
            "bf_dump",
            "bf_h",
            "bf_mode",
            "bf_scale",
            "bf_w",
            "q_d",
            "q_h",
            "fwd_menu",
            "fwd_select",
            "st_cap",
            "st_mode",
            "st_mul",
            "st_on",
            "st_rate",
            "st_target",
            "ads",
            "batch_label",
            "bg_chat",
            "bg_home",
            "diag_log",
            "dice",
            "emoji",
            "fist",
            "float_footer",
            "home_float_tab",
            "limits",
            "media",
            "moments",
            "recall",
            "scan_free",
            "theme",
            "voice_text",
            "theme_bar_tint",
            "theme_tab_tint",
    };

    /** 旧版物理键前缀（v1.4 及更早）。 */
    private static final String LEGACY_PREFIX = "ap_";

    /**
     * 微信侧本地兜底（v1.5.1）。
     *
     * v1.5 把配置改成「微信进程零落盘 + 纯广播」，在本机直接翻车：MIUI 的唤醒路径管控会把
     * 「微信 → 乐信」的广播判成 Security_WakePath 拒掉（logcat 实证：
     * {@code Broadcast Reject caller=com.tencent.mm callee=io.github.lex0m.lexin action=x.q}），
     * 于是微信每次启动都只能读默认值 —— 用户在面板里打开的开关永远不生效。
     *
     * 现在恢复 v1.4 的兜底：微信进程自己也存一份，写进它本来就在用的
     * {@code <pkg>_preferences}，键名做 hex 混淆（{@code ap_616473} = "ads"），
     * 不新建文件、不留明文语义。广播仍然保留，用于「模块侧改完立刻生效」。
     */
    private static String prefKey(String logical) {
        StringBuilder sb = new StringBuilder(LEGACY_PREFIX);
        try {
            for (byte b : logical.getBytes("UTF-8")) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
        } catch (Throwable ignored) {
            return LEGACY_PREFIX + Integer.toHexString(logical.hashCode());
        }
        return sb.toString();
    }

    /** 反解 {@link #prefKey}；老版本那种 hash 键解不出来（返回 null，交给迁移逻辑）。 */
    private static String prefLogical(String phys) {
        if (phys == null || !phys.startsWith(LEGACY_PREFIX)) return null;
        String hex = phys.substring(LEGACY_PREFIX.length());
        if (hex.isEmpty() || (hex.length() & 1) != 0) return null;
        try {
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++) {
                int v = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                if (v < 0x20 || v > 0x7E) return null;      // 老 hash 键不是 ASCII
                b[i] = (byte) v;
            }
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 微信侧启动：读模块侧镜像（v1.6.0）。
     *
     * v1.5.1 在这里读的是**微信自己的** prefs，并且把配置写回去当兜底 —— 那是整套方案里最硬的
     * 一处痕迹：微信只要枚举自己的 SharedPreferences 就能看见 ap_* 键，还能顺着键名反推功能。
     * 现在改成只读模块侧（XSharedPreferences，全反射），读不到就等广播，再没有就用默认值。
     * 宁可功能按默认档跑，也不往对方目录写一个字节。
     */
    private static void loadModulePrefs(Context c) {
        try {
            String blob = XP.read(MODULE_PKG, XP.KEY);
            if (blob == null || blob.isEmpty()) {
                log("module prefs unavailable");
                return;
            }
            byte[] raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP);
            byte[] plain = xor(raw, raw.length);
            String text = new String(plain, "UTF-8");
            int n = 0;
            synchronized (Storage.class) {
                mem.clear();
                for (String line : text.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    mem.put(line.substring(0, eq), line.substring(eq + 1));
                    n++;
                }
            }
            if (n > 0) {
                confident = true;
                log("module prefs loaded " + n);
            }
        } catch (Throwable t) {
            log("module prefs: " + t.getClass().getSimpleName());
        }
    }

    private static void migrateLegacyPrefs(Context ctx) {
        try {
            // 运行期拼装：dex 常量池里不留这个文件名（R8 会把字面量拼接折叠，必须用运行期值派生）
            String legacyFile = ctx.getPackageName() + "_preferences";
            android.content.SharedPreferences old =
                    ctx.getSharedPreferences(legacyFile, Context.MODE_PRIVATE);
            Map<String, ?> all = old.getAll();
            if (all == null || all.isEmpty()) return;

            android.content.SharedPreferences.Editor e = null;
            int migrated = 0;
            java.util.Set<String> done = new java.util.HashSet<>();
            for (String logical : LEGACY_KEYS) {
                String phys = LEGACY_PREFIX + Integer.toHexString(logical.hashCode());
                if (!all.containsKey(phys)) continue;
                done.add(phys);
                Object v = all.get(phys);
                String enc = null;
                if (v instanceof Boolean) enc = encodeBool((Boolean) v);
                else if (v instanceof Integer) enc = encodeInt((Integer) v);
                else if (v instanceof String) enc = encodeStr((String) v);
                if (enc == null) continue;
                put(logical, enc);
                if (e == null) e = old.edit();
                e.remove(phys);
                migrated++;
            }
            // v1.5.1 写过「ap_+字节hex」形式的键。以前这里把所有 ap_* 一律 remove，
            // 认不出的（正是 v1.5.x 用户的数据）被直接删掉 —— 升级即丢配置。
            // 现在先按 hex 反解再迁一次，最后只删确实搬走了的。
            for (String k : all.keySet()) {
                if (k == null || !k.startsWith(LEGACY_PREFIX)) continue;
                if (done.contains(k)) continue;                  // 已经在上面的循环里处理过
                String logical = prefLogical(k);
                if (logical != null && !mem.containsKey(logical)) {
                    Object v = all.get(k);
                    String enc = null;
                    if (v instanceof Boolean) enc = encodeBool((Boolean) v);
                    else if (v instanceof Integer) enc = encodeInt((Integer) v);
                    else if (v instanceof String) enc = encodeStr((String) v);
                    if (enc != null) {
                        put(logical, enc);
                        migrated++;
                    }
                }
                if (e == null) e = old.edit();
                e.remove(k);
            }
            if (e != null) e.apply();
            if (migrated > 0) {
                log("migrated " + migrated + " legacy keys");
                invalidate();
                prefetch();
            }
        } catch (Throwable ignored) {
            // 迁移失败不影响功能：微信侧继续用默认值，模块侧继续用自己的文件
        }
    }

    /**
     * 背景图自愈：图已经在模块侧（bg.img）但配置项为空（升级/迁移后常见）——
     * 把「有背景」补回去，否则用户会看到背景没了。
     */
    private static void healBackground() {
        try {
            if (appCtx == null) return;
            File bg = new File(appCtx.getFilesDir(), "bg.img");
            if (!bg.isFile() || bg.length() <= 0) return;
            if (!str("bf_bg", "").isEmpty()) return;
            put("bf_bg", encodeStr("host"));
            put("theme", encodeBool(true));
            log("background healed");
        } catch (Throwable ignored) {
            // 自愈失败不影响其它功能
        }
    }

    /** provider 读全量用：返回当前镜像（不改内部状态）。 */
    static Map<String, String> snapshot() {
        ensureLoaded();
        synchronized (Storage.class) {
            return new HashMap<>(mem);
        }
    }

    /** provider 写入口（模块进程调）：值已是编码后的 b:/i:/s: 形式。 */
    static void putEncoded(String key, String encoded) {
        put(key, encoded);
    }

    /**
     * 微信侧：收到模块推来的全量（{@link CfgBus#FULL}）。整份替换内存镜像。
     * 这条路径是异步的 —— 微信刚起来那几十毫秒里读到的是默认值，拿到推送后自动切到真实配置。
     */
    static void applyRemote(java.util.List<String> kv) {
        if (kv == null) return;
        boolean ready;
        synchronized (Storage.class) {
            mem.clear();
            for (String line : kv) {
                int eq = line == null ? -1 : line.indexOf('=');
                if (eq <= 0) continue;
                mem.put(line.substring(0, eq), line.substring(eq + 1));
            }
            stamp = System.currentTimeMillis();
            loaded = true;
            loading = false;
            // 空列表不算"拿到配置"：首次安装时模块侧可能还没写出任何键，
            // 把它当"用户全关了"会让懒挂载把功能饿死。
            if (!mem.isEmpty()) confident = true;
            ready = confident;
        }
        // 配置到位 → 让 Features 补装那些因为"配置未知"被跳过的默认关功能
        if (ready) {
            try {
                Features.onConfigReady();
            } catch (Throwable ignored) {
                // 模块还没初始化完就忽略，下次推送再来
            }
        }
    }

    /** 模块侧：读自己的文件（混淆后按行 k=v）。 */
    private static void loadFromDisk() {
        if (appCtx == null) return;
        File f = dataFile();
        if (f == null || !f.isFile() || f.length() <= 0) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 256 * 1024)];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            byte[] plain = xor(buf, off);
            String text = new String(plain, 0, off, "UTF-8");
            synchronized (Storage.class) {
                mem.clear();
                for (String line : text.split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    mem.put(line.substring(0, eq), unescape(line.substring(eq + 1)));
                }
            }
        } catch (Throwable t) {
            log("disk read: " + t.getClass().getSimpleName());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    // ── 写 ───────────────────────────────────────────────────────────────────

    /**
     * 唯一的写入口。
     * 模块侧 → 写自己的文件；微信侧 → 走 provider 让模块进程写（微信进程零落盘）。
     */
    private static void put(String key, String encoded) {
        if (key == null) return;
        synchronized (Storage.class) {
            mem.put(key, encoded);
        }
        if (hostSide) {
            // v1.6：宿主侧只在内存里更新，并如实告诉模块侧（不写任何文件）
            putViaProvider(key, encoded);
        } else {
            dirty = true;
            flush();
            notifyHosts();
        }
    }

    /** 微信侧：把一项改动交给模块进程落盘（广播，不走 provider —— 包可见性把 provider 封死了）。 */
    private static void putViaProvider(String key, String encoded) {
        if (appCtx == null) return;
        CfgBus.putRemote(appCtx, key, encoded);
    }

    /** 模块侧：把内存镜像落盘（混淆）。低频（只在用户改设置时发生）。 */
    private static void flush() {
        if (!dirty || hostSide || appCtx == null) return;
        File f = dataFile();
        if (f == null) return;
        FileOutputStream os = null;
        try {
            StringBuilder sb = new StringBuilder(mem.size() * 24 + 32);
            synchronized (Storage.class) {
                for (Map.Entry<String, String> e : mem.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            byte[] plain = sb.toString().getBytes("UTF-8");
            byte[] enc = xor(plain, plain.length);
            os = new FileOutputStream(f, false);
            os.write(enc);
            os.flush();
            dirty = false;
            publishForHost();
        } catch (Throwable t) {
            log("disk write: " + t.getClass().getSimpleName());
        } finally {
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 模块侧：写一份「给宿主读」的镜像（模块私有目录，微信只读）。
     *
     * 为什么不直接让微信读 .cfg：.cfg 在模块进程私有目录，宿主进程没有权限。
     * 这份走框架的 prefs 通道，是唯一「不碰微信目录、又能跨进程读」的路径。
     */
    private static void publishForHost() {
        try {
            Context c = appCtx;
            if (c == null || hostSide) return;
            StringBuilder sb = new StringBuilder(mem.size() * 24 + 32);
            synchronized (Storage.class) {
                for (Map.Entry<String, String> e : mem.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
            }
            byte[] plain = sb.toString().getBytes("UTF-8");
            byte[] enc = xor(plain, plain.length);
            String b64 = android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP);
            // LSPosed 的新 XSharedPreferences（API 93+）要求模块侧用 MODE_WORLD_READABLE 建文件，
            // 配合 manifest 的 xposedsharedprefs meta-data；targetSdk 35 上会抛 SecurityException，
            // 所以失败就退回私有模式（此时宿主读不到，靠广播）。
            android.content.SharedPreferences sp;
            try {
                sp = c.getSharedPreferences(XP.PREFS, 1);      // MODE_WORLD_READABLE
            } catch (Throwable t2) {
                sp = c.getSharedPreferences(XP.PREFS, Context.MODE_PRIVATE);
            }
            sp.edit().putString(XP.KEY, b64).commit();
            // 尽力放开读权限（SELinux 仍可能拦；拦了就走广播，功能不受影响）
            try {
                java.io.File dir = new java.io.File(c.getApplicationInfo().dataDir, "shared_prefs");
                java.io.File f = new java.io.File(dir, XP.PREFS + ".xml");
                dir.setExecutable(true, false);
                dir.setReadable(true, false);
                f.setReadable(true, false);
            } catch (Throwable ignored) {
                // 改不动权限不影响主流程
            }
        } catch (Throwable t) {
            log("publish: " + t.getClass().getSimpleName());
        }
    }

    /** 模块侧：把最新全量推给微信（改完设置立即生效的那条路）。 */
    private static void notifyHosts() {
        if (appCtx == null) return;
        CfgBus.pushAll(appCtx, snapshot());
    }

    private static File dataFile() {
        try {
            Context c = appCtx;
            if (c == null || hostSide) return null;
            return new File(c.getFilesDir(), DATA_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 编解码 ───────────────────────────────────────────────────────────────

    /** b:1 / i:123 / s:文本（换行与反斜杠转义）。 */
    static String encodeBool(boolean v) { return v ? "b:1" : "b:0"; }
    static String encodeInt(int v) { return "i:" + v; }
    static String encodeStr(String v) { return "s:" + escape(v == null ? "" : v); }

    private static boolean asBool(String s, boolean def) {
        if (s == null || s.length() < 3 || s.charAt(0) != 'b') return def;
        return "b:1".equals(s);
    }

    private static int asInt(String s, int def) {
        if (s == null || s.length() < 3 || s.charAt(0) != 'i') return def;
        try { return Integer.parseInt(s.substring(2)); } catch (Throwable t) { return def; }
    }

    private static String asStr(String s, String def) {
        if (s == null || s.length() < 3 || s.charAt(0) != 's') return def;
        return unescape(s.substring(2));
    }

    private static String escape(String s) {
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
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else sb.append(n);
            } else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 字节混淆：固定派生密钥的 XOR。
     * 这不是加密（挡不住逆向），目的是让"提出来一个文件就能读出功能开关"这件事不成立。
     */
    private static byte[] xor(byte[] data, int len) {
        byte[] out = new byte[len];
        int k = keySeed();
        for (int i = 0; i < len; i++) {
            out[i] = (byte) (data[i] ^ (byte) ((k >>> ((i & 3) * 8)) & 0xFF));
        }
        return out;
    }

    private static int keySeed() {
        // 由包名与类名派生：改包名不会崩，只是读不出旧文件（此时回落到默认值，安全）
        int h = 0x5f3759df;
        String s = MODULE_PKG + "#" + DATA_FILE;
        for (int i = 0; i < s.length(); i++) h = h * 131 + s.charAt(i);
        return h;
    }

    // ── 对外 API（与原实现逐字节兼容） ───────────────────────────────────────

    static boolean on(String key, boolean def) {
        try {
            ensureLoaded();
            return asBool(mem.get(key), def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    static void setOn(String key, boolean value) {
        try { put(key, encodeBool(value)); } catch (Throwable ignored) { }
    }

    static String str(String key, String def) {
        try {
            ensureLoaded();
            return asStr(mem.get(key), def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    static void setStr(String key, String value) {
        try { put(key, encodeStr(value)); } catch (Throwable ignored) { }
    }

    static int num(String key, int def) {
        try {
            ensureLoaded();
            return asInt(mem.get(key), def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    static void setNum(String key, int value) {
        try { put(key, encodeInt(value)); } catch (Throwable ignored) { }
    }

    /** 诊断是否开启（持久化在模块侧，微信进程只读）。 */
    static boolean diag() {
        return on(K_DIAG, false);
    }

    /**
     * 诊断消息：**只进内存**，绝不落盘、绝不进 logcat 之外的任何地方。
     * v1.4 之前这里会往微信 prefs 写明文（实测值 "trigger: panel shown"），已改掉。
     */
    static void diag(String msg) {
        lastDiag = msg == null ? "" : msg;
    }

    static String lastDiag() {
        return lastDiag;
    }

    /** 诊断：当前在哪一侧（不含路径，避免把目录名写进任何地方）。 */
    static String side() {
        return "host=" + hostSide + " loaded=" + loaded + " n=" + mem.size();
    }

    static boolean isHostSide() {
        return hostSide;
    }

    /** 配置是否已确认拿到（懒挂载的闸门）。 */
    static boolean confident() {
        return confident;
    }

    private static void log(String s) {
        try {
            Log.d(T + s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private Storage() {}
}
