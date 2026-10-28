package io.github.lex0m.lexin;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 主页仿 TG 悬浮底栏（v1.3.1）。
 *
 * 把微信主页底部的原生 TabBar（com.tencent.mm.ui.LauncherUIBottomTabView，类名未混淆）
 * 改成悬浮圆角卡片：圆角 / 阴影 / 左右侧边距 / 底部抬升。
 *
 * 纯本地 UI 改造：只动 View 外观，不替换导航逻辑、不接 Compose、零联网，检测面最小。
 * 思路同 FloatingFooter（聊天输入栏悬浮化），只是目标 View 换成主页底栏。
 *
 * 参数固定为出厂值：圆角 20dp · 侧边距 12dp · 底部间距 6dp · 阴影 6dp。
 */
final class HomeFloatingTab {

    private static final String T = "svc-hftab: ";

    private static final String TAB_CLS = "com.tencent.mm.ui.LauncherUIBottomTabView";

    private static final int SIDE_MARGIN_DP = 20;
    private static final int BOTTOM_GAP_DP = 14;
    private static final int ELEVATION_DP = 12;

    static void install() {
        Class<?> cls = Silent.cls(Features.loader, TAB_CLS);
        boolean ok = cls != null && hook(cls);
        Status.set("主页悬浮底栏", ok);
        Status.note("主页悬浮底栏", ok ? "主页底栏已悬浮卡片化" : "当前微信版本未适配");
        Log.d(T + "install ok=" + ok + " cls=" + (cls == null ? "null" : cls.getName()));
    }

    private static boolean hook(Class<?> cls) {
        int n = 0;
        try {
            // 构造后立即设圆角 + 阴影（不依赖 LayoutParams）
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (Prefs.onDefault(Prefs.K_HOME_FLOAT_TAB)) applyDrawingStyle(p.thisObject);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;

            // 左右边距 / 底部间距要等 attach（父容器 addView 后 LayoutParams 才非空）
            XposedBridge.hookAllMethods(cls, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (Prefs.onDefault(Prefs.K_HOME_FLOAT_TAB)) applyMargins(p.thisObject);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 挂不上就算了
        }
        Log.d(T + "hooked=" + n);
        return n > 0;
    }

    private static void applyDrawingStyle(Object obj) {
        if (!(obj instanceof View)) return;
        final View tab = (View) obj;
        final float density = tab.getResources().getDisplayMetrics().density;
        // 半透明玻璃底：让底栏明显「悬浮」在内容上方，而不是贴边的实心白条
        tab.setBackgroundColor(0xB3FFFFFF);
        tab.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // 胶囊（灵动岛）：圆角 = 高度一半，两端完全半圆
                float r = view.getHeight() / 2f;
                outline.setRoundRect(0, 0, view.getWidth(), Math.max(view.getHeight(), 1), r);
            }
        });
        tab.setClipToOutline(true);
        tab.setElevation(ELEVATION_DP * density);
    }

    private static void applyMargins(Object obj) {
        if (!(obj instanceof View)) return;
        View tab = (View) obj;
        ViewGroup.LayoutParams lp = tab.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        float density = tab.getResources().getDisplayMetrics().density;
        int side = (int) (SIDE_MARGIN_DP * density);
        int bottom = (int) (BOTTOM_GAP_DP * density);
        mlp.leftMargin = side;
        mlp.rightMargin = side;
        mlp.bottomMargin = bottom;
        tab.requestLayout();
    }

    private HomeFloatingTab() {}
}
