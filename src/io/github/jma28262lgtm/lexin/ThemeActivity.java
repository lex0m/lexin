package io.github.jma28262lgtm.lexin;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 外观页：三个功能各自带参数。 */
public class ThemeActivity extends Activity {

    /** Preset choices keep this screen free of sliders and their edge cases. */
    private static final int[] ALPHAS = {255, 220, 180, 128, 90, 50};
    private static final String[] ALPHA_LABELS = {"100%", "86%", "70%", "50%", "35%", "20%"};

    private static final int[] SCALES = {50, 75, 100, 125, 150, 200};
    private static final String[] SCALE_LABELS = {"50%", "75%", "100%", "125%", "150%", "200%"};

    private static final int[] RADII = {4, 8, 12, 16, 24, 32};
    private static final String[] RADIUS_LABELS = {"4dp", "8dp", "12dp", "16dp", "24dp", "32dp"};

    private static final int[] BLURS = {5, 10, 20, 30, 45, 60};

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Storage.init(getApplicationContext());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F6F6F8"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22) + statusBarPx(), dp(18), dp(28));
        scroll.addView(root);

        root.addView(title("界面美化"));
        root.addView(note("三项功能，全部只在本机渲染。改完重进界面即可看到效果。"));

        // ── 总开关与背景 ──
        LinearLayout g0 = card(root);
        addSwitch(g0, Storage.K_THEME, "启用美化", "总开关");

        LinearLayout g1 = card(root);
        addAction(g1, "全屏背景", bgLabel(), new Runnable() {
            @Override
            public void run() {
                ImagePicker.pick(ThemeActivity.this, Storage.K_BG);
            }
        });
        addAction(g1, "背景透明度", alphaLabel(), new Runnable() {
            @Override
            public void run() {
                choose("背景透明度", ALPHA_LABELS, indexOf(Storage.num(Beautify.K_BG_ALPHA, 200), ALPHAS),
                        new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_BG_ALPHA, ALPHAS[i]);
                                Storage.setOn(Storage.K_THEME, true);
                                recreate();
                            }
                        });
            }
        });
        addAction(g1, "背景大小比例", scaleLabel(), new Runnable() {
            @Override
            public void run() {
                choose("背景大小比例", SCALE_LABELS, indexOf(Storage.num(Beautify.K_BG_SCALE, 100), SCALES),
                        new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_BG_SCALE, SCALES[i]);
                                Storage.setOn(Storage.K_THEME, true);
                                recreate();
                            }
                        });
            }
        });
        addAction(g1, "清除背景", "恢复微信默认", new Runnable() {
            @Override
            public void run() {
                Storage.setStr(Beautify.K_BG, "");
                toast("已清除背景");
                recreate();
            }
        });

        // ── corners ──
        LinearLayout g2 = card(root);
        addSwitch(g2, Beautify.K_CORNER_ON, "UI 圆角化", "给界面容器加圆角");
        addAction(g2, "圆角半径", radiusLabel(), new Runnable() {
            @Override
            public void run() {
                choose("圆角半径", RADIUS_LABELS, indexOf(Storage.num(Beautify.K_CORNER_DP, 12), RADII),
                        new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_CORNER_DP, RADII[i]);
                                Storage.setOn(Beautify.K_CORNER_ON, true);
                                Storage.setOn(Storage.K_THEME, true);
                                recreate();
                            }
                        });
            }
        });

        // ── 毛玻璃 ──
        LinearLayout g3 = card(root);
        addSwitch(g3, Beautify.K_BLUR_ON, "毛玻璃", "GPU 模糊（Android 12+）");
        addAction(g3, "模糊强度", blurLabel(), new Runnable() {
            @Override
            public void run() {
                choose("模糊强度", new String[]{"5", "10", "20", "30", "45", "60"},
                        indexOf(Storage.num(Beautify.K_BLUR_RADIUS, 20), BLURS), new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_BLUR_RADIUS, BLURS[i]);
                                Storage.setOn(Beautify.K_BLUR_ON, true);
                                Storage.setOn(Storage.K_THEME, true);
                                recreate();
                            }
                        });
            }
        });

        root.addView(note("圆角只作用于有背景色的容器，避免抹掉图标和气泡。"));
        setContentView(scroll);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private interface Pick { void on(int index); }

    private void choose(String title, String[] labels, int checked, final Pick pick) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, Math.max(0, checked),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    pick.on(which);
                                    d.dismiss();
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {
            toast("设置失败");
        }
    }

    private static int indexOf(int value, int[] pool) {
        for (int i = 0; i < pool.length; i++) if (pool[i] == value) return i;
        return 0;
    }

    private String bgLabel() {
        String p = Storage.str(Beautify.K_BG, "");
        if (p == null || p.isEmpty()) return "未设置（点击选择图片）";
        int i = p.lastIndexOf('/');
        return "已设置：" + (i >= 0 ? p.substring(i + 1) : p);
    }

    private String alphaLabel() {
        int v = Storage.num(Beautify.K_BG_ALPHA, 200);
        for (int i = 0; i < ALPHAS.length; i++) if (ALPHAS[i] == v) return ALPHA_LABELS[i];
        return v + "/255";
    }

    private String scaleLabel() {
        int v = Storage.num(Beautify.K_BG_SCALE, 100);
        return v + "%";
    }

    private String radiusLabel() {
        return Storage.num(Beautify.K_CORNER_DP, 12) + "dp";
    }

    private String blurLabel() {
        return "半径 " + Storage.num(Beautify.K_BLUR_RADIUS, 20);
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ── view factories ───────────────────────────────────────────────────────

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        tv.setTextColor(Color.parseColor("#1C1C1E"));
        tv.setPadding(dp(4), 0, 0, dp(6));
        return tv;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(Color.parseColor("#8A8A8E"));
        tv.setPadding(dp(4), dp(4), dp(4), dp(10));
        return tv;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private void addSwitch(LinearLayout card, final String key, String label, String desc) {
        LinearLayout row = plainRow(label, desc);
        final Switch sw = new Switch(this);
        sw.setChecked(Storage.on(key, false));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Storage.setOn(key, on);
                if (on) Storage.setOn(Storage.K_THEME, true);
            }
        });
        row.addView(sw);
        card.addView(row);
        divider(card);
    }

    private void addAction(LinearLayout card, String label, String desc, final Runnable action) {
        LinearLayout row = plainRow(label, desc);
        TextView arrow = new TextView(this);
        arrow.setText("\u203A");
        arrow.setTextSize(18f);
        arrow.setTextColor(Color.parseColor("#C7C7CC"));
        row.addView(arrow);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        card.addView(row);
        divider(card);
    }

    private LinearLayout plainRow(String label, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(Color.parseColor("#1C1C1E"));
        texts.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setTextColor(Color.parseColor("#8A8A8E"));
        texts.addView(d);

        row.addView(texts);
        return row;
    }

    private void divider(LinearLayout card) {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#EFEFF4"));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        card.addView(v);
    }

    private int statusBarPx() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            // 继续往下
        }
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
