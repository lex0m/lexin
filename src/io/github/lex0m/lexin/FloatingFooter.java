package io.github.lex0m.lexin;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 悬浮聊天底栏（v1.3）。
 *
 * 把微信聊天页底部的输入栏（ChatFooter）改成悬浮卡片：圆角、阴影、左右侧边距、底部抬升。
 * 思路照抄 CrackerCat/wcx 的 FloatingChatFooter（hook ChatFooter 构造 + onAttachedToWindow）。
 *
 * 参数固定为出厂值（可在后续版本加面板调节）：
 *   圆角 24dp · 侧边距 12dp · 底部间距 4dp · 阴影 4dp
 *
 * ChatFooter 类名在微信里长期未混淆（pluginsdk 包），但为跨版本稳健仍走锚点 exact 槽位，
 * 拿不到就退回直接 Class.forName。
 */
final class FloatingFooter {

    private static final String T = "svc-ffoot: ";

    private static final String CHAT_FOOTER_CLS = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";

    private static final int SIDE_MARGIN_DP = 20;
    private static final int MAX_CORNER_DP = 36;
    private static final int ELEVATION_DP = 8;

    private static volatile Class<?> footerCls;

    static void install() {
        footerCls = Anchors.cls(Anchors.CHAT_FOOTER);
        if (footerCls == null) {
            footerCls = Silent.cls(Features.loader, CHAT_FOOTER_CLS);
        }
        boolean ok = footerCls != null && hook();
        Status.set("悬浮聊天底栏", ok);
        Status.note("悬浮聊天底栏", ok ? "聊天输入栏已悬浮卡片化" : "当前微信版本未适配");
        Log.d(T + "install ok=" + ok + " cls=" + (footerCls == null ? "null" : footerCls.getName()));
    }

    private static boolean hook() {
        if (footerCls == null) return false;
        int n = 0;
        try {
            // 构造后立即设圆角 + 阴影（不依赖 LayoutParams）
            XposedBridge.hookAllConstructors(footerCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (Prefs.onDefault(Prefs.K_FLOAT_FOOTER)) applyDrawingStyle(p.thisObject);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;

            // 左右边距要等 onAttachedToWindow（父容器 addView 后 LayoutParams 才非空）
            XposedBridge.hookAllMethods(footerCls, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (Prefs.onDefault(Prefs.K_FLOAT_FOOTER)) applySideMargins(p.thisObject);
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

    /** 圆角 + 阴影：构造后即可设置，不依赖 LayoutParams。 */
    private static void applyDrawingStyle(Object obj) {
        if (!(obj instanceof View)) return;
        final View footer = (View) obj;
        final float density = footer.getResources().getDisplayMetrics().density;
        footer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // 胶囊（灵动岛）：圆角 = 高度一半（输入行收起时是完整胶囊），
                // 上限 36dp —— 表情面板展开时高度骤增，别圆成椭圆。
                // 用 view 自身高度作边界（不能加 bottomMargin：微信把它设成 -键盘高，加了会塌成 1px）。
                float r = Math.min(view.getHeight() / 2f, MAX_CORNER_DP * density);
                outline.setRoundRect(0, 0, view.getWidth(), Math.max(view.getHeight(), 1), r);
            }
        });
        footer.setClipToOutline(true);
        footer.setElevation(ELEVATION_DP * density);
    }

    /** 左右侧边距：让输入栏呈现悬浮卡片的内缩。 */
    private static void applySideMargins(Object obj) {
        if (!(obj instanceof View)) return;
        View footer = (View) obj;
        ViewGroup.LayoutParams lp = footer.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        int m = (int) (SIDE_MARGIN_DP * footer.getResources().getDisplayMetrics().density);
        mlp.leftMargin = m;
        mlp.rightMargin = m;
        footer.requestLayout();
    }

    private FloatingFooter() {}
}
