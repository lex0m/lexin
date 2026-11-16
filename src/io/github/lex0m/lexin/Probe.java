package io.github.lex0m.lexin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命中探针（v1.6.0）。
 *
 * 为什么要有它：以前面板的「已生效」只等于「hook 装上了」。8.0.80 上这两件事分家了 ——
 * 类还在、方法名还在、hook 挂得上，但微信根本不走那条路（方法搬迁 / 链路改道 / 类被热修替换），
 * 于是面板全绿、功能全无。
 *
 * 这里把每个功能拆成三个数：
 *   armed = 成功挂上去的 hook 数（安装期，一次性）
 *   hits  = 回调被真正调用的次数（运行期，每次命中 +1）
 *   last  = 最后一次命中的时间（用来判断「昨天命中过、今天没有」）
 *
 * 面板据此显示「已挂载（未命中）」/「已命中 N 次」，而不是笼统的「已生效」。
 * 计数只在内存，跨进程靠 status 快照带出去 —— 所以快照必须带时间戳（见 Status.render）。
 */
final class Probe {

    /** 单个功能的一行计数。字段全是 volatile：安装线程写、hook 回调线程写、推送线程读。 */
    static final class Row {
        final String name;
        volatile int armed;
        volatile int hits;
        volatile long first;
        volatile long last;
        volatile String note;

        Row(String name) {
            this.name = name;
        }

        int state() {
            if (hits > 0) return Status.HIT;
            if (armed > 0) return Status.ARMED;
            return Status.NONE;
        }
    }

    private static final Map<String, Row> rows = new ConcurrentHashMap<>();

    static Row row(String feature) {
        if (feature == null) feature = "?";
        Row r = rows.get(feature);
        if (r != null) return r;
        synchronized (Probe.class) {
            r = rows.get(feature);
            if (r == null) {
                r = new Row(feature);
                rows.put(feature, r);
            }
            return r;
        }
    }

    /** 安装期：这个功能挂上了 n 个 hook（n=0 表示没挂上）。 */
    static void armed(String feature, int n) {
        Row r = row(feature);
        r.armed = Math.max(0, n);
    }

    /** 运行期：命中一次。热路径，只做自增与两个时间戳赋值。 */
    static void hit(String feature) {
        Row r = row(feature);
        long now = System.currentTimeMillis();
        // 诊断包才输出（正式包 Log.d 是空实现），用于在真机上确认"哪些 hook 真的被调用"
        Log.d("svc-hit: " + feature);
        if (r.first == 0L) r.first = now;
        r.last = now;
        r.hits++;
        // 命中是"这个功能真的在工作"的唯一证据，尽快推出去
        Features.pushSoon();
    }

    static void note(String feature, String text) {
        row(feature).note = text;
    }

    static int armed(String feature) {
        return row(feature).armed;
    }

    static int hits(String feature) {
        return row(feature).hits;
    }

    static boolean live(String feature) {
        return row(feature).hits > 0;
    }

    static Map<String, Row> all() {
        return new LinkedHashMap<>(rows);
    }

    /** 诊断用：全局命中总数（判断「整个模块是否还有 hook 活着」）。 */
    static int totalHits() {
        int n = 0;
        for (Row r : rows.values()) n += r.hits;
        return n;
    }

    /**
     * 活性自检：模块自己 hook 自己一个静态方法，然后定时调用。
     *
     * 这个方法被调用说明「框架的 hook 机制整体还在」；如果它停了而微信还活着，
     * 说明 hook 被整体还原（热修替换 / 反注入卸载）—— 这是 M6 的唯一运行时判据。
     */
    static volatile int beat = 0;

    static void pulse() {
        beat++;
    }

    private Probe() {}
}
