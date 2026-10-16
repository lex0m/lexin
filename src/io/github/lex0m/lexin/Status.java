package io.github.lex0m.lexin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 功能挂载状态。
 *
 * 全自动适配的代价是「某些功能在当前微信版本上可能装不上」—— 那也必须是**看得见**的：
 * 每个功能装完记一笔，设置面板里逐条显示「已生效 / 未适配（当前微信版本）」
 * 和锚点解析明细，用户不用去猜开关为什么没反应。
 */
final class Status {

    private static final Map<String, Boolean> ok = new ConcurrentHashMap<>();
    private static final Map<String, String> note = new ConcurrentHashMap<>();

    static void set(String feature, boolean armed) {
        ok.put(feature, armed);
    }

    static void note(String feature, String text) {
        note.put(feature, text);
    }

    static boolean ok(String feature) {
        Boolean b = ok.get(feature);
        return b != null && b;
    }

    static String note(String feature) {
        return note.get(feature);
    }

    /** 面板用：功能 -> "已生效 / 未适配（当前微信版本）"。 */
    /** 一行摘要：适配 9/9（给"加载埋点"和面板共用）。 */
    static String summary() {
        int ok = 0;
        int total = 0;
        for (Map.Entry<String, Boolean> e : all().entrySet()) {
            total++;
            if (e.getValue()) ok++;
        }
        return "适配 " + ok + "/" + total;
    }

    static Map<String, Boolean> all() {
        return new LinkedHashMap<>(ok);
    }

    static int armed() {
        int n = 0;
        for (Boolean b : ok.values()) if (b != null && b) n++;
        return n;
    }

    private Status() {}
}
