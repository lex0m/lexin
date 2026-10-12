package io.github.lex0m.lexin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 去广告：只拦「本地决策点」，不改任何网络请求。
 *
 * v1.0 里的四个门有两个在 8.0.78 上已经失效（ah2.v.h、finder.convert.w4.X 这两个类名
 * 早就不带那些方法了）—— 现在是按锚点表 + 自动扫描定位，装不上的版本会在面板里标明。
 */
final class AdBlock {

    private static final String T = "svc-ad: ";

    static void install() {
        int open = 0;
        open += deny(Anchors.AD_APPBRAND, "K2");
        open += deny(Anchors.AD_OPEN, "a");
        // 朋友圈信息流广告：掐掉"广告对象"的构造/投喂，条目根本进不了时间线。
        // 走锚点（类名 + 广告常量 + (String) 构造器 三重特征校验），微信改名也认得出。
        // 归属：和开屏/小程序一样，都归「去广告」这一个开关管。
        int sns = blockMomentsAd();

        boolean any = open + sns > 0;
        Status.set("去广告", any);
        Status.note("去广告", any
                ? "拦截点 " + (open + sns) + " 个（开屏/小程序 " + open + " · 朋友圈 " + sns + "）"
                : "当前微信版本未适配");
        Log.d(T + "armed open=" + open + " sns=" + sns);
    }

    /**
     * 朋友圈信息流广告拦截。
     *
     * 做法参考 WeKit：朋友圈广告在被解析出来时一定会走那个「广告信息」类的
     * (String) 构造器（8.0.78 是 com.tencent.mm.plugin.sns.storage.ADInfo），
     * 在这里直接拦掉，广告条目就不会被组装进时间线。
     */
    private static int blockMomentsAd() {
        List<Class<?>> list = Anchors.all(Anchors.SNS_AD);
        int hits = 0;
        for (final Class<?> c : list) {
            // 第一层：广告对象的 (String) 构造（WeKit 同款，直接让构造不成立）
            for (Constructor<?> ct : c.getDeclaredConstructors()) {
                Class<?>[] pt = ct.getParameterTypes();
                if (pt.length != 1 || pt[0] != String.class) continue;
                try {
                    ct.setAccessible(true);
                    XposedBridge.hookMethod(ct, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Prefs.onDefault(Prefs.K_ADS)) return;
                            p.setResult(null);          // 构造不成立 → 这个广告条目就没了
                            Log.d(T + "blocked moments ad");
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个构造器
                }
            }
            // 第二层：有些版本是「空构造 + feed(xml)」两步建的，把喂数据那一步也掐掉
            for (Method m : c.getDeclaredMethods()) {
                if (!"feed".equals(m.getName())) continue;
                if (m.getReturnType() != void.class || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Prefs.onDefault(Prefs.K_ADS)) return;
                            p.setResult(null);          // 跳过方法体：广告数据不落地
                            Log.d(T + "blocked moments ad feed");
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        return hits;
    }

    /** 把所有 boolean 重载都答「否」，宿主跳过分支。 */
    private static int deny(String slot, String name) {
        List<Class<?>> list = Anchors.all(slot);
        int hits = 0;
        for (final Class<?> c : list) {
            for (final Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Prefs.onDefault(Prefs.K_ADS)) return;
                            p.setResult(Boolean.FALSE);
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        }
        return hits;
    }

    private AdBlock() {}
}
