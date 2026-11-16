package io.github.lex0m.lexin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 功能挂载状态（v1.6.0：从「装上了」升级为「装上了 + 被调用了」）。
 *
 * 三种状态，面板必须区分开：
 *   NONE  = 锚点没解析到 / hook 一个都没挂上（当前微信版本未适配）
 *   ARMED = hook 挂上了，但运行期一次都没被调用（**8.0.80 上绝大多数「假成功」就是这一档**）
 *   HIT   = 真被调用过（附命中次数与最后命中时间）
 *
 * 快照格式（写 status.txt / 推给面板的那份）：
 *   #lexin|v=1.6.0|wx=8.0.80|code=3220|pid=123|ts=1763...
 *   消息防撤回=2|armed=2|hits=37|last=1763...
 *   去广告=1|armed=3|hits=0
 *
 * 头部是必须的：面板在另一个进程，读到的只能是快照；没有版本/时间戳就分不清
 * 「本次真的成功」和「上次会话留下的绿灯」——这正是 v1.5 之前最大的误判来源。
 */
final class Status {

    static final int NONE = 0;
    static final int ARMED = 1;
    static final int HIT = 2;

    /** 快照字符串头部前缀，面板据此判断这是不是我们能解析的格式。 */
    static final String HEAD = "#lexin|";

    /** 快照生成时间（本进程最后一次渲染）。 */
    private static volatile long stamp;

    /** 旧接口：armed=true 记「挂上了」，false 记「没挂上」。命中数由 {@link Probe#hit} 单独累加。 */
    static void set(String feature, boolean armed) {
        Probe.armed(feature, armed ? Math.max(1, Probe.armed(feature)) : 0);
        stamp = System.currentTimeMillis();
    }

    /** 新接口：明确告诉探针挂上了几个 hook（0 = 没挂上）。 */
    static void armed(String feature, int n) {
        Probe.armed(feature, n);
        stamp = System.currentTimeMillis();
    }

    /** 命中一次。功能代码在自己的回调里调用，热路径。 */
    static void hit(String feature) {
        Probe.hit(feature);
        stamp = System.currentTimeMillis();
    }

    /**
     * 备注更新时置脏位：定时推送以前只在「命中数变化」时发，导致
     * 「为什么没命中」这类诊断信息永远传不出微信进程（note 改了也不推）。
     * 现在 note 一变，下一次定时窗口就会推一份出来。
     */
    private static volatile boolean noteDirty = false;

    static void note(String feature, String text) {
        Probe.note(feature, text);
        noteDirty = true;
        // 有新的诊断信息就尽快推一份出去（节流在 Features 里），
        // 不等那个 5 分钟的定时窗口 —— 微信一旦切后台就冻结，窗口永远等不到。
        Features.pushSoon();
    }

    /** 取并清除脏位（定时推送用）。 */
    static boolean consumeNoteDirty() {
        if (!noteDirty) return false;
        noteDirty = false;
        return true;
    }

    static String note(String feature) {
        return Probe.row(feature).note;
    }

    /** 该功能是否「装上了」（不看命中）—— 补装逻辑用它。 */
    static boolean ok(String feature) {
        return Probe.row(feature).armed > 0;
    }

    /** 该功能是否「真的在用」（有命中）—— 面板与诊断用它。 */
    static boolean live(String feature) {
        return Probe.live(feature);
    }

    static int state(String feature) {
        return Probe.row(feature).state();
    }

    static long stamp() {
        return stamp;
    }

    /** 旧接口：面板/日志用的布尔表（armed 即 true，保持调用方零改动）。 */
    static Map<String, Boolean> all() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, Probe.Row> e : Probe.all().entrySet()) {
            out.put(e.getKey(), e.getValue().armed > 0);
        }
        return out;
    }

    static int armed() {
        int n = 0;
        for (Probe.Row r : Probe.all().values()) if (r.armed > 0) n++;
        return n;
    }

    /** 一行摘要：适配 9/9 · 命中 37（给"加载埋点"和面板共用）。 */
    static String summary() {
        int okCount = 0;
        int total = 0;
        for (Probe.Row r : Probe.all().values()) {
            total++;
            if (r.armed > 0) okCount++;
        }
        return "适配 " + okCount + "/" + total + " · 命中 " + Probe.totalHits();
    }

    /**
     * 渲染成快照文本（微信进程 → 模块侧的 status.txt）。
     * 头部字段全部是运行期值，不含路径、不含类名。
     */
    static String render() {
        long now = System.currentTimeMillis();
        stamp = now;
        StringBuilder sb = new StringBuilder(512);
        sb.append(HEAD)
                .append("v=").append(Features.MODULE_VERSION)
                .append("|wx=").append(WxVersion.name())
                .append("|code=").append(WxVersion.code())
                .append("|pid=").append(android.os.Process.myPid())
                .append("|ts=").append(now)
                .append('\n');
        for (Probe.Row r : Probe.all().values()) {
            sb.append(r.name).append('=').append(r.state())
                    .append("|armed=").append(r.armed)
                    .append("|hits=").append(r.hits);
            if (r.last > 0) sb.append("|last=").append(r.last);
            if (r.note != null && !r.note.isEmpty()) sb.append("|note=").append(sanitize(r.note));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** note 里可能带类名/异常信息，进快照前把换行和竖线去掉，避免破格式。 */
    private static String sanitize(String s) {
        return s.replace('\n', ' ').replace('|', '/').replace('=', ':');
    }

    /** 面板侧解析出来的一行。 */
    static final class S {
        int state;
        int armed;
        int hits;
        long last;
        String note;
    }

    /** 面板侧：解析快照文本。头部单独用 {@link #headField} 取。 */
    static Map<String, S> parse(String text) {
        Map<String, S> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq);
            S s = new S();
            for (String part : line.substring(eq + 1).split("\\|")) {
                int p = part.indexOf('=');
                if (p < 0) {
                    // 行首那个裸数字就是 state："消息防撤回=2|armed=..."
                    try {
                        s.state = Integer.parseInt(part.trim());
                    } catch (Throwable ignored) {
                        // 坏字段跳过
                    }
                    continue;
                }
                String k = part.substring(0, p);
                String v = part.substring(p + 1);
                try {
                    if ("armed".equals(k)) s.armed = Integer.parseInt(v);
                    else if ("hits".equals(k)) s.hits = Integer.parseInt(v);
                    else if ("last".equals(k)) s.last = Long.parseLong(v);
                    else if ("note".equals(k)) s.note = v;
                } catch (Throwable ignored) {
                    // 单字段坏掉不影响整行
                }
            }
            if (s.state == NONE && s.armed > 0) s.state = ARMED;
            if (s.hits > 0) s.state = HIT;
            out.put(name, s);
        }
        return out;
    }

    /** 面板侧：取头部某个字段（v / wx / code / pid / ts）。 */
    static String headField(String text, String key) {
        if (text == null) return null;
        int eol = text.indexOf('\n');
        String head = eol < 0 ? text : text.substring(0, eol);
        if (!head.startsWith(HEAD)) return null;
        for (String part : head.substring(HEAD.length()).split("\\|")) {
            int p = part.indexOf('=');
            if (p > 0 && part.substring(0, p).equals(key)) return part.substring(p + 1);
        }
        return null;
    }

    private Status() {}
}
