package io.github.lex0m.lexin;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 直接从模块 APK 里读资源（主题图 / 加密锚点表）。
 *
 * <p>v1.7.22（免 root 自审轮）把"模块 APK 在哪"从三级扩成**四路探测**，并且每一路都必须
 * **验明正身**（{@link #looksLikeModule}）才采用 —— 以前只要"有个 .apk 路径"就当模块 APK，
 * 在免 root 框架下会把宿主自己/别的模块当成我们，读出一张别人的图或一张过期的表。
 *
 * <table>
 *   <tr><th>来源</th><th>谁会走这里</th><th>形态</th></tr>
 *   <tr><td>① 框架给的 modulePath</td><td>LSPosed（initZygote）</td><td>磁盘 APK</td></tr>
 *   <tr><td>② 宿主 APK 的 assets 内嵌</td><td>LSPatch / NPatch **集成模式**</td><td>内存 APK 字节</td></tr>
 *   <tr><td>③ 当前 ClassLoader 的 DexPathList</td><td>LSPosed / 免 root **本地模式**</td><td>磁盘 APK</td></tr>
 *   <tr><td>④ 宿主私有目录里的模块快照</td><td>LSPatch / NPatch 本地模式</td><td>磁盘 APK</td></tr>
 *   <tr><td>⑤ PackageManager</td><td>模块被独立安装（LSPosed），最后兜底</td><td>磁盘 APK（可能是另一个版本）</td></tr>
 * </table>
 *
 * <h3>v1.7.21 的两个真 bug（本轮修）</h3>
 * <ol>
 *   <li><b>{@code Element.path} 是 {@link File} 不是 String</b>：AOSP 里
 *       {@code dalvik.system.DexPathList$Element#path} 是 {@code File} 字段，
 *       上一版写的是 {@code if (!(p instanceof String)) continue;} ⇒ 在真机上**必然**
 *       一个候选都拿不到，整段是死代码。现在 File / CharSequence / toString 三种都认。</li>
 *   <li><b>集成模式根本没有"模块 APK 路径"</b>：LSPatch/NPatch 把模块 APK 塞进
 *       {@code assets/lspatch/modules/} 或 {@code assets/npatch/modules/}，
 *       运行时用 ByteBuffer 直接加载 dex（{@code ByteBufferDexClassLoader}），
 *       磁盘上从来没有那个文件、PackageManager 也查不到 ⇒ 上一版三条路全空。
 *       现在直接读宿主 assets（公开 API），不用反射、不用安装。</li>
 * </ol>
 */
final class Assets {

    private static final String T = "svc-assets: ";
    private static final String ROOT = "theme/";
    private static final String MODULE_PKG = "io.github.lex0m.lexin";

    /**
     * 模块 APK 的"身份证"。
     *
     * 只认这三样：加密锚点表、明文锚点表（开发包）、xposed_init 里写着我们的入口类。
     * 宿主 APK、别的 Xposed 模块、LSPatch 的 origin.apk 都过不了这一关。
     */
    private static final String MARK_ANCHOR = "assets/anchor.bin";
    private static final String MARK_JSON = "assets/anchors.json";
    private static final String MARK_INIT = "assets/xposed_init";
    private static final String MARK_ENTRY = "io.github.lex0m.lexin.Entry";

    /**
     * "这次 PackageManager 查询是我们自己发起的"标记。
     *
     * 只在本模块读自己 APK 路径时置位，读完立刻清；Stealth 的包名隐藏 hook 见到它就放行。
     * 没有它，包名隐藏和锚点表读取会互相打架（谁先装谁赢，且输的那一方是静默失败）。
     */
    static final ThreadLocal<Boolean> SELF_QUERY = new ThreadLocal<>();

    /** 实际生效的 APK 来源（面板里显示，排查现场问题用）。 */
    private static volatile String source = "未初始化";
    /** 实际生效的读取方式（zip 直读 / asset-manager）。 */
    private static volatile String readHow = "";
    /** 上一次探测为什么什么都没找到（面板显示，默认进日志）。 */
    private static volatile String lastMiss = "";

    private static volatile AssetManager am;
    private static volatile String apkPath;
    /**
     * 模块 APK 的字节：只在"宿主 assets 内嵌"这一路用。
     * 这条路没有磁盘路径，但整套 zip 直读可以照跑 —— 把 APK 当 zip 从内存里解开。
     */
    private static volatile byte[] apkBytes;
    private static volatile Map<String, byte[]> blobEntries;
    private static volatile ZipFile zip;
    /** 是否已做过一次完整探测。失败也置位（{@link #lastProbeAt} 节流后会重试）。 */
    private static volatile boolean probed;
    private static volatile long lastProbeAt;
    private static volatile Boolean themeOk;
    /** 当前来源是否"与进程里跑的 dex 同源"（同源就不用校验版本）。 */
    private static volatile boolean sameGeneration;
    private static volatile boolean versionChecked;
    private static volatile boolean stale;

    /**
     * 由 Entry.initZygote 调用一次，APK 路径由框架给出。
     *
     * zygote 阶段只记路径 —— **这里一行反射都不能有**（新版安卓对隐藏 API 越收越紧，
     * 这个回调是所有回调里最早、最容易把整个模块带崩的一步）。真正的探测推迟到按需。
     */
    static void init(String modulePath) {
        if (modulePath == null || modulePath.isEmpty()) return;
        apkPath = modulePath;
        apkBytes = null;
        source = "框架（initZygote）";
        sameGeneration = true;
        versionChecked = false;
        probed = true;
        Log.d(T + "module path from framework: " + modulePath);
    }

    // ── 探测 ────────────────────────────────────────────────────────────────

    /**
     * 确保 {@link #apkPath} / {@link #apkBytes} 至少有一个可用。
     *
     * 失败后不是永久封口：{@link Features#app} 可能晚一点才就绪（attach 回调之前也会有人问资源），
     * 换 loader / 换框架后也值得再试。用 5 秒节流，避免每次读图都把四条路重跑一遍。
     */
    private static void ensureSource() {
        if (hasSource()) return;
        synchronized (Assets.class) {
            if (hasSource()) return;
            long now = System.currentTimeMillis();
            if (probed && now - lastProbeAt < 5000L) return;
            probed = true;
            lastProbeAt = now;
            probe();
        }
    }

    private static boolean hasSource() {
        return apkBytes != null || (apkPath != null && !apkPath.isEmpty());
    }

    private static void probe() {
        lastMiss = "";
        // ① 框架给的路径（LSPosed 的 initZygote）—— 仍然要验明正身：
        //    老框架/某些 fork 可能给的是宿主路径，那是读别人家的图。
        String p = apkPath;
        if (p != null && !p.isEmpty()) {
            if (usable(p)) {
                adopt(p, "框架（initZygote）", true);
                return;
            }
            noteMiss("framework-path:" + p);
            apkPath = null;
        }
        // ② 宿主 assets 里内嵌的模块 APK（LSPatch / NPatch 集成模式）
        try {
            if (fromHostAssets()) return;
        } catch (Throwable t) {
            noteMiss("host-assets:" + t.getClass().getSimpleName());
        }
        // ③ 当前 ClassLoader 的 DexPathList（LSPosed / 免 root 本地模式）
        try {
            p = fromClassLoader();
            if (p != null) {
                adopt(p, "ClassLoader（运行时 dex）", true);
                return;
            }
        } catch (Throwable t) {
            noteMiss("dexpath:" + t.getClass().getSimpleName());
        }
        // ④ 宿主私有目录里的模块快照（LSPatch / NPatch 本地模式：框架把模块 APK 落到宿主目录）
        try {
            p = fromHostDirs();
            if (p != null) {
                adopt(p, "宿主私有目录快照", true);
                return;
            }
        } catch (Throwable t) {
            noteMiss("host-dirs:" + t.getClass().getSimpleName());
        }
        // ⑤ PackageManager（模块被独立安装）。最后兜底：它可能是**另一个版本**的模块，
        //    anchor.bin 会和进程里的 dex 对不上（见 versionMatches）。
        try {
            p = fromPackageManager();
            if (p != null) {
                adopt(p, "PackageManager（已安装模块）", false);
                return;
            }
        } catch (Throwable t) {
            noteMiss("pm:" + t.getClass().getSimpleName());
        }
        source = "未找到模块 APK";
        Log.d(T + "probe failed: " + lastMiss);
    }

    private static void adopt(String path, String why, boolean sameGen) {
        apkPath = path;
        apkBytes = null;
        blobEntries = null;
        zip = null;
        source = why;
        sameGeneration = sameGen;
        versionChecked = false;
        mount(path);        // 隐藏 API 挂不上也无所谓：zip 直读已覆盖
        Log.d(T + "source=" + why + " path=" + path);
    }

    private static void adopt(byte[] bytes, String why) {
        apkBytes = bytes;
        apkPath = null;
        blobEntries = null;
        zip = null;
        source = why;
        sameGeneration = true;
        versionChecked = false;
        Log.d(T + "source=" + why + " bytes=" + (bytes == null ? -1 : bytes.length));
    }

    private static void noteMiss(String why) {
        lastMiss = lastMiss.isEmpty() ? why : (lastMiss + " | " + why);
    }

    /**
     * 宿主 assets 里的模块 APK。
     *
     * LSPatch 集成模式：{@code assets/lspatch/modules/<模块>.apk}
     * NPatch  集成模式：{@code assets/npatch/modules/<模块>.apk}
     *
     * 用公开 API（AssetManager.list/open），不需要反射、不需要 install、也不需要包可见性。
     * 模型上只留一份字节（1.9MB 级），读完就缓存进 {@link #blobEntries}。
     */
    private static boolean fromHostAssets() {
        Context c = Features.app;
        if (c == null) return false;
        AssetManager hostAm = c.getAssets();
        if (hostAm == null) return false;
        String[] dirs = {"lspatch/modules", "npatch/modules"};
        for (String dir : dirs) {
            String[] names;
            try {
                names = hostAm.list(dir);
            } catch (Throwable t) {
                continue;
            }
            if (names == null || names.length == 0) continue;
            for (String n : names) {
                // 不按扩展名筛：不同 fork 存进来可能叫 <包名>.apk / module.apk / 甚至无后缀
                // （modules.json 这类小文件也会被读一次，但 looksLikeModule 会立刻否掉）。
                if (n == null || n.isEmpty()) continue;
                InputStream in = null;
                byte[] b = null;
                try {
                    in = hostAm.open(dir + "/" + n);
                    b = readAll(in, 64 * 1024 * 1024);
                } catch (Throwable t) {
                    noteMiss("asset-open:" + n);
                } finally {
                    close(in);
                }
                if (b == null || b.length == 0) continue;
                if (!looksLikeModule(b)) {
                    noteMiss("asset-not-module:" + n);
                    continue;
                }
                adopt(b, "宿主 assets（" + dir + "/" + n + "）");
                return true;
            }
        }
        return false;
    }

    /**
     * 当前 ClassLoader 的 DexPathList。
     *
     * LSPosed 把模块 APK 用独立的 PathClassLoader 加载 ⇒ {@code pathList.dexElements[].path}
     * 里就是模块 APK 本身。免 root 本地模式若把模块落成文件，也会出现在这里。
     *
     * <p><b>字段类型</b>：AOSP 是 {@code private final File path}（API 28+ 全版本），
     * 不是 String —— v1.7.21 在这上面写死了 {@code instanceof String}，整段永远不命中。
     */
    private static String fromClassLoader() {
        ClassLoader cl = Assets.class.getClassLoader();
        if (cl == null) return null;
        String host = null;
        try {
            Context c = Features.app;
            if (c != null) host = c.getApplicationInfo().sourceDir;
        } catch (Throwable ignored) {
            // 宿主路径拿不到就只靠"验明正身"过滤
        }
        Object pathList = field(cl, "pathList");
        Object elems = pathList == null ? null : field(pathList, "dexElements");
        if (!(elems instanceof Object[])) return null;
        for (Object e : (Object[]) elems) {
            String path = elementPath(e);
            if (path == null || !path.toLowerCase().endsWith(".apk")) continue;
            if (host != null && path.equals(host)) continue;
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) continue;
            if (!sameFile(f, host) && looksLikeModule(f)) return path;
        }
        return null;
    }

    /**
     * {@code DexPathList.Element#path} 取绝对路径。
     *
     * 三种可能都认：File（AOSP 真实形态）、CharSequence（个别 ROM/旧实现）、
     * {@code Element.toString()}（Android 9+ 直接返回 path 字符串）。
     */
    private static String elementPath(Object e) {
        Object p = field(e, "path");
        if (p instanceof File) return ((File) p).getAbsolutePath();
        if (p instanceof CharSequence) return p.toString();
        try {
            String s = String.valueOf(e);
            if (s != null && s.toLowerCase().endsWith(".apk")) return s;
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static boolean sameFile(File f, String path) {
        try {
            return path != null && f.getAbsolutePath().equals(path);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 宿主私有目录里的模块快照（免 root 本地模式的落盘位置）。
     *
     * 只进 {@code files|lspatch}、{@code files|npatch}、{@code cache|lspatch}、{@code cache|npatch}
     * 这四个子目录 —— 绝不遍历微信自己的 cache（那里几十万个文件，扫一次就是一次可观测的 IO 尖峰）。
     */
    private static String fromHostDirs() {
        Context c = Features.app;
        if (c == null) return null;
        File[] roots = {c.getFilesDir(), c.getCacheDir()};
        String[] sub = {"lspatch", "npatch"};
        for (File root : roots) {
            if (root == null) continue;
            for (String s : sub) {
                String hit = findApk(new File(root, s), 0);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String findApk(File d, int depth) {
        if (d == null || depth > 4 || !d.isDirectory()) return null;
        File[] kids = d.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k == null || !k.isFile()) continue;
            String n = k.getName();
            if (n != null && n.toLowerCase().endsWith(".apk") && looksLikeModule(k)) {
                return k.getAbsolutePath();
            }
        }
        for (File k : kids) {
            if (k == null || !k.isDirectory()) continue;
            String hit = findApk(k, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 模块被独立安装时的 APK（LSPosed 的常规形态）。 */
    private static String fromPackageManager() {
        Context c = Features.app;
        if (c == null) return null;
        String p = null;
        // ★ v1.7（C-01 自噬）：Stealth.hideOwnPackage 会把
        //   ApplicationPackageManager.getApplicationInfo(OURS) 改成抛 NameNotFoundException，
        //   而这里**正是**靠这个查询拿模块自己的 APK 路径 —— 于是隐蔽功能一旦装上，
        //   锚点表就再也读不到，全功能静默哑掉。用 ThreadLocal 给"自己人"开一条口子。
        SELF_QUERY.set(Boolean.TRUE);
        try {
            p = c.getPackageManager().getApplicationInfo(MODULE_PKG, 0).sourceDir;
        } catch (Throwable t) {
            noteMiss("pm-query:" + t.getClass().getSimpleName());
        } finally {
            SELF_QUERY.remove();
        }
        return usable(p) ? p : null;
    }

    /** 这个路径是不是"能读的乐信模块 APK"。 */
    private static boolean usable(String path) {
        if (path == null || path.isEmpty()) return false;
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return false;
        return looksLikeModule(f);
    }

    // ── 验明正身 ────────────────────────────────────────────────────────────

    /** 磁盘 APK：含加密锚点表 / 明文表，或 xposed_init 写着我们的入口类。 */
    private static boolean looksLikeModule(File f) {
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            if (z.getEntry(MARK_ANCHOR) != null || z.getEntry(MARK_JSON) != null) return true;
            ZipEntry e = z.getEntry(MARK_INIT);
            if (e == null) return false;
            InputStream in = z.getInputStream(e);
            try {
                byte[] b = readAll(in, 4096);
                return b != null && new String(b, "UTF-8").contains(MARK_ENTRY);
            } finally {
                close(in);
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            close(z);
        }
    }

    /** 内存 APK：同一套判据，走 ZipInputStream（不解压整包，只按条目名走）。 */
    private static boolean looksLikeModule(byte[] apk) {
        if (apk == null || apk.length < 4) return false;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new ByteArrayInputStream(apk));
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (MARK_ANCHOR.equals(n) || MARK_JSON.equals(n)) return true;
                if (MARK_INIT.equals(n)) {
                    byte[] b = readAll(zis, 4096);
                    if (b != null && new String(b, "UTF-8").contains(MARK_ENTRY)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 坏包/截断：当它不是模块
        } finally {
            close(zis);
        }
        return false;
    }

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 拿不到 AssetManager 时的直读路径：把模块 APK 当 zip 打开（内存包也一样）。 */
    private static InputStream openDirect(String name) {
        ensureSource();
        String entry = "assets/" + name;
        byte[] mem = apkBytes;
        if (mem != null) {
            Map<String, byte[]> m = ensureBlobEntries();
            byte[] b = m == null ? null : m.get(entry);
            if (b == null) return null;
            readHow = "内存 zip";
            return new ByteArrayInputStream(b);
        }
        String path = apkPath;
        if (path == null) return null;
        try {
            ZipFile zf = zip;
            if (zf == null) {
                zf = new ZipFile(path);
                zip = zf;                          // 缓存，别每次重开（也不关：整个进程都要用）
            }
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            // 读进内存再返回：调用方会立刻 close，直接把 ZipFile 的流交出去会被抽走
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            InputStream raw = zf.getInputStream(e);
            byte[] buf = new byte[8192];
            int n;
            while ((n = raw.read(buf)) > 0) out.write(buf, 0, n);
            raw.close();
            readHow = "zip 直读";
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Throwable t) {
            zip = null;
            Log.d(T + "zip read failed: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 把内存 APK 里 {@code assets/} 下的条目全捞出来（模块包很小，一次扫完就够用一辈子）。 */
    private static Map<String, byte[]> ensureBlobEntries() {
        Map<String, byte[]> m = blobEntries;
        if (m != null) return m;
        byte[] apk = apkBytes;
        if (apk == null) return null;
        synchronized (Assets.class) {
            if (blobEntries != null) return blobEntries;
            Map<String, byte[]> out = new HashMap<>();
            ZipInputStream zis = null;
            try {
                zis = new ZipInputStream(new ByteArrayInputStream(apk));
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n == null || !n.startsWith("assets/") || e.isDirectory()) continue;
                    byte[] b = readAll(zis, 8 * 1024 * 1024);
                    if (b != null) out.put(n, b);
                }
            } catch (Throwable t) {
                Log.d(T + "blob scan failed: " + t.getClass().getSimpleName());
            } finally {
                close(zis);
            }
            blobEntries = out;
            return out;
        }
    }

    private static byte[] readAll(InputStream in, int max) {
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    // ── 对外 API ────────────────────────────────────────────────────────────

    /** 按宿主自己的短资源名加载主题素材，没有就返回 null。 */
    static Drawable drawable(String shortName) {
        if (shortName == null || shortName.isEmpty()) return null;
        ensureSource();
        for (String ext : new String[]{"", ".png", ".webp", ".jpg"}) {
            InputStream in = null;
            try {
                in = openDirect(ROOT + shortName + ext);
                if (in == null && am != null) in = am.open(ROOT + shortName + ext);
                if (in == null) continue;
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) return new BitmapDrawable(null, bmp);
            } catch (Throwable ignored) {
                // 试下一个扩展名
            } finally {
                close(in);
            }
        }
        return null;
    }

    /** APK 里是否有这个名字的主题素材。 */
    static boolean has(String shortName) {
        return drawable(shortName) != null;
    }

    /** 读模块 APK 里的文本资源。 */
    static String text(String path) {
        byte[] raw = bytes(path);
        if (raw == null) return null;
        try {
            return new String(raw, "UTF-8");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 锚点表（v1.6.0：优先读加密后的 anchor.bin）。
     *
     * 明文 anchors.json 只为开发/降级保留：构建期 package.py 会把明文换成密文，
     * 正式包里根本不存在 anchors.json 这个条目。
     */
    static String anchorText() {
        ensureSource();
        if (versionMatches()) {
            byte[] raw = bytes("anchor.bin");
            if (raw != null && raw.length > 0) {
                String s = AnchorCodec.decrypt(raw);
                if (s != null && !s.isEmpty()) return s;
            }
        } else {
            Log.d(T + "skip anchor.bin: stale module apk");
        }
        // ★ v1.7（C-01 收口）：assets 读不到就用 dex 里内置的那份。
        //   免 root 内嵌模式（LSPatch/NPatch）下：没有 initZygote 拿不到模块路径、包可见性又封死
        //   getApplicationInfo、框架还可能只把 dex 挂进来不落 APK ⇒ 上面这条永远失败。
        //   没有这个兜底，那些环境里整个模块是静默哑的，且日志里看不出原因。
        byte[] builtin = AnchorData.bytes();
        if (builtin != null && builtin.length > 0) {
            String s = AnchorCodec.decrypt(builtin);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;   // 正式包只有密文：明文回退会让 dex 里留下 "anchors.json" 这个特征串
    }

    /** 读 asset 的原始字节。公开 API（直读 zip）优先，隐藏 API 兜底。 */
    static byte[] bytes(String path) {
        ensureSource();
        InputStream in = null;
        try {
            in = openDirect(path);
            if (in == null && am != null) {         // zip 读不到（SELinux 之类）才试隐藏 API
                in = am.open(path);
            }
            if (in == null) return null;
            return readAll(in, 8 * 1024 * 1024);
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
        }
    }

    /** 模块 APK 是否可用（面板用它判断能不能读锚点表）。 */
    static boolean ready() {
        ensureSource();
        return am != null || hasSource();
    }

    /** 主题素材当前是否真的读得到（面板据此提示"这个框架下主题会哑"）。 */
    static boolean themeAvailable() {
        Boolean ok = themeOk;
        if (ok != null) return ok;
        boolean v = bytes(ROOT + "ChatRoom_Bubble_Text_Receiver_White.png") != null
                || bytes(ROOT + "ChatRoom_Bubble_Text_Sender_Green.png") != null
                || bytes(ROOT + "icons_out_wechat.png") != null;
        // 只在"这次探测真有结论"时缓存：app 还没就绪时问一句会得到 false，
        // 把它缓存住就会让面板永远显示"主题不可用"（而其实几秒后就能读到）。
        if (hasSource()) themeOk = v;
        return v;
    }

    /** 模块 APK 的绝对路径；内存内嵌模式下为 null（这时看 {@link #source()}）。 */
    static String apk() {
        ensureSource();
        return apkPath;
    }

    /** 当前资源来源（人话，面板显示）：来源 + 读法。 */
    static String source() {
        ensureSource();
        String s = source;
        String how = readHow;
        return (how == null || how.isEmpty()) ? s : (s + " · " + how);
    }

    /** 上一次探测失败的细节（面板/诊断用；正常情况是空串）。 */
    static String lastMiss() {
        return lastMiss;
    }

    /** 环境变了（换 loader / 换框架）允许重新探测一次。 */
    static void resetProbe() {
        synchronized (Assets.class) {
            if (!hasSource() || "PackageManager（已安装模块）".equals(source)) {
                // 只有"没找到"或"用的安装版兜底"才值得重探；框架给的路径继续用
                probed = false;
                lastProbeAt = 0L;
            }
            themeOk = null;
            versionChecked = false;
            stale = false;
        }
    }

    // ── 杂项 ────────────────────────────────────────────────────────────────

    /**
     * 当前 APK 是否和进程里跑的 dex 同一版本。
     *
     * 只有 PackageManager 这一路可能拿到"另一个版本"（用户装着旧版模块、框架里跑的是新版）。
     * 版本对不上时不用它的 anchor.bin —— 否则会出现"锚点表是旧的、功能挂到错类上"，
     * 症状和表写错一模一样。主题图不受影响（图不分版本）。
     */
    private static boolean versionMatches() {
        if (versionChecked) return !stale;
        versionChecked = true;
        if (sameGeneration || apkBytes != null) {
            stale = false;
            return true;
        }
        String p = apkPath;
        if (p == null) return true;
        String v = buildInfoVersion(p);
        if (v == null || v.isEmpty()) return true;      // 开发包没有 build-info：不拦
        stale = !v.equals(Features.MODULE_VERSION);
        if (stale) Log.d(T + "stale module apk: " + v + " != " + Features.MODULE_VERSION);
        return !stale;
    }

    /** 读 APK 里 assets/build-info.txt 的 version 行。 */
    private static String buildInfoVersion(String path) {
        ZipFile z = null;
        InputStream in = null;
        try {
            z = new ZipFile(path);
            ZipEntry e = z.getEntry("assets/build-info.txt");
            if (e == null) return null;
            in = z.getInputStream(e);
            byte[] b = readAll(in, 4096);
            if (b == null) return null;
            String text = new String(b, "UTF-8");
            for (String line : text.split("\n")) {
                String s = line.trim();
                // 构建脚本写的是 versionName= / versionCode= / buildId=（见 tools/package.py）；
                // 老格式是 version=，两个都认。
                if (s.startsWith("versionName=")) return s.substring("versionName=".length()).trim();
                if (s.startsWith("version=")) return s.substring("version=".length()).trim();
            }
        } catch (Throwable ignored) {
            // 读不到就不拦
        } finally {
            close(in);
            close(z);
        }
        return null;
    }

    private static void mount(String path) {
        try {
            AssetManager m = AssetManager.class.newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            Object r = addAssetPath.invoke(m, path);
            if (r instanceof Integer && ((Integer) r) != 0) {
                am = m;
            }
        } catch (Throwable ignored) {
            // 隐藏 API 被拦（新版安卓会更严）：不折腾，直接走 zip 直读
        }
    }

    /** 逐级向上找同名字段（pathList / dexElements / path 都在不同层级的实现类上） */
    private static Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static void close(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void close(ZipFile z) {
        try {
            if (z != null) z.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private Assets() {}
}
