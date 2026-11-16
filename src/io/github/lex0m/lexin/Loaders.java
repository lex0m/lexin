package io.github.lex0m.lexin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类加载器集合（v1.6.2）。
 *
 * 照着 wcx 的 `ClassLoaderRegistry` / `HybridClassLoader` 的思路做：**不要相信任何单一 ClassLoader**。
 *
 * 微信 8.0.80 用 Tinker 热修，真正执行的类由 `DelegateLastClassLoader` 从
 * patch 目录下的 tinker_classN.apk 加载；而 `XC_LoadPackage.classLoader` 给的常常是
 * base.apk 的 `PathClassLoader`。用后者解析出来的类"存在、能挂、永远不被调用"——面板全绿、
 * 功能全哑。
 *
 * 这里把进程里能找到的 loader 全收进来（含 ActivityThread 里每个 LoadedApk 的 loader），
 * 解析时按"路径里带 tinker/patch 的优先"排序，逐个尝试。这样不依赖框架给哪一个，也不依赖
 * 采纳时机。
 */
final class Loaders {

    private static final String T = "svc-loaders: ";
    private static volatile List<ClassLoader> ordered;

    /** 收集 + 排序（懒加载，进程内只做一次；热修换 loader 后调 reset 重收）。 */
    static List<ClassLoader> candidates() {
        List<ClassLoader> l = ordered;
        if (l != null) return l;
        synchronized (Loaders.class) {
            if (ordered != null) return ordered;
            Map<ClassLoader, Boolean> seen = new IdentityHashMap<>();
            List<ClassLoader> out = new ArrayList<>();
            add(seen, out, Features.loader);
            add(seen, out, safeTccl());
            add(seen, out, Features.app == null ? null : Features.app.getClass().getClassLoader());
            add(seen, out, Loaders.class.getClassLoader());
            add(seen, out, de.robv.android.xposed.XposedBridge.class.getClassLoader());
            add(seen, out, ClassLoader.getSystemClassLoader());
            add(seen, out, new android.content.ContextWrapper(null) {
            }.getClass().getClassLoader());     // 兜底：框架侧的 loader
            collectActivityThread(seen, out);
            // 运行时（补丁）loader 优先：路径里带 tinker / patch 的排前面
            Collections.sort(out, new java.util.Comparator<ClassLoader>() {
                @Override
                public int compare(ClassLoader a, ClassLoader b) {
                    return rank(a) - rank(b);
                }

                private int rank(ClassLoader cl) {
                    String s = String.valueOf(cl);
                    if (s.contains("tinker") || s.contains("patch")) return 0;
                    if (s.contains("base.apk")) return 1;
                    return 2;
                }
            });
            ordered = out;
            Log.d(T + "candidates=" + out.size());
            return out;
        }
    }

    private static ClassLoader safeTccl() {
        try {
            return Thread.currentThread().getContextClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void add(Map<ClassLoader, Boolean> seen, List<ClassLoader> out, ClassLoader cl) {
        if (cl == null || seen.containsKey(cl)) return;
        seen.put(cl, Boolean.TRUE);
        out.add(cl);
    }

    /**
     * ActivityThread.mPackages 里每个 LoadedApk 都有自己的 ClassLoader（微信的热修/插件 dex
     * 往往就挂在这里）。反射拿一遍，不初始化任何业务类。
     */
    private static void collectActivityThread(Map<ClassLoader, Boolean> seen, List<ClassLoader> out) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            if (thread == null) return;
            for (String field : new String[]{"mPackages", "mResourcePackages"}) {
                try {
                    java.lang.reflect.Field f = at.getDeclaredField(field);
                    f.setAccessible(true);
                    Object v = f.get(thread);
                    if (!(v instanceof Map)) continue;
                    for (Object ref : ((Map<?, ?>) v).values()) {
                        Object apk = ref instanceof java.lang.ref.Reference
                                ? ((java.lang.ref.Reference<?>) ref).get() : ref;
                        if (apk == null) continue;
                        try {
                            java.lang.reflect.Field clf = apk.getClass()
                                    .getDeclaredField("mClassLoader");
                            clf.setAccessible(true);
                            Object cl = clf.get(apk);
                            if (cl instanceof ClassLoader) add(seen, out, (ClassLoader) cl);
                        } catch (Throwable ignored) {
                            // 这个 LoadedApk 拿不到就跳过
                        }
                    }
                } catch (Throwable ignored) {
                    // 字段不存在就跳过
                }
            }
        } catch (Throwable ignored) {
            // 拿不到 ActivityThread 就只用静态根
        }
    }

    /** 按候选顺序解析类；全失败返回 null。 */
    static Class<?> load(String name) {
        if (name == null || name.isEmpty()) return null;
        for (ClassLoader cl : candidates()) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (c != null) return c;
            } catch (Throwable ignored) {
                // 下一个 loader
            }
        }
        return null;
    }

    /** 热修/插件 dex 换过之后重新收集。 */
    static void reset() {
        synchronized (Loaders.class) {
            ordered = null;
        }
    }

    private Loaders() {}
}
