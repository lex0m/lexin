package io.github.lex0m.lexin;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 界面套件：面板和模块侧两个 Activity 共用。
 *
 * 全部用代码画（没有布局 XML、没有 drawable 资源）—— 面板跑在微信进程里，
 * 只能靠代码构建视图。配色走「浅灰底 + 白卡片 + 冷色主色」，圆角 14dp、留白 16dp，
 * 分组各配一个强调色，开关也按强调色着色。
 */
final class Ui {

    // ── 配色 ────────────────────────────────────────────────────────────────

    static final int BG = 0xFFF3F4F7;
    static final int CARD = 0xFFFFFFFF;
    static final int TITLE = 0xFF15171C;
    static final int TEXT = 0xFF2A2E36;
    static final int SUB = 0xFF8A8F9A;
    static final int LINE = 0xFFEDEFF3;
    static final int ACCENT = 0xFF4A6CF7;
    static final int ACCENT_DEEP = 0xFF7C5CFC;
    static final int OK = 0xFF0F9D58;
    static final int WARN = 0xFFE5484D;
    static final int GOLD = 0xFFF59E0B;

    /** 四个分组各一个强调色，视觉上分得开。 */
    static final int C_CHAT = 0xFF4A6CF7;
    static final int C_LOCAL = 0xFF0F9D58;
    static final int C_PLAY = 0xFFF59E0B;
    static final int C_SKIN = 0xFF7C5CFC;

    static int dp(Context ctx, float v) {
        float d = 1f;
        try {
            d = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            // 用默认值
        }
        return (int) (v * d + 0.5f);
    }

    static int statusBarPx(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            // 继续往下
        }
        return dp(ctx, 24);
    }

    /**
     * 底部系统栏高度（导航栏 / 手势条）。
     *
     * targetSdk 35（Android 15 起强制 edge-to-edge）时窗口会画到导航栏下面，
     * 页面只留固定 26dp 会在三键导航的设备上被挡住最后一行 ⇒ 这里把系统栏高度补上。
     * 手势导航下该 dimen 通常只有 ~24dp，取不到就按 24dp 兜底。
     */
    static int navBarPx(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int h = ctx.getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return dp(ctx, 24);
    }

    /**
     * 关掉系统的「强制深色」（Android 10+ 的 force dark）。
     *
     * 面板是自己画的浅色界面，代码里没写深色配色；系统在深色模式下会把浅色底强制反转，
     * 我们写死的文字色不跟着转 ⇒ 深色设备上会出现「浅字 + 浅底」。这一句让界面保持原色。
     */
    static void noForceDark(android.app.Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && a != null && a.getWindow() != null) {
                a.getWindow().getDecorView().setForceDarkAllowed(false);
            }
        } catch (Throwable ignored) {
            // 老系统没有这个 API，忽略
        }
    }

    /** 面板是 Dialog 不是 Activity，同一个开关要按 Window 关一次。 */
    static void noForceDark(android.view.Window win) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && win != null) {
                win.getDecorView().setForceDarkAllowed(false);
            }
        } catch (Throwable ignored) {
            // 老系统忽略
        }
    }

    static GradientDrawable round(Context ctx, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static GradientDrawable roundStroke(Context ctx, int color, float radiusDp,
                                        int strokeColor, float strokeDp) {
        GradientDrawable d = round(ctx, color, radiusDp);
        d.setStroke(Math.max(1, dp(ctx, strokeDp)), strokeColor);
        return d;
    }

    static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ── 结构 ────────────────────────────────────────────────────────────────

    /** 页面根：浅灰底 + 让开状态栏。 */
    static LinearLayout page(Context ctx) {
        LinearLayout root = column(ctx);
        root.setBackgroundColor(BG);
        int pad = dp(ctx, 16);
        root.setPadding(pad, dp(ctx, 16) + statusBarPx(ctx), pad,
                dp(ctx, 26) + navBarPx(ctx));
        return root;
    }

    /** 顶部渐变标题卡（不带账号小部件）。 */
    static LinearLayout hero(Context ctx, String title, String subtitle, String right) {
        return hero(ctx, title, subtitle, right, null, null);
    }

    /**
     * 顶部渐变标题卡：模块名 + 副标题 + 可选的右侧文字，
     * 右上角还能挂一个「当前登录微信」的小部件（圆头像 + 昵称）。
     */
    static LinearLayout hero(Context ctx, String title, String subtitle, String right,
                             android.graphics.Bitmap avatar, String accountName) {
        return hero(ctx, title, subtitle, right, avatar, accountName, null);
    }

    /** 带关闭按钮的版本：面板是全屏 Dialog，右上角给一个明确的出口。 */
    static LinearLayout hero(Context ctx, String title, String subtitle, String right,
                             android.graphics.Bitmap avatar, String accountName,
                             final Runnable onClose) {
        LinearLayout box = column(ctx);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, ACCENT_DEEP});
        bg.setCornerRadius(dp(ctx, 16));
        box.setBackground(bg);
        int pad = dp(ctx, 16);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        box.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        t.setTextColor(Color.WHITE);
        t.getPaint().setFakeBoldText(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(t);

        // 右上角账号小部件：圆头像 + 昵称（拿不到就整块不显示，版本号在下一行）
        if (avatar != null && accountName != null && !accountName.isEmpty()) {
            top.addView(accountChip(ctx, avatar, accountName));
        }
        if (onClose != null) {
            TextView x = new TextView(ctx);
            x.setText("\u2715");
            x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            x.setTextColor(0xE6FFFFFF);
            int xpad = dp(ctx, 8);
            x.setPadding(xpad, xpad, xpad, xpad);
            x.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClose.run();
                }
            });
            top.addView(x);
        }
        box.addView(top);

        if (subtitle != null && !subtitle.isEmpty()) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.BOTTOM);
            line.setPadding(0, dp(ctx, 6), 0, 0);

            TextView s = new TextView(ctx);
            s.setText(subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s.setTextColor(0xD9FFFFFF);
            s.setLineSpacing(dp(ctx, 3), 1f);
            s.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(s);

            if (right != null && !right.isEmpty()) {
                TextView r = new TextView(ctx);
                r.setText(right);
                r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                r.setTextColor(0xCCFFFFFF);
                line.addView(r);
            }
            box.addView(line);
        }
        return box;
    }

    /** 账号小部件：圆头像 + 昵称，白字，右上角。 */
    private static LinearLayout accountChip(Context ctx, android.graphics.Bitmap avatar,
                                            String name) {
        LinearLayout chip = new LinearLayout(ctx);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);

        int size = dp(ctx, 32);
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        android.graphics.Bitmap round = WxAccount.circle(avatar, size);
        if (round != null) {
            iv.setImageBitmap(round);
        } else {
            iv.setImageBitmap(avatar);
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
        ilp.rightMargin = dp(ctx, 7);
        chip.addView(iv, ilp);

        TextView tv = new TextView(ctx);
        String shortName = name.length() > 10 ? name.substring(0, 10) + "…" : name;
        tv.setText(shortName);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(Color.WHITE);
        tv.getPaint().setFakeBoldText(true);
        chip.addView(tv);
        return chip;
    }

    /** 白色圆角卡片。 */
    static LinearLayout card(Context ctx) {
        LinearLayout c = column(ctx);
        c.setBackground(round(ctx, CARD, 14));
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                c.setElevation(dp(ctx, 1));
            } catch (Throwable ignored) {
                // 忽略
            }
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        c.setLayoutParams(lp);
        return c;
    }

    /** 卡片里的小分组标题：左侧一根强调色竖条 + 彩色文字。 */
    static void section(Context ctx, LinearLayout card, String text, int color) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int top = dp(ctx, 14);
        row.setPadding(dp(ctx, 14), top, dp(ctx, 14), dp(ctx, 4));

        View bar = new View(ctx);
        bar.setBackground(round(ctx, color, 2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 13));
        blp.rightMargin = dp(ctx, 7);
        row.addView(bar, blp);

        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        t.setTextColor(color);
        t.getPaint().setFakeBoldText(true);
        row.addView(t);

        card.addView(row);
    }

    static TextView note(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(SUB);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        tv.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 8));
        return tv;
    }

    /** 卡片下沿的一条浅色分隔线。 */
    static void divider(Context ctx, LinearLayout card) {
        View v = new View(ctx);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.7f)));
        lp.leftMargin = dp(ctx, 14);
        lp.rightMargin = dp(ctx, 14);
        card.addView(v, lp);
    }

    // ── 行 ──────────────────────────────────────────────────────────────────

    /** 开关行：标题 + 说明 + 着色的开关。 */
    static Switch switchRow(Context ctx, LinearLayout card, String label, String desc,
                            boolean checked, int accent, final Toggle toggle) {
        LinearLayout row = row(ctx);
        LinearLayout texts = texts(ctx, label, desc);
        row.addView(texts);

        final Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        tintSwitch(sw, accent);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                try {
                    toggle.on(on);
                } catch (Throwable ignored) {
                    // 设置失败不影响界面
                }
            }
        });
        row.addView(sw);
        card.addView(row);
        divider(ctx, card);
        return sw;
    }

    /** 开关跟着分组强调色走（系统默认是绿色，跟我们的配色不搭）。 */
    static void tintSwitch(Switch sw, int accent) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            sw.setThumbTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}},
                    new int[]{color(accent), color(0xFFFFFFFF)}));
            sw.setTrackTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}},
                    new int[]{color(alpha(accent, 0.45f)), color(0xFFD8DBE2)}));
        } catch (Throwable ignored) {
            // 主题不支持就算了
        }
    }

    /** 可点行：标题 + 当前值 + 右箭头。 */
    static LinearLayout actionRow(Context ctx, LinearLayout card, String label, String value,
                                  int accent, final Runnable action) {
        LinearLayout row = row(ctx);
        row.addView(texts(ctx, label, value));

        TextView arrow = new TextView(ctx);
        arrow.setText("\u203A");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        arrow.setTextColor(0xFFC2C6D0);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    action.run();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        });
        card.addView(row);
        divider(ctx, card);
        return row;
    }

    static void infoRow(Context ctx, LinearLayout card, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        tv.setPadding(dp(ctx, 14), dp(ctx, 2), dp(ctx, 14), dp(ctx, 12));
        card.addView(tv);
    }

    private static LinearLayout row(Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11));
        return row;
    }

    private static LinearLayout texts(Context ctx, String label, String desc) {
        LinearLayout texts = column(ctx);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(TEXT);
        texts.addView(t);

        if (desc != null && !desc.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(desc);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            d.setTextColor(SUB);
            d.setLineSpacing(dp(ctx, 2), 1f);
            d.setPadding(0, dp(ctx, 2), dp(ctx, 6), 0);
            texts.addView(d);
        }
        return texts;
    }

    // ── 颜色小工具 ──────────────────────────────────────────────────────────

    static int color(int argb) {
        return argb;
    }

    static int alpha(int argb, float f) {
        int a = (int) (((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 状态圆点 + 文字，用于适配状态。 */
    static TextView statusLine(Context ctx, boolean ok, String name, String detail) {
        TextView tv = new TextView(ctx);
        tv.setText((ok ? "\u25CF " : "\u25CB ") + name + "  " + detail);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(ok ? OK : WARN);
        tv.setLineSpacing(dp(ctx, 2), 1f);
        tv.setPadding(dp(ctx, 14), dp(ctx, 3), dp(ctx, 14), dp(ctx, 3));
        return tv;
    }

    interface Toggle {
        void on(boolean value);
    }

    private Ui() {}
}
