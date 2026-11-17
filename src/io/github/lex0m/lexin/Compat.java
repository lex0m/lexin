package io.github.lex0m.lexin;

import android.content.Context;
import android.os.Build;

/**
 * 环境兼容层（v1.7.9，v1.7.22 免 root 自审轮修正框架判定）。
 *
 * 借鉴 wcx `VersionCompat` 与 WeKit `@Feature` 隔离的两条原则：
 *   ① **不按版本号写 if-else 分支** —— 版本号只用于"告知与诊断"；
 *      能不能用取决于"类/特征能否解析"，那由 {@link Anchors} / {@link DexFind} 回答。
 *   ② 兼容性结论要给用户**一句人话**（未适配 X/Y 项 → 三档），
 *      而不是让人盯着一排红条自己猜"是不是整个模块坏了"。
 *
 * 这里只收"环境事实"，不做判断。判断在 {@link SettingsPanel#compatNote(android.app.Activity)}。
 *
 * <h3>v1.7.22 修的两件事</h3>
 * <ol>
 *   <li><b>"同 loader ⇒ 内嵌"这条判据在 LSPatch/NPatch 下是错的</b>：Vector 用
 *       {@code org.matrix.vector.impl.utils.VectorModuleClassLoader}（内部再套
 *       {@code ByteBufferDexClassLoader}）加载模块，宿主与模块**永远不是同一个 loader**。
 *       上一版只要 {@code detectInjected()} 拿不到宿主 APK（早期调用 / app 还没 set），
 *       就会把免 root 误判成「LSPosed/Xposed」并把错值缓存死。现在：判不出来就报"未就绪"、
 *       **不进缓存**，等 app 就绪再判。</li>
 *   <li><b>补丁包的默认命名</b>（{@code *-lspatched.apk} / {@code *-npatched.apk}）是零成本指纹，
 *       先看文件名，再决定要不要打开 300MB 的宿主 APK 读中央目录。</li>
 * </ol>
 */
final class Compat {

    private static final String T = "svc-compat: ";

    /** 声明支持的系统下限（minSdk），与 AndroidManifest 一致。 */
    static final int SDK_MIN = 28;
    /** 真机验证过的系统上限：Android 15。更高版本"未验证"，不是"不支持"。 */
    static final int SDK_TESTED = 35;

    static final String FW_LSPATCH = "LSPatch/内嵌";
    static final String FW_NPATCH = "NPatch/内嵌";
    static final String FW_EMBED_SAME = "内嵌（同 loader）";
    static final String FW_LSPOSED = "LSPosed/Xposed";
    static final String FW_UNKNOWN = "未知";
    static final String FW_NOT_READY = "未就绪";

    private static String cachedRom;
    private static String cachedFramework;
    private static String cachedSnapshot;
    private static Boolean cachedRootless;

    static int sdk() {
        return Build.VERSION.SDK_INT;
    }

    static boolean atLeast(int n) {
        return sdk() >= n;
    }

    /**
     * Android 15（API 35）起，`dataSync` 型前台服务有 **6 小时累计上限**，
     * 超时系统会回调 `Service.onTimeout()` 并停掉前台状态。
     * 语音导出的常驻监听会因此"6 小时后静默失效"——这是真兼容点，不是理论问题。
     */
    static boolean hasFgsTimeout() {
        return sdk() >= 35;
    }

    /** 系统版本短名：Android 9 / 10 … 15 / 16（预览）。 */
    static String sdkName() {
        String rel = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        int dot = rel.indexOf('.');
        String major = dot > 0 ? rel.substring(0, dot) : rel;
        return major.isEmpty() ? ("API " + sdk()) : ("Android " + major);
    }

    /** ROM 家族（只用于诊断与文案；不参与任何功能分支）。 */
    static String rom() {
        String r = cachedRom;
        if (r != null) return r;
        r = detectRom();
        cachedRom = r;
        return r;
    }

    private static String detectRom() {
        try {
            String oplus = prop("ro.build.version.oplusrom");
            String miui = prop("ro.miui.ui.version.name");
            String honor = prop("ro.build.version.magic");
            if (!oplus.isEmpty()) return "ColorOS/realme UI";
            if (!miui.isEmpty()) return "MIUI/HyperOS";
            if (!honor.isEmpty()) return "MagicOS";
            String disp = String.valueOf(Build.DISPLAY).toUpperCase();
            String inc = String.valueOf(Build.VERSION.INCREMENTAL).toUpperCase();
            if (disp.contains("COLOROS") || inc.contains("COLOROS")) return "ColorOS";
            if (disp.contains("MIUI") || inc.contains("MIUI")) return "MIUI";
            if (disp.contains("HYPEROS")) return "HyperOS";
            String mf = String.valueOf(Build.MANUFACTURER);
            return mf.isEmpty() ? "未知 ROM" : mf;
        } catch (Throwable t) {
            return "未知 ROM";
        }
    }

    /**
     * 框架形态：先看**注入产物**，再看 loader。
     *
     * 官方接口（LSPatch v1.2 / NPatch v1.1.0）把 native 核与 loader 直接写进宿主 APK：
     *   LSPatch → assets/lspatch/…（loader.dex / metaloader.dex / so/）
     *   NPatch  → assets/npatch/loader.bin + libnpatch.so
     * 补丁包的默认文件名也带着指纹（-lspatched.apk / -npatched.apk），先看名字最省事。
     */
    static String framework() {
        String f = cachedFramework;
        if (f != null) return f;
        f = detectFramework();
        if (conclusive(f)) cachedFramework = f;     // 判不出来不缓存：等 Features.app 就绪再判
        return f;
    }

    private static boolean conclusive(String f) {
        return f != null && !FW_NOT_READY.equals(f) && !FW_UNKNOWN.equals(f);
    }

    private static String detectFramework() {
        String injected = detectInjected();
        if (injected != null) return injected;
        try {
            ClassLoader xp = de.robv.android.xposed.XposedBridge.class.getClassLoader();
            ClassLoader self = Compat.class.getClassLoader();
            if (xp == null) return FW_NOT_READY;
            if (xp == self) return FW_EMBED_SAME;
            // 模块 loader 与框架不同：常规 LSPosed 形态，或者"宿主 APK 读不到"的免 root。
            // 后者由 detectInjected 的资产/文件名判据兜住；这里只在 app 已就绪时才敢下结论。
            return Features.app == null ? FW_NOT_READY : FW_LSPOSED;
        } catch (Throwable t) {
            // 模块自己的进程（LSPosed 的 SettingsActivity）里没有 Xposed 类，属正常
            return FW_UNKNOWN;
        }
    }

    /**
     * 读宿主 APK 认注入产物。
     *
     * 三级判据（从便宜到贵）：
     *   ① 文件名后缀 `-lspatched.apk` / `-npatched.apk`（两条线打补丁时的默认输出名）
     *   ② 中央目录里的 `assets/lspatch/…` / `assets/npatch/…`
     *   ③ 逐个条目扫前缀（有些打包器不写目录条目）
     */
    private static String detectInjected() {
        try {
            Context c = Features.app;
            if (c == null) return null;
            String apk = c.getApplicationInfo().sourceDir;
            if (apk == null) return null;
            String lower = apk.toLowerCase();
            if (lower.contains("lspatched")) return FW_LSPATCH;
            if (lower.contains("npatched")) return FW_NPATCH;
            java.util.zip.ZipFile z = null;
            try {
                z = new java.util.zip.ZipFile(apk);
                if (z.getEntry("assets/npatch/loader.bin") != null
                        || z.getEntry("assets/npatch/libnpatch.so") != null
                        || z.getEntry("assets/npatch/") != null) return FW_NPATCH;
                if (z.getEntry("assets/lspatch/loader.dex") != null
                        || z.getEntry("assets/lspatch/metaloader.dex") != null
                        || z.getEntry("assets/lspatch/config.json") != null
                        || z.getEntry("assets/lspatch/") != null) return FW_LSPATCH;
                // 目录条目有时不写成 entry：兜底扫一遍前缀
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    if (n.startsWith("assets/npatch/")) return FW_NPATCH;
                    if (n.startsWith("assets/lspatch/")) return FW_LSPATCH;
                }
            } finally {
                if (z != null) try { z.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            // 宿主 APK 读不到（SELinux 等）就退回 loader 判定
        }
        return null;
    }

    /**
     * 是否"免 root 内嵌框架"（模块 dex 跑在宿主进程里，没有独立模块 App/进程）。
     *
     * 这不是诊断标签，是**能力开关**：这类环境里模块没有自己的 Application、ContentProvider、
     * native 库和桌面图标，凡是以"模块进程存在"为前提的链路都必须降级而不是静默失败。
     */
    static boolean isRootless() {
        Boolean r = cachedRootless;
        if (r != null) return r;
        String f = framework();
        if (!conclusive(f)) return false;           // 还没判出来：先按常规形态走，别提前降级
        r = f.startsWith("LSPatch") || f.startsWith("NPatch") || f.startsWith("内嵌");
        cachedRootless = r;
        return r;
    }

    /**
     * 模块是否被**独立安装**（有 package、有 data dir、有 prefs）。
     *
     * 免 root 内嵌框架下模块只有 dex 被塞进宿主，PackageManager 查不到它 ——
     * 于是所有"模块自己的 prefs / 自己的进程 / 自己的组件"为前提的链路都要降级。
     * 这条判据很便宜（一次 PM 查询），比 {@link #isRootless()}（要读宿主 APK）轻得多，
     * 适合在启动早期（Storage.init / Application.attach）用。
     */
    static boolean moduleInstalled() {
        try {
            Context c = Features.app;
            if (c == null) return true;             // 还没就绪：先按常规形态走
            Assets.SELF_QUERY.set(Boolean.TRUE);
            try {
                c.getPackageManager().getApplicationInfo("io.github.lex0m.lexin", 0);
                return true;
            } finally {
                Assets.SELF_QUERY.remove();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 免 root 下明确不可用的能力（面板/提示用，避免用户以为模块坏了）。 */
    static String rootlessLimits() {
        if (!isRootless()) return null;
        return "该框架没有独立模块进程：语音导出转码、独立设置页与内置转码库不可用；"
                + "主题素材改从宿主 assets 里的模块包读，配置落在微信 prefs";
    }

    /** 一行环境快照，写进 status 头 / 面板诊断行。 */
    static String snapshot(Context ctx) {
        String s = cachedSnapshot;
        if (s != null) return s;
        StringBuilder sb = new StringBuilder();
        sb.append(sdkName()).append('(').append(sdk()).append(')');
        sb.append(" · ").append(rom());
        String f = framework();
        sb.append(" · ").append(f);
        try {
            String wx = HostInfo.versionName(ctx);
            if (wx != null && !wx.isEmpty()) sb.append(" · 微信 ").append(wx);
        } catch (Throwable ignored) {
            // 版本读不到就不写
        }
        s = sb.toString();
        if (conclusive(f)) cachedSnapshot = s;      // "未就绪"的快照不缓存（换来的是一行错的 os=）
        Log.d(T + s);
        return s;
    }

    /**
     * 系统版本是否在"已验证"区间内。
     * 低于 SDK_MIN 装不上（minSdk 拦），高于 SDK_TESTED 只是没验过。
     */
    static boolean sdkVerified() {
        return sdk() >= SDK_MIN && sdk() <= SDK_TESTED;
    }

    /** 反射读系统属性（hidden API 被拦时返回空串，调用方必须容忍）。 */
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 环境变了（换 ROM/换框架不用重启，但换了宿主版本要重算快照）。 */
    static void invalidate() {
        cachedSnapshot = null;
        cachedRom = null;
        cachedFramework = null;
        cachedRootless = null;
    }

    private Compat() {}
}
