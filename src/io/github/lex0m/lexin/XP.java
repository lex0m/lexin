package io.github.lex0m.lexin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 只读配置桥（v1.6.0）。
 *
 * 目标：微信进程**不读也不写自己的任何文件**，还能拿到用户配置。
 *
 * 通道选型：
 *   1) 框架的 {@code XSharedPreferences} —— 标准做法，读的是模块自己的 prefs 文件，
 *      对微信目录零写入。这里全程反射调用，且类名走 {@link S} 的混淆表，
 *      dex 常量池里不留明文（静态扫不出"这个模块在用 XSharedPreferences"）。
 *   2) 读不到就返回 null，调用方退到广播，再退到默认值 —— 任何一级失败都不影响微信。
 *
 * 说明：模块侧每次改配置会同时写一份"给宿主读的"镜像（{@link Storage#publishForHost}），
 * 这份镜像位于模块私有目录，微信进程只是读者。
 */
final class XP {

    /** "de.robv.android.xposed.XSharedPreferences"（XOR+Base64，运行期解）。 */
    private static final String CLS =
            S.d("Pj90KDU4LHQ7ND4oNTM+dCIqNSk/PnQCCTI7KD8+Cig/PD8oPzQ5Pyk=");

    /** 模块侧给宿主读的那份 prefs 文件名（短名，无版本号）。 */
    static final String PREFS = "a";
    /** 整份镜像存在这一个键里（值是混淆后的整串，不是明文 k=v）。 */
    static final String KEY = "d";

    private static volatile Object ref;
    private static volatile Method getString;

    /** 读模块侧镜像；任何异常一律返回 null（调用方必须能容忍）。 */
    static String read(String modulePkg, String key) {
        try {
            if (ref == null) {
                Class<?> c = Class.forName(CLS, false, XP.class.getClassLoader());
                Constructor<?> ct = c.getConstructor(String.class, String.class);
                Object o = ct.newInstance(modulePkg, PREFS);
                c.getMethod("reload").invoke(o);
                getString = c.getMethod("getString", String.class, String.class);
                ref = o;
            }
            Object v = getString.invoke(ref, key, null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private XP() {}
}
