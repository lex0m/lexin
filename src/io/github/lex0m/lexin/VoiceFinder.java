package io.github.lex0m.lexin;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 按文件系统定位微信语音文件（v1.7）。
 *
 * 为什么要这个：8.0.80 上老的取路径办法已经死了 ——
 *   · 锚点槽位 `voice_path` 指向 `mc0.b`，而实测这个类**只剩一个构造函数**，
 *     `Uj(f9,talker,boolean)→String` 早就没了 ⇒ `voicePathSilently()` 永远返回 null
 *     ⇒ 用户点导出只会看到「没找到语音文件」。
 *   · 实测目录结构是 **两级十六进制分片**：`MicroMsg/<账户>/voice2/<XX>/<YY>/msg_<hash>.amr`
 *     （共 23658 个文件）。以前那种"拿 imgPath 平铺拼一个路径"的写法必然失败。
 *
 * 做法（两级匹配，都不依赖微信内部类）：
 *   ① 名字匹配：消息的 `field_imgPath` 可能带 `msg_` 前缀，扫一遍找同名文件；
 *   ② 时间匹配：用消息的 `field_createTime`（秒）去比文件的 lastModified，取最接近且 ≤8 秒的。
 * 遍历结果做进程内缓存（3 分钟），避免连点几次都全盘扫一遍。
 */
final class VoiceFinder {

    private static final String T = "svc-vfind: ";
    /** 缓存有效期：微信语音文件落了就不会变，3 分钟足够。 */
    private static final long CACHE_MS = 180_000L;
    /** 时间匹配允许的最大偏差（毫秒）。语音落盘通常就在消息创建后 1 秒内。 */
    private static final long MAX_SKEW = 8_000L;

    private static volatile List<File> cache;
    private static volatile long cacheAt;

    /** 微信 MicroMsg 根目录（微信进程里 getFilesDir() 的父目录就是它）。 */
    static File microMsgRoot(Context ctx) {
        try {
            File files = ctx.getFilesDir();
            if (files == null) return null;
            File parent = files.getParentFile();
            if (parent == null) return null;
            File mm = new File(parent, "MicroMsg");
            return mm.isDirectory() ? mm : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找一条语音文件。
     *
     * @param imgPath    消息的 field_imgPath（可能是 msg_xxx，也可能只是 hash，可为空）
     * @param createTime 消息创建时间（毫秒；传 0 表示不知道）
     * @return 命中的文件，找不到返回 null
     */
    static File find(Context ctx, String imgPath, long createTime) {
        File root = microMsgRoot(ctx);
        if (root == null) return null;

        List<File> all = list(root);
        if (all.isEmpty()) return null;

        // ① 名字匹配：imgPath 与文件名互相包含即算命中
        if (imgPath != null && !imgPath.isEmpty()) {
            String want = imgPath.endsWith(".amr") || imgPath.endsWith(".silk")
                    ? imgPath.substring(0, imgPath.lastIndexOf('.')) : imgPath;
            for (File f : all) {
                String name = f.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                if (base.equals(want) || base.contains(want) || want.contains(base)) {
                    Log.d(T + "hit by name: " + name);
                    return f;
                }
            }
        }

        // ② 时间匹配：取 lastModified 最接近且不超过 MAX_SKEW 的
        if (createTime > 0) {
            File best = null;
            long bestGap = Long.MAX_VALUE;
            for (File f : all) {
                long gap = Math.abs(f.lastModified() - createTime);
                if (gap < bestGap && gap <= MAX_SKEW) {
                    bestGap = gap;
                    best = f;
                }
            }
            if (best != null) {
                Log.d(T + "hit by time: " + best.getName() + " gap=" + bestGap + "ms");
                return best;
            }
        }
        return null;
    }

    /** 列 voice2 下所有语音文件（带 3 分钟缓存）。 */
    private static List<File> list(File microMsgRoot) {
        List<File> c = cache;
        if (c != null && System.currentTimeMillis() - cacheAt < CACHE_MS) return c;

        List<File> out = new ArrayList<>();
        try {
            File[] accounts = microMsgRoot.listFiles();
            if (accounts == null) return out;
            for (File acc : accounts) {
                if (!acc.isDirectory()) continue;
                for (String v : new String[]{"voice2", "voice3"}) {
                    File voice = new File(acc, v);
                    if (!voice.isDirectory()) continue;
                    File[] lv1 = voice.listFiles();
                    if (lv1 == null) continue;
                    for (File d1 : lv1) {
                        if (!d1.isDirectory()) {
                            // 老版本可能平铺，也收进来
                            if (d1.isFile() && d1.length() > 0) out.add(d1);
                            continue;
                        }
                        File[] lv2 = d1.listFiles();
                        if (lv2 == null) continue;
                        for (File d2 : lv2) {
                            if (d2.isFile()) {
                                if (d2.length() > 0) out.add(d2);
                                continue;
                            }
                            File[] files = d2.listFiles();
                            if (files == null) continue;
                            for (File f : files) {
                                if (f.isFile() && f.length() > 0) out.add(f);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(T + "list failed: " + t.getClass().getSimpleName());
        }
        cache = out;
        cacheAt = System.currentTimeMillis();
        Log.d(T + "indexed " + out.size() + " voice files");
        return out;
    }

    /** 换版本/重扫时清掉索引。 */
    static void invalidate() {
        cache = null;
        cacheAt = 0;
    }

    private VoiceFinder() {}
}
