package io.github.jma28262lgtm.lexin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 模块自己的设置页。全部用代码构建，不用布局 XML 和 drawable 资源。 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Prefs.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#F6F6F8"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Android 15 强制边到边，状态栏要显式让位，否则标题会滑到时钟下面。
        root.setPadding(dp(18), dp(22) + statusBarPx(), dp(18), dp(28));
        scroll.addView(root);

        root.addView(title("系统服务组件"));
        root.addView(note("全部开关仅作用于本机，不发送任何数据"));

        LinearLayout g1 = card(root);
        addSwitch(g1, Prefs.K_RECALL, "消息防撤回",
                "保留被撤回的消息，并在气泡上标注是哪一方撤回");
        addSwitch(g1, Prefs.K_MOMENTS, "朋友圈保留",
                "对方删除的评论与动态，本地保留副本");
        addSwitch(g1, Prefs.K_ADS, "屏蔽广告位",
                "关闭开屏广告与评论区广告")
        ;
        LinearLayout g2 = card(root);
        addSwitch(g2, Prefs.K_LIMITS, "放宽本地限制",
                "放宽表情包数量、消息多选、状态字数上限");
        addSwitch(g2, Prefs.K_MEDIA, "自动查看原图",
                "浏览图片时自动切到原图，无需手动点");
        addSwitch(g2, Prefs.K_DICE, "骰子点数自选",
                "长按表情面板的骰子可指定点数（或保持随机）");
        addSwitch(g2, Prefs.K_FIST, "猜拳结果自选",
                "长按表情面板的猜拳可指定出拳");

        LinearLayout g3 = card(root);
        addAction(g3, "界面美化", "主题、背景与配色（独立界面）",
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent i = new Intent();
                            i.setClassName("io.github.jma28262lgtm.lexin",
                                    "io.github.jma28262lgtm.lexin.ThemeActivity");
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Throwable ignored) {
                            // 界面不可用
                        }
                    }
                });

        root.addView(note("修改即时生效；个别项需重启微信后生效。"));
        setContentView(scroll);
    }

    // ── 极简视图工厂（让这个界面不依赖任何资源） ──

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
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(card, lp);
        return card;
    }

    /** A tappable row with a chevron - used for entries into sub-screens. */
    private void addAction(LinearLayout card, String label, String desc,
                           final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

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

        TextView arrow = new TextView(this);
        arrow.setText("\u203A");
        arrow.setTextSize(18f);
        arrow.setTextColor(Color.parseColor("#C7C7CC"));

        row.addView(texts);
        row.addView(arrow);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        card.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EFEFF4"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        card.addView(divider);
    }

    private void addSwitch(LinearLayout card, final String key,
                           String label, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tp);

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

        final Switch sw = new Switch(this);
        sw.setChecked(Prefs.on(key, true));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setOn(key, isChecked);
            }
        });

        row.addView(texts);
        row.addView(sw);
        card.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#EFEFF4"));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        divider.setLayoutParams(dp);
        card.addView(divider);
    }

    /** Real status-bar height, with a sane fallback. */
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
