package io.github.lex0m.lexin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 锚点解析：把「功能需要的类」和「某个微信版本里那个类叫什么」解耦。
 *
 * 解析顺序（逐级回退，全部带指纹校验——宁可少一个功能，也不挂错类）：
 *   1) 本版本表        assets/anchors.json 里 versionName 精确命中
 *   2) 邻近版本表      同 major.minor 里离得最近的版本
 *   3) 本机学习缓存    上次运行时扫描出来的结果（按 versionCode 存，换版本自动作废）
 *   4) 运行时扫描      AutoScan 直接读宿主 APK 的 dex，按槽位规则找候选
 *   5) 全都不行        返回 null，调用方静默跳过该功能，不影响其它功能
 *
 * 表里的规则（slots）由 tools/gen-anchors.py 生成，Java 和 Python 共用同一套定义，
 * 不会出现两边不一致。
 */
final class Anchors {

    private static final String T = "svc-anchor: ";

    // ── 槽位 ────────────────────────────────────────────────────────────────

    /** 系统消息解析器 d(String,String,String)->Map：撤回通知的必经之路。 */
    static final String SYSMSG = "sysmsg_parser";
    /** 消息实体：带 field_msgSvrId / field_talker / field_isSend。 */
    static final String MSG = "msg";
    /** 聊天行绑定器（版本表命中时用它做撤回标记，更省事）。 */
    static final String CHAT_BINDER = "chat_binder";
    /** 聊天列表适配器：类名长期不变，是行标记的兜底路径。 */
    static final String CHAT_ADAPTER = "chat_adapter";
    /** 随机数入口 Q(int,int)->int（骰子/猜拳改结果）。 */
    static final String RNG = "rng";
    /** 表情面板发送入口 a(View,Context,int,回调)。 */
    static final String EMOJI_PANEL = "emoji_panel";
    /** 图片浏览页。 */
    static final String GALLERY = "gallery";
    /** 关怀模式介绍页（隐蔽入口）。 */
    static final String CARE_MODE = "care_mode";
    /** 朋友圈存储层：SQL 执行口（按 SQL 文本判删除，不误伤读）。 */
    static final String SNS_DB = "sns_db";
    static final String SNS_DB2 = "sns_db2";
    /** 开屏/小程序广告判定。 */
    static final String AD_APPBRAND = "ad_appbrand";
    static final String AD_OPEN = "ad_open";
    /** 朋友圈广告：带广告常量 + (String) 构造器的那个类（8.0.78 是 plugin.sns.storage.ADInfo）。 */
    static final String SNS_AD = "sns_ad";
    /** 扫码结果处理：方法里带 MicroMsg.QBarStringHandler 日志 tag 的那个类（8.0.78 是 v74.v）。 */
    static final String QR_SCAN = "qr_scan";
    /** 微信运动：SportUtil（各家手机步数来源不同，微信自己适配过）。 */
    static final String SPORT_UTIL = "sport_util";
    /** 微信运动：自己听计步器的那条路。 */
    static final String SPORT_DETECTOR = "sport_detector";
    /** 微信运动：推送链路听计步器的那条路。 */
    static final String SPORT_PUSH_DETECTOR = "sport_push_detector";

    private static final String[] ALL_SLOTS = {
            SYSMSG, MSG, CHAT_BINDER, CHAT_ADAPTER, RNG, EMOJI_PANEL,
            GALLERY, CARE_MODE, SNS_DB, SNS_DB2, AD_APPBRAND, AD_OPEN, SNS_AD, QR_SCAN,
            SPORT_UTIL, SPORT_DETECTOR, SPORT_PUSH_DETECTOR,
    };

    /** 功能 -> 它需要的槽位（面板按功能报适配状态，比按槽位报更好懂）。 */
    private static final String[][] FEATURES = {
            {"消息防撤回", SYSMSG, MSG},
            {"撤回标记", CHAT_BINDER, CHAT_ADAPTER},
            {"骰子/猜拳", EMOJI_PANEL, RNG},
            {"自动查看原图", GALLERY},
            {"朋友圈保留", SNS_DB, SNS_DB2},
            // 开屏 / 小程序 / 朋友圈 三个广告拦截点同属「去广告」这一个开关，
            // 面板里合成一行，别让用户以为还有个独立的开关没找到。
            {"去广告", AD_APPBRAND, AD_OPEN, SNS_AD},
            {"隐蔽入口", CARE_MODE},
            {"解除扫码限制", QR_SCAN},
            {"运动步数", SPORT_UTIL, SPORT_DETECTOR, SPORT_PUSH_DETECTOR},
    };

    // ── 状态 ────────────────────────────────────────────────────────────────

    private static final Object LOCK = new Object();
    private static final Map<String, List<Class<?>>> cache = new ConcurrentHashMap<>();
    private static final Map<String, String> source = new ConcurrentHashMap<>();

    private static volatile JSONObject rules;
    private static volatile JSONObject versions;
    private static volatile JSONObject learned;
    private static volatile boolean loaded = false;
    private static volatile boolean tableOk = false;

    static void init() {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            try {
                String text = Assets.text("anchors.json");
                if (text != null) {
                    JSONObject root = new JSONObject(text);
                    rules = root.optJSONObject("slots");
                    versions = root.optJSONObject("versions");
                    tableOk = rules != null && versions != null;
                }
            } catch (Throwable t) {
                Log.d(T + "table broken: " + t.getClass().getSimpleName());
            }
            learned = readLearned();
            Log.d(T + "table=" + tableOk + " versions=" + knownVersions());
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /** 槽位的第一个可用类；解析不到返回 null。 */
    static Class<?> cls(String slot) {
        List<Class<?>> l = all(slot);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 槽位对应的全部类（sns_db 这类一个版本里可能有多个库对象）。 */
    static List<Class<?>> all(String slot) {
        List<Class<?>> c = cache.get(slot);
        if (c != null) return c;
        synchronized (LOCK) {
            c = cache.get(slot);
            if (c != null) return c;
            c = resolve(slot);
            cache.put(slot, c);
            if (c.isEmpty()) {
                AutoScan.markUnresolved(slot);
                Log.d(T + "unresolved " + slot + " on " + WxVersion.name());
            }
            return c;
        }
    }

    static boolean ok(String slot) {
        return !all(slot).isEmpty();
    }

    static String name(String slot) {
        Class<?> c = cls(slot);
        return c == null ? null : c.getName();
    }

    private static List<Class<?>> resolve(String slot) {
        init();
        List<Class<?>> out = verifyAll(slot, fromVersion(slot, WxVersion.name()), "table");
        if (!out.isEmpty()) return out;
        for (String v : nearby(WxVersion.name())) {
            out = verifyAll(slot, fromVersion(slot, v), v);
            if (!out.isEmpty()) return out;
        }
        out = verifyAll(slot, learnedName(slot), "learned");
        if (!out.isEmpty()) return out;
        out = verifyAll(slot, AutoScan.candidates(slot), "scan");
        return out;
    }

    private static List<String> fromVersion(String slot, String version) {
        List<String> out = new ArrayList<>();
        if (version == null || version.isEmpty() || versions == null) return out;
        try {
            JSONObject v = versions.optJSONObject(version);
            JSONObject s = v == null ? null : v.optJSONObject("slots");
            if (s == null) return out;
            Object val = s.opt(slot);
            if (val instanceof String) {
                out.add((String) val);
            } else if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, null);
                    if (n != null) out.add(n);
                }
            }
        } catch (Throwable ignored) {
            // 表坏了就当没有
        }
        return out;
    }

    private static List<String> learnedName(String slot) {
        return fromJson(learned, slot);
    }

    private static List<String> fromJson(JSONObject o, String slot) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        try {
            Object val = o.opt(slot);
            if (val instanceof String) {
                out.add((String) val);
            } else if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, null);
                    if (n != null) out.add(n);
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 版本表回退顺序：先比当前低的最接近版本，再比当前高的最接近版本。 */
    private static List<String> nearby(String v) {
        List<String> out = new ArrayList<>();
        if (versions == null || v == null || v.isEmpty()) return out;
        try {
            final int[] me = WxVersion.parse(v);
            List<String> keys = new ArrayList<>();
            Iterator<String> it = versions.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys, new java.util.Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return rank(a, me) - rank(b, me);
                }

                private int rank(String k, int[] me) {
                    int[] p = WxVersion.parse(k);
                    if (p[0] != me[0] || p[1] != me[1]) return 900;
                    int d = p[2] - me[2];
                    return d == 0 ? 899 : (d < 0 ? -d : 100 + d);
                }
            });
            for (String k : keys) {
                if (!k.equals(v) && rank2(k, me) < 900) out.add(k);
            }
        } catch (Throwable ignored) {
            // 回退顺序算不出来就只用精确表
        }
        return out;
    }

    private static int rank2(String k, int[] me) {
        int[] p = WxVersion.parse(k);
        if (p[0] != me[0] || p[1] != me[1]) return 900;
        int d = p[2] - me[2];
        return d == 0 ? 899 : (d < 0 ? -d : 100 + d);
    }

    // ── 校验 ────────────────────────────────────────────────────────────────

    private static List<Class<?>> verifyAll(String slot, List<String> names, String from) {
        List<Class<?>> out = new ArrayList<>();
        if (names == null || names.isEmpty()) return out;
        for (String n : names) {
            Class<?> c = verify(slot, n, from);
            if (c != null && !out.contains(c)) out.add(c);
        }
        return out;
    }

    private static Class<?> verify(String slot, String className, String from) {
        if (className == null || className.isEmpty()) return null;
        try {
            Class<?> c = Class.forName(className, false, Features.loader);
            if (c == null) return null;
            if (!fingerprintOk(slot, c)) {
                Log.d(T + "fingerprint mismatch " + slot + " -> " + className);
                return null;
            }
            source.put(slot, from);
            Log.d(T + "resolved " + slot + " -> " + className + " (" + from + ")");
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 槽位指纹：规则里写什么查什么，规则没写就只要类存在。 */
    static boolean fingerprintOk(String slot, Class<?> c) {
        init();
        if (rules == null) return true;
        try {
            JSONObject r = rules.optJSONObject(slot);
            if (r == null) return true;

            String exact = r.optString("exact", "");
            if (!exact.isEmpty()) return exact.equals(c.getName());

            String pkg = r.optString("pkg", "");
            if (!pkg.isEmpty() && !c.getName().startsWith(pkg)) return false;

            String simple = r.optString("simple", "");
            if (!simple.isEmpty()) {
                String cn = c.getName();
                String tail = cn.substring(cn.lastIndexOf('.') + 1);
                if (!tail.matches(simple)) return false;
            }

            String fields = r.optString("fields", "");
            if (!fields.isEmpty()) {
                for (String f : fields.split(",")) {
                    if (fieldDeep(c, f.trim()) == null) return false;
                }
            }

            String fp = r.optString("method", "");
            if (!fp.isEmpty() && !hasMethod(c, fp)) return false;
            String cfp = r.optString("ctor", "");
            if (!cfp.isEmpty() && !hasCtor(c, cfp)) return false;
            String fp2 = r.optString("method2", "");
            if (!fp2.isEmpty() && !hasMethod(c, fp2)) return false;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Field fieldDeep(Class<?> owner, String name) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 指纹格式：name(param,param):ret，写 * 表示任意类型；name 为 <init> 时按构造器查。 */
    static boolean hasMethod(Class<?> c, String fp) {
        try {
            int lp = fp.indexOf('(');
            int rp = fp.lastIndexOf(')');
            int colon = fp.indexOf(':', rp);
            if (lp < 0 || rp < 0 || colon < 0) return false;
            String name = fp.substring(0, lp).trim();
            // 构造器不在 getDeclaredMethods() 里 —— 指纹里写 <init> 必须走构造器那条路，
            // 否则 Java 侧永远判定"不匹配"（生成器是解析 dex，构造器在方法表里，两边会打架）。
            if ("<init>".equals(name)) return hasCtor(c, fp);
            String inner = fp.substring(lp + 1, rp);
            String[] params = inner.isEmpty() ? new String[0] : inner.split(",");
            String ret = fp.substring(colon + 1).trim();

            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != params.length) continue;
                boolean match = true;
                for (int i = 0; i < pt.length && match; i++) {
                    match = typeMatch(params[i].trim(), pt[i]);
                }
                if (match) match = typeMatch(ret, m.getReturnType());
                if (match) return true;
            }
        } catch (Throwable ignored) {
            // 拿不到方法就当不匹配
        }
        return false;
    }

    /** 构造器指纹：<init>(java.lang.String):void —— 构造器不在 getDeclaredMethods 里，得单独查。 */
    static boolean hasCtor(Class<?> c, String fp) {
        try {
            int lp = fp.indexOf('(');
            int rp = fp.lastIndexOf(')');
            if (lp < 0 || rp < 0) return false;
            String inner = fp.substring(lp + 1, rp);
            String[] params = inner.isEmpty() ? new String[0] : inner.split(",");
            for (java.lang.reflect.Constructor<?> ct : c.getDeclaredConstructors()) {
                Class<?>[] pt = ct.getParameterTypes();
                if (pt.length != params.length) continue;
                boolean match = true;
                for (int i = 0; i < pt.length && match; i++) {
                    match = typeMatch(params[i].trim(), pt[i]);
                }
                if (match) return true;
            }
        } catch (Throwable ignored) {
            // 不匹配
        }
        return false;
    }

    private static boolean typeMatch(String expected, Class<?> actual) {
        if ("*".equals(expected)) return true;
        if (actual == null) return false;
        if (expected.equals(actual.getName()) || expected.equals(actual.getSimpleName())) return true;
        // 数组类型必须走 getCanonicalName()：数组的 getName() 是 "[Ljava.lang.Object;"、
        // getSimpleName() 是 "Object[]"，指纹里写的是 "java.lang.Object[]" —— 只比前两个永远不匹配。
        return expected.equals(actual.getCanonicalName());
    }

    // ── 学习缓存 ─────────────────────────────────────────────────────────────

    private static JSONObject readLearned() {
        try {
            String s = Storage.str(key(), "");
            if (s == null || s.isEmpty()) return null;
            return new JSONObject(s);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 扫描命中就落盘，下次启动零成本。 */
    static void learn(String slot, String className) {
        try {
            JSONObject cur = learned == null ? new JSONObject() : new JSONObject(learned.toString());
            List<String> have = fromJson(cur, slot);
            if (!have.contains(className)) have.add(className);
            JSONArray arr = new JSONArray();
            for (String n : have) arr.put(n);
            cur.put(slot, arr.length() == 1 ? arr.getString(0) : arr);
            learned = cur;
            Storage.setStr(key(), cur.toString());
        } catch (Throwable ignored) {
            // 缓存写不进去也不影响本次运行
        }
    }

    /** 缓存按 versionCode 分开：换版本自动作废，不需要手动清。 */
    private static String key() {
        return "ax_" + (WxVersion.code() > 0 ? WxVersion.code() : 0);
    }

    // ── 面板要用的展示接口 ───────────────────────────────────────────────────

    static String source(String slot) {
        String s = source.get(slot);
        return s == null ? "-" : s;
    }

    /** 面板顶部的适配摘要。 */
    static String summary() {
        init();
        int okFeatures = 0;
        List<String> miss = new ArrayList<>();
        for (String[] f : FEATURES) {
            boolean yes = false;
            for (int i = 1; i < f.length; i++) {
                if (ok(f[i])) {
                    yes = true;
                    break;
                }
            }
            if (yes) okFeatures++;
            else miss.add(f[0]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("微信 ").append(WxVersion.label())
                .append(" · 功能适配 ").append(okFeatures).append('/').append(FEATURES.length);
        if (!tableOk) sb.append("（版本表未加载）");
        if (!miss.isEmpty()) {
            sb.append("\n未适配：");
            for (int i = 0; i < miss.size(); i++) {
                if (i > 0) sb.append('、');
                sb.append(miss.get(i));
            }
        }
        return sb.toString();
    }

    /** 每个功能的适配状态，给面板逐行显示。 */
    static Map<String, String> features() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] f : FEATURES) {
            StringBuilder detail = new StringBuilder();
            for (int i = 1; i < f.length; i++) {
                if (detail.length() > 0) detail.append(" · ");
                detail.append(label(f[i])).append(ok(f[i]) ? "✓" : "✗");
            }
            out.put(f[0], detail.toString());
        }
        return out;
    }

    static String label(String slot) {
        if (SYSMSG.equals(slot)) return "撤回解析";
        if (MSG.equals(slot)) return "消息实体";
        if (CHAT_BINDER.equals(slot)) return "行绑定";
        if (CHAT_ADAPTER.equals(slot)) return "聊天列表";
        if (RNG.equals(slot)) return "随机数";
        if (EMOJI_PANEL.equals(slot)) return "表情面板";
        if (GALLERY.equals(slot)) return "图片浏览";
        if (CARE_MODE.equals(slot)) return "入口页面";
        if (SNS_DB.equals(slot)) return "朋友圈库";
        if (SNS_DB2.equals(slot)) return "朋友圈库2";
        if (AD_APPBRAND.equals(slot)) return "小程序广告";
        if (AD_OPEN.equals(slot)) return "开屏广告";
        if (SNS_AD.equals(slot)) return "朋友圈广告";
        if (QR_SCAN.equals(slot)) return "扫码处理";
        if (SPORT_UTIL.equals(slot)) return "步数工具";
        if (SPORT_DETECTOR.equals(slot)) return "步数监听";
        if (SPORT_PUSH_DETECTOR.equals(slot)) return "步数推送监听";
        return slot;
    }

    static String[] slots() {
        return ALL_SLOTS.clone();
    }

    static JSONObject rule(String slot) {
        init();
        try {
            return rules == null ? null : rules.optJSONObject(slot);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Set<String> knownVersions() {
        Set<String> out = new TreeSet<>();
        try {
            if (versions != null) {
                Iterator<String> it = versions.keys();
                while (it.hasNext()) out.add(it.next());
            }
        } catch (Throwable ignored) {
            // 空表
        }
        return out;
    }

    /** 面板「重新适配」：清掉内存与学习缓存，触发重扫。 */
    static void forget() {
        synchronized (LOCK) {
            cache.clear();
            source.clear();
            learned = null;
            Storage.setStr(key(), "");
            AutoScan.reset();
        }
    }

    private Anchors() {}
}
