package io.github.lex0m.lexin;

import android.graphics.drawable.ColorDrawable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 主题引擎：以聊天背景为主。 */
final class Theme {

    private static final String T = "svc-theme: ";

    // 背景是独立设置（不属于主题包）；键名在 Storage 里声明，保证两个进程看到的一致。
    static final String K_BG = Storage.K_BG;
    static final String K_BG_HOME = Storage.K_BG_HOME;
    /** 底栏着色（0 = 不动）。 */
    static final String K_TAB_TINT = "theme_tab_tint";
    /** 标题栏着色（0 = 不动）。 */
    static final String K_BAR_TINT = "theme_bar_tint";

    static void install() throws Throwable {
        if (!Prefs.on(Prefs.K_THEME, false)) {
            Log.d(T + "master switch off");
            return;
        }
        int n = 0;
        n += hookChatBackground();
        n += hookHomeUI();
        n += hookBgView();
        n += hookImages();
        n += hookTextScale();
        n += hookTextColour();
        n += hookHomeBackground();
        n += hookBottomTab();
        Log.d(T + "armed=" + n);
    }

    // ── chat background ──────────────────────────────────────────────────────

    /** 聊天背景视图本身就是 ImageView，在它的构造里设 drawable 最省事。 */
    private static int hookBgView() {
        int hits = 0;
        try {
            Class<?> cls = Silent.cls(Features.loader,
                    S.CHAT_IMG_BG);
            if (cls == null) {
                Log.d(T + "chat bg view absent");
                return 0;
            }
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Drawable d = backgroundDrawable();
                                if (d != null && p.thisObject instanceof ImageView) {
                                    ((ImageView) p.thisObject).setImageDrawable(d);
                                }
                            } catch (Throwable ignored) {
                                // 仅外观
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个构造
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 聊天背景：主题包优先，其次是面板设置。 */
    private static Drawable backgroundDrawable() {
        // 面板设置优先：背景是用户的选择，不是主题。
        try {
            String path = Prefs.str(K_BG, null);
            if (path == null || path.isEmpty()) return null;
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            return Drawable.createFromPath(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 主界面背景：LauncherUI 承载四个 tab，画它的 decor 就够。 */
    private static int hookHomeBackground() {
        int hits = 0;
        try {
            Class<?> launcher = Silent.cls(Features.loader, S.CLS_LAUNCHER);
            if (launcher == null) return 0;
            for (Method m : launcher.getDeclaredMethods()) {
                if (!"onCreate".equals(m.getName()) || m.getParameterCount() != 1) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Drawable d = homeBackgroundDrawable();
                                if (d == null) return;
                                if (!(p.thisObject instanceof android.app.Activity)) return;
                                android.app.Activity act = (android.app.Activity) p.thisObject;
                                View decor = act.getWindow().getDecorView();
                                if (decor != null) decor.setBackground(d);
                            } catch (Throwable ignored) {
                                // 仅外观
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 主题包提供的主界面背景，未设置返回 null。 */
    private static Drawable homeBackgroundDrawable() {
        try {
            String path = Prefs.str(K_BG_HOME, null);
            if (path == null || path.isEmpty()) return null;
            File f = new File(path);
            if (!f.exists() || !f.canRead()) return null;
            return Drawable.createFromPath(f.getAbsolutePath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 底栏文字颜色：重造整个栏会接管点击事件，风险大，所以只改颜色。 */
    private static int hookBottomTab() {
        int hits = 0;
        try {
            Class<?> cls = Silent.cls(Features.loader,
                    S.CLS_BTAB);
            if (cls == null) {
                Log.d(T + "tab bar class missing");
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (!("k".equals(n) || "f".equals(n) || "i".equals(n) || "j".equals(n))) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int colour = ThemePack.colour("tabbar_text", 0);
                                if (colour == 0) return;
                                if (p.thisObject instanceof View) {
                                    paintText((View) p.thisObject, colour);
                                }
                            } catch (Throwable ignored) {
                                // 仅外观
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 重设子树里文字的颜色，不动图标。 */
    private static void paintText(View v, int colour) {
        if (v == null) return;
        try {
            if (v instanceof TextView) ((TextView) v).setTextColor(colour);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) paintText(g.getChildAt(i), colour);
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    // ── text colour ──────────────────────────────────────────────────────────

    /** 应用主题包的文字色：只挂 setTextColor(int) 这个重载。 */
    private static int hookTextColour() {
        int hits = 0;
        try {
            for (Method m : TextView.class.getDeclaredMethods()) {
                if (!"setTextColor".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != int.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                int c = ThemePack.colour("text", 0);
                                if (c != 0) p.args[0] = c;
                            } catch (Throwable ignored) {
                                // 保留宿主原色
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 标题栏/状态栏颜色接管（8.0.78 锚点：HomeUI 的两个方法）。 */
    private static int hookHomeUI() {
        int hits = 0;
        try {
            Class<?> home = Silent.cls(Features.loader, S.CLS_HOME);
            if (home == null) {
                Log.d(T + "HomeUI missing");
                return 0;
            }
            hits += hookColourArg(home, "q", K_BAR_TINT);
            hits += hookColourArg(home, "v", K_BAR_TINT);
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    // ── image replacement engine ─────────────────────────────────────────────
    //
    // 主题素材按宿主自己的资源名匹配：微信请求 drawable/ChatRoom_Bubble_Text_Sender_Green 时，主题目录里有同名短名文件就替换掉它请求的图。这一条规则覆盖气泡、tab 图标、头像框与背景，社区主题包（Themebox 格式）就是这么做的。

    /** shortName -> drawable 缓存，重复绑定不重复读文件。 */
    private static final Map<String, Drawable> imageCache = new ConcurrentHashMap<>();
    /** 防止重入我们自己的替换。 */
    private static final ThreadLocal<Boolean> replacing = new ThreadLocal<Boolean>();

    private static int hookImages() {
        int hits = 0;
        try {
            // 进程里所有 ImageView 都会走这两个方法。
            Class<?> iv = ImageView.class;
            for (String name : new String[]{"setImageResource"}) {
                for (Method m : iv.getDeclaredMethods()) {
                    if (!name.equals(m.getName()) || m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != int.class) continue;
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    if (Boolean.TRUE.equals(replacing.get())) return;
                                    Object self = p.thisObject;
                                    if (!(self instanceof ImageView)) return;
                                    ImageView view = (ImageView) self;
                                    Object a0 = p.args[0];
                                    if (!(a0 instanceof Integer)) return;
                                    int resId = (Integer) a0;
                                    if (resId == 0) return;

                                    String full = view.getResources().getResourceName(resId);
                                    if (full == null) return;
                                    String shortName = full.substring(full.lastIndexOf('/') + 1);

                                    Drawable themed = themedDrawable(shortName);
                                    if (themed == null) return;

                                    replacing.set(Boolean.TRUE);
                                    try {
                                        view.setImageDrawable(themed);
                                    } finally {
                                        replacing.set(Boolean.FALSE);
                                    }
                                } catch (Throwable ignored) {
                                    // 不影响渲染
                                }
                            }
                        });
                        hits++;
                    } catch (Throwable ignored) {
                        // 下一个重载
                    }
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 交给主题包解析——主题包可以是扁平结构，也可以用 images/ 子目录。 */
    static Drawable themedDrawable(String shortName) {
        return ThemePack.image(shortName);
    }

    /** 面板设置优先于主题包；0 表示不接管，-1 表示回退到主题包。 */
    private static boolean takeover(String prefKey) {
        int panel = Prefs.num(prefKey, -1);
        return panel != 0 && (panel > 0 || ThemePack.colour("actionbar", 0) != 0);
    }

    private static int resolvedColour(String prefKey) {
        int panel = Prefs.num(prefKey, -1);
        if (panel > 0) return panel;
        if (panel == 0) return 0;
        return ThemePack.colour("actionbar", 0);
    }

    // ── font scale / text colour ─────────────────────────────────────────────
    //
    // TextView.setTextSize 有两个重载（float px、sp+unit），都按主题包的 widgets.font_scale 缩放（1.0 = 不动）。文字颜色只在主题包确实定义了才应用，空值保留宿主原色——面板才还原得回去。

    private static int hookTextScale() {
        int hits = 0;
        try {
            for (Method m : TextView.class.getDeclaredMethods()) {
                if (!"setTextSize".equals(m.getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                float scale = ThemePack.decimal("font_scale", 1.0f);
                                if (scale > 0.05f && Math.abs(scale - 1.0f) > 0.01f) {
                                    Object a0 = p.args[0];
                                    if (a0 instanceof Float) {
                                        p.args[0] = ((Float) a0) * scale;
                                    }
                                }
                            } catch (Throwable ignored) {
                                // 保留宿主字号
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 感知亮度判断，够用来决定文字用黑还是白。 */
    private static boolean isDark(int colour) {
        int r = (colour >> 16) & 0xFF;
        int g = (colour >> 8) & 0xFF;
        int b = colour & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140;
    }

    /** 重设文字/图标颜色，让它们在新背景上仍然可读。 */
    private static void paintForeground(View v, int colour) {
        if (v == null) return;
        try {
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(colour);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    paintForeground(g.getChildAt(i), colour);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 替换所有「单个 int 参数」重载里的颜色实参。 */
    private static int hookColourArg(Class<?> owner, String name, final String prefKey) {
        int hits = 0;
        for (Method m : owner.getDeclaredMethods()) {
            if (!name.equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != int.class) continue;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!takeover(prefKey)) return;
                            p.args[0] = resolvedColour(prefKey);
                        } catch (Throwable ignored) {
                            // 保留宿主原色
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!takeover(prefKey)) return;
                            int c = resolvedColour(prefKey);
                            // 前景与背景要配套：只改填充色会让标题沉进背景（真机上观察到的），所以文字按新亮度重设。
                            if (p.thisObject instanceof View) {
                                paintForeground((View) p.thisObject,
                                        isDark(c) ? 0xFFFFFFFF : 0xFF1C1C1E);
                            }
                        } catch (Throwable ignored) {
                            // 仅外观
                        }
                    }
                });
                hits++;
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }
        return hits;
    }

    private static int hookChatBackground() {
        int hits = 0;
        try {
            Class<?> comp = Silent.cls(Features.loader,
                    "com.tencent.mm.ui.chatting.component.v2");
            if (comp == null) {
                Log.d(T + "background component missing");
                return 0;
            }
            for (Method m : comp.getDeclaredMethods()) {
                if (!"y0".equals(m.getName()) || m.getParameterCount() != 0) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            applyBackground(p.thisObject);
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个重载
                }
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    private static void applyBackground(Object component) {
        try {
            Drawable d = backgroundDrawable();
            if (d == null) return;

            ImageView anchor = (ImageView) firstFieldOfType(component, ImageView.class);
            if (anchor == null) return;
            View parent = (View) anchor.getParent();
            if (!(parent instanceof ViewGroup)) return;

            View first = ((ViewGroup) parent).getChildAt(0);
            if (!(first instanceof ImageView)) return;
            ImageView layer = (ImageView) first;
            // 只有纯色那一层归我们替换。
            if (layer.getDrawable() instanceof ColorDrawable) {
                layer.setImageDrawable(d);
                Log.d(T + "background applied");
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 沿父类链找到第一个类型匹配的已声明字段。 */
    static Object firstFieldOfType(Object owner, Class<?> wanted) {
        if (owner == null) return null;
        try {
            Class<?> c = owner.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || f.getType().isArray()) continue;
                    if (!wanted.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null) return v;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }
}
