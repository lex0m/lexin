package io.github.lex0m.lexin;

import java.util.List;

/**
 * DexKit 封装：按**字符串特征**在运行时 dex 里找类（v1.7.6）。
 *
 * 为什么必须有它：微信 8.0.80 的核心业务类被 Tinker 补丁替换，
 * base.apk 里那些字符串全是「孤儿串」（存在但零代码引用），
 * patch.apk 是自研 DXDIFF 格式，tinker_classN.apk 全量扫也无结果。
 * 而 DexKit 扫的是**运行时 ClassLoader 里已加载的 dex**，能按字符串精确命中 ——
 * WeKit / wcx 的所有锚点都是这么找的。
 *
 * 注意：DexKit 是 Kotlin 写的，这里全程用 Java 调它的静态工厂；
 * 初始化一次后复用（创建开销大，扫 dex 更贵）。
 */
final class DexFind {

    private static final String T = "svc-dexfind: ";

    private static volatile Object bridge;      // DexKitBridge，避免编译期强依赖
    private static volatile boolean failed = false;
    /** 进程内结果缓存（键 = 包名 + 特征串）。换 loader / 换宿主版本后必须清。 */
    private static final java.util.Map<String, java.util.List<String>> cache = new java.util.HashMap<>();
    /** 最近一次失败原因（给探针显示，省得只能靠 logcat）。 */
    static volatile String lastError = "";

    /**
     * ★ 提前把 native 库加载好（v1.7.6 真机踩出来的）。
     *
     * DexKit 自己的 Kotlin 伴生对象里应该会 loadLibrary("dexkit")，
     * 但经过 R8 之后实测没生效 —— 报 `UnsatisfiedLinkError:
     * No implementation found for long ...DexKitBridge.nativeInitDexKitByClass`。
     * 而 .so 确实在 /data/app/.../lib/arm64/libdexkit.so 里。
     * 这里显式加载一次（幂等，重复加载无害）。
     */
    static {
        try {
            System.loadLibrary("dexkit");
        } catch (Throwable t) {
            lastError = "loadLibrary: " + t.getClass().getSimpleName();
        }
    }

    /** 懒初始化；失败返回 null（调用方必须能容忍）。 */
    private static Object bridge(ClassLoader cl) {
        if (bridge != null) return bridge;
        if (failed) return null;
        synchronized (DexFind.class) {
            if (bridge != null) return bridge;
            try {
                Class<?> c = Class.forName("org.luckypray.dexkit.DexKitBridge", false, DexFind.class.getClassLoader());
                // Kotlin object 的静态方法：DexKitBridge.create(ClassLoader, boolean)
                java.lang.reflect.Method create = c.getMethod("create", ClassLoader.class, boolean.class);
                Object b = create.invoke(null, cl, true);
                bridge = b;
                Log.d(T + "bridge created");
                return b;
            } catch (Throwable t) {
                failed = true;
                Throwable c = t;
                StringBuilder why = new StringBuilder();
                for (int i = 0; i < 4 && c != null; i++) {
                    why.append(i == 0 ? "" : " <- ").append(c.getClass().getSimpleName());
                    if (c.getMessage() != null) {
                        String m = c.getMessage();
                        why.append(':').append(m.length() > 90 ? m.substring(0, 90) : m);
                    }
                    c = c.getCause();
                }
                lastError = "bridge: " + why;
                Log.d(T + "bridge failed: " + t);
                return null;
            }
        }
    }

    /**
     * 在指定包里找「同时引用这些字符串」的类。
     *
     * @param pkg         包名（如 com.tencent.mm.modelsimple）
     * @param eqStrings   必须同时出现的字符串
     * @return 类名列表（找不到返回空表）
     */
    static java.util.List<String> findClasses(ClassLoader cl, String pkg, String... eqStrings) {
        return findClasses(cl, pkg, true, eqStrings);
    }

    /**
     * @param exact true = `usingEqStrings`（**整串精确匹配**，少一个字符都不命中）；
     *              false = `usingStrings`（**子串包含**，适合锚点表里那些日志前缀片段）。
     *
     * ★ v1.7.9 真机踩出来的：锚点表 `slots[*].strings` 存的是**日志前缀片段**
     * （如 `doRevokeMsg revokeFlag=`），拿它去 `usingEqStrings` 精确匹配必然 0 命中 ——
     * 兜底链看着跑完了，其实一次都没找着。DexProbe 那边用的是完整串，所以能命中，
     * 两处语义不同，用同一个方法就会互相骗。
     */
    static java.util.List<String> findClasses(ClassLoader cl, String pkg, boolean exact, String... strings) {
        // v1.7.9（兼容/性能）：DexKit 一次全量扫 250~320ms，同一个特征在同一进程里
        // 往往被问多次（探针 + 功能重装 + 锚点重试）。按「包 + 模式 + 特征串」做进程内缓存：
        // 命中就返回副本，避免调用方改到缓存本体。换 loader 时必须 resetCache()。
        final String key = (pkg == null ? "" : pkg) + '#' + exact + '#'
                + java.util.Arrays.toString(strings);
        synchronized (cache) {
            java.util.List<String> hit = cache.get(key);
            if (hit != null) return new java.util.ArrayList<>(hit);
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Object b = bridge(cl);
        if (b == null) return out;
        try {
            Class<?> fcCls = Class.forName("org.luckypray.dexkit.query.FindClass", false, DexFind.class.getClassLoader());
            Class<?> cmCls = Class.forName("org.luckypray.dexkit.query.matchers.ClassMatcher", false, DexFind.class.getClassLoader());

            Object findClass = fcCls.getMethod("create").invoke(null);
            // pkg 传 null/空 = 不限定包（补丁后的类可能已经被搬到别的包里，限定包会漏）
            if (pkg != null && !pkg.isEmpty()) {
                findClass = fcCls.getMethod("searchPackages", String[].class)
                        .invoke(findClass, (Object) new String[]{pkg});
            }

            Object matcher = cmCls.getMethod("create").invoke(null);
            // exact=true → 整串精确；false → 子串包含（锚点表的 strings 是日志前缀片段）
            matcher = cmCls.getMethod(exact ? "usingEqStrings" : "usingStrings", String[].class)
                    .invoke(matcher, (Object) strings);
            findClass = fcCls.getMethod("matcher", cmCls).invoke(findClass, matcher);

            Object result = b.getClass().getMethod("findClass", fcCls).invoke(b, findClass);
            if (result instanceof List) {
                for (Object cd : (List<?>) result) {
                    try {
                        Object name = cd.getClass().getMethod("getName").invoke(cd);
                        if (name instanceof String) out.add((String) name);
                    } catch (Throwable ignored) {
                    }
                }
            }
            Log.d(T + "findClasses(" + pkg + ") -> " + out);
            // 只缓存"真的扫完了"的结果（空表也缓存：说明这套特征在这个版本上确实没有，
            // 反复重扫只是白花 300ms）。bridge 失败在上面的 return 已经出去了。
            synchronized (cache) {
                cache.put(key, new java.util.ArrayList<>(out));
            }
        } catch (Throwable t) {
            lastError = "find: " + t;
            Log.d(T + "findClasses failed: " + t);
        }
        return out;
    }

    /** 诊断：列出 DexKitBridge 的静态 create 重载，确认参数形状对不对。 */    static String describeCreate() {
        try {
            Class<?> c = Class.forName("org.luckypray.dexkit.DexKitBridge", false, DexFind.class.getClassLoader());
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Method m : c.getMethods()) {
                if (!"create".equals(m.getName())) continue;
                sb.append(m.getParameterCount()).append('(');
                for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(',');
                sb.append(") ");
            }
            return sb.length() == 0 ? "no create()" : sb.toString();
        } catch (Throwable t) {
            return "cls: " + t.getClass().getSimpleName();
        }
    }

    /**
     * 清空结果缓存（v1.7.9）。
     *
     * Tinker 换 loader 之后，同一套特征在新 dex 里可能指向不同的类 ——
     * 缓存不清就是"挂到了旧类上"，症状和"锚点表写错"一模一样（面板绿、功能哑）。
     */
    static void resetCache() {
        synchronized (cache) {
            cache.clear();
        }
        Log.d(T + "cache cleared");
    }

    private DexFind() {}
}
