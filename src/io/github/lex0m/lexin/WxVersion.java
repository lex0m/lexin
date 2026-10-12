package io.github.lex0m.lexin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/**
 * 宿主（微信）版本。
 *
 * 微信每次升级都会重新混淆类名，所以「某个功能在当前版本能不能用」这件事必须先问版本：
 * 版本表（assets/anchors.json）按 versionName 建索引，拿不到就退回邻近版本，再不行就运行时扫描。
 */
final class WxVersion {

    private static volatile boolean ready = false;
    private static volatile String name = "";
    private static volatile int code = 0;
    private static volatile int[] parts = {0, 0, 0};

    static synchronized void init(Context ctx) {
        if (ready) return;
        ready = true;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(Entry.HOST, 0);
            name = pi.versionName == null ? "" : pi.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            parts = parse(name);
        } catch (Throwable ignored) {
            // 版本探测尽力而为，拿不到就按「未知版本」处理
        }
    }

    /** "8.0.78" -> {8,0,78}；解析不出来返回 {0,0,0}。 */
    static int[] parse(String v) {
        int[] out = {0, 0, 0};
        if (v == null) return out;
        try {
            String[] seg = v.trim().split("\\.");
            for (int i = 0; i < 3 && i < seg.length; i++) {
                StringBuilder digits = new StringBuilder();
                for (int j = 0; j < seg[i].length(); j++) {
                    char c = seg[i].charAt(j);
                    if (c >= '0' && c <= '9') digits.append(c);
                    else break;
                }
                if (digits.length() > 0) out[i] = Integer.parseInt(digits.toString());
            }
        } catch (Throwable ignored) {
            // 保持 0
        }
        return out;
    }

    static String name() {
        return name;
    }

    static int code() {
        return code;
    }

    /** 主版本号，用于「8.0.x」这类粗档位判断。 */
    static int major() {
        return parts[0];
    }

    static int minor() {
        return parts[1];
    }

    static int patch() {
        return parts[2];
    }

    /** a.b.c >= 给定值。 */
    static boolean atLeast(int a, int b, int c) {
        int[] p = parts;
        if (p[0] != a) return p[0] > a;
        if (p[1] != b) return p[1] > b;
        return p[2] >= c;
    }

    /** 面板显示用："8.0.78 (3180)" 或 "未知版本"。 */
    static String label() {
        if (name == null || name.isEmpty()) return "未知版本";
        return code > 0 ? name + " (" + code + ")" : name;
    }

    private WxVersion() {}
}
