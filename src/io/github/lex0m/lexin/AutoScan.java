package io.github.lex0m.lexin;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 运行时自适配：直接读宿主 APK 的 dex 字符串表，按槽位规则找候选类名。
 *
 * 为什么不用 ClassLoader 遍历：微信 7.8 万个类，逐个 loadClass 会触发静态初始化，
 * 副作用不可控。这里只读 dex 的字符串（类名在 dex 里本来就是明文），
 * 再用 Class.forName(name,false,loader) 做「不初始化」的指纹校验 —— 安全且够快。
 *
 * 一次扫描结果写入本机缓存（Anchors.learn），下次启动零成本；
 * 换微信版本时缓存 key 变化，自动重新扫。
 */
final class AutoScan {

    private static final String T = "svc-scan: ";

    private static final Map<String, List<String>> found = new ConcurrentHashMap<>();
    private static final Set<String> unresolved = ConcurrentHashMap.newKeySet();
    private static volatile boolean running = false;
    private static volatile boolean finished = false;
    private static volatile long startedAt = 0L;
    private static volatile Runnable onFinished;

    /** 广播槽位没解析出来 —— 由 Anchors 在解析失败时调用。 */
    static void markUnresolved(String slot) {
        if (!finished) unresolved.add(slot);
    }

    /** 已经扫到的候选（没扫完就是空表）。 */
    static List<String> candidates(String slot) {
        List<String> l = found.get(slot);
        if (l != null) return l;
        return Collections.emptyList();
    }

    /** 有没有槽位还没解析出来 —— 决定要不要开扫描线程。 */
    static boolean needed() {
        return !unresolved.isEmpty() && !running && !finished;
    }

    /** 全自动适配的开关：有没解析出来的槽位就后台扫一遍。 */
    static void schedule() {
        if (finished || running || unresolved.isEmpty()) return;
        if (Features.app == null) return;
        synchronized (AutoScan.class) {
            if (finished || running) return;
            running = true;
            startedAt = System.currentTimeMillis();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    scan();
                } catch (Throwable e) {
                    Log.d(T + "aborted: " + e.getClass().getSimpleName());
                } finally {
                    running = false;
                    finished = true;
                    Log.d(T + "done in " + (System.currentTimeMillis() - startedAt) + "ms");
                    Runnable cb = onFinished;
                    if (cb != null) {
                        try {
                            cb.run();
                        } catch (Throwable ignored) {
                            // 回调失败不影响扫描结果
                        }
                    }
                }
            }
        }, "s" + Integer.toHexString((int) (System.nanoTime() & 0xFFF)));
        t.setDaemon(true);
        t.start();
    }

    /** 扫描完成后回调（回到主线重试那些没装上的功能）。 */
    static void whenFinished(Runnable r) {
        if (finished) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // 忽略
            }
            return;
        }
        onFinished = r;
    }

    static boolean isRunning() {
        return running;
    }

    /** 面板「重新适配」：清掉学习结果与本次扫描状态。 */
    static void reset() {
        synchronized (AutoScan.class) {
            found.clear();
            unresolved.clear();
            finished = false;
            onFinished = null;
        }
    }

    // ── 扫描本体 ─────────────────────────────────────────────────────────────

    private static void scan() throws Exception {
        String apk = Features.app.getApplicationInfo().sourceDir;
        Set<String> want = new HashSet<>(unresolved);
        if (want.isEmpty()) return;

        // 收集每个槽位的字节前缀（有稳定包名的槽位直接按前缀抠字符串）
        List<byte[]> prefixes = new ArrayList<>();
        List<String> prefixSlot = new ArrayList<>();
        List<String> broad = new ArrayList<>();
        for (String slot : want) {
            JSONObject r = Anchors.rule(slot);
            if (r != null && !r.optBoolean("scan", true)) continue;   // 签名可能撞车，只认版本表
            String pkg = r == null ? "" : r.optString("pkg", "");
            boolean fingerprinted = r != null
                    && (!r.optString("method", "").isEmpty()
                    || !r.optString("fields", "").isEmpty());
            if (!pkg.isEmpty()) {
                prefixes.add(("L" + pkg.replace('.', '/') + "/").getBytes());
                prefixSlot.add(slot);
            } else if (fingerprinted) {
                broad.add(slot);
            }
        }
        if (prefixes.isEmpty() && broad.isEmpty()) {
            Log.d(T + "nothing scannable");
            return;
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            Collections.sort(entries, new java.util.Comparator<ZipEntry>() {
                @Override
                public int compare(ZipEntry a, ZipEntry b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            int dexCount = 0;
            for (ZipEntry e : entries) {
                String n = e.getName();
                if (!n.startsWith("classes") || !n.endsWith(".dex")) continue;
                dexCount++;
                byte[] data = read(zip, e);
                if (data == null) continue;
                for (int i = 0; i < prefixes.size(); i++) {
                    String slot = prefixSlot.get(i);
                    if (found.containsKey(slot)) continue;
                    List<String> names = grab(data, prefixes.get(i), 24);
                    if (names.isEmpty()) continue;
                    pick(slot, names);
                }
                if (!broad.isEmpty()) {
                    List<String> loose = grabLoose(data, 4000);
                    for (String slot : broad) {
                        if (found.containsKey(slot)) continue;
                        pick(slot, loose);
                    }
                }
                // 都解决了就早点收工
                boolean allDone = true;
                for (String slot : want) {
                    if (!found.containsKey(slot)) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone) break;
                data = null;
            }
            Log.d(T + "dex=" + dexCount + " hits=" + found.size());
        } finally {
            close(zip);
        }
    }

    /** 候选里挑第一个通过指纹校验的，并写入学习缓存。 */
    private static void pick(String slot, List<String> names) {
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, false, Features.loader);
                if (c == null) continue;
                if (!Anchors.fingerprintOk(slot, c)) continue;
                List<String> hit = new ArrayList<>(1);
                hit.add(n);
                found.put(slot, hit);
                Anchors.learn(slot, n);
                Log.d(T + "learned " + slot + " -> " + n);
                return;
            } catch (Throwable ignored) {
                // 下一个候选
            }
        }
    }

    /** 抠出匹配前缀的类描述符（去 L/;，点号分隔），去重、限量。 */
    private static List<String> grab(byte[] data, byte[] prefix, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int from = 0;
        while (out.size() < limit) {
            int at = indexOf(data, prefix, from);
            if (at < 0) break;
            from = at + 1;
            String raw = descriptorAt(data, at);
            if (raw == null || !seen.add(raw)) continue;
            out.add(raw);
        }
        return out;
    }

    /** 无包名锚点用的宽松扫描：只要形如 Lxx/yy; 的短名（微信的混淆类就长这样）。 */
    private static List<String> grabLoose(byte[] data, int limit) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < data.length - 4 && out.size() < limit; i++) {
            if (data[i] != 'L') continue;
            byte b1 = data[i + 1];
            if (b1 < 'a' || b1 > 'z') continue;
            String raw = descriptorAt(data, i);
            if (raw == null) continue;
            String[] seg = raw.split("\\.");
            if (seg.length != 2) continue;
            if (seg[0].length() < 2 || seg[0].length() > 4) continue;
            if (seg[1].length() < 1 || seg[1].length() > 4) continue;
            if (!seg[0].matches("[a-z0-9]+") || !seg[1].matches("[a-z0-9_]+")) continue;
            if (seen.add(raw)) out.add(raw);
            i += raw.length();          // 跳过这一个，别在同一个串里反复匹配
        }
        return out;
    }

    /**
     * 从 at 处的 'L' 读一个类描述符，返回 **Class.forName 认的类名**（点号分隔、去掉开头的 L）。
     * 早期版本直接返回了 dex 里的 `Lcom/foo/bar;` 原样字符串，导致候选类名全部 ClassNotFound，
     * 第四级"运行时扫描"实际上从来没生效过。
     */
    private static String descriptorAt(byte[] data, int at) {
        StringBuilder sb = new StringBuilder(40);
        for (int i = at + 1, n = 0; i < data.length && n < 160; i++, n++) {
            byte b = data[i];
            if (b == ';') {
                return sb.length() > 2 ? sb.toString() : null;
            }
            if (b == '/') {
                sb.append('.');
                continue;
            }
            if (b == '$' || b == '_' || b == '.' || b == '-') {
                sb.append((char) b);
                continue;
            }
            if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')) {
                sb.append((char) b);
                continue;
            }
            return null;
        }
        return null;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] read(ZipFile zip, ZipEntry e) {
        InputStream in = null;
        try {
            long size = e.getSize();
            if (size <= 0 || size > 64L * 1024 * 1024) return null;
            in = zip.getInputStream(e);
            byte[] buf = new byte[(int) size];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r <= 0) break;
                off += r;
            }
            return off == buf.length ? buf : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static void close(ZipFile z) {
        try {
            if (z != null) z.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private AutoScan() {}
}
