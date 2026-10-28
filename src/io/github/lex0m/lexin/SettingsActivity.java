package io.github.lex0m.lexin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/**
 * 模块自己的设置页（跑在模块进程里）。
 *
 * 主入口其实是微信里的隐藏长按（见 Settings），这一页是给「从外部打开」准备的，
 * 版式与面板保持一致：渐变标题卡 → 每日一言 → 交流群 → 适配状态 → 功能分组。
 */
public class SettingsActivity extends Activity {

    private static final String GROUP_ID = "186737276";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.noForceDark(this);          // 深色模式下别把浅色界面强制反转
        Prefs.init(getApplicationContext());
        Disclaimer.gate(this, new Runnable() {
            @Override
            public void run() {
                build();
            }
        });
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout root = Ui.page(this);
        scroll.addView(root);

        root.addView(Ui.hero(this, "乐信", "欢迎使用乐信 · 微信界面美化 · 本地功能增强",
                "v" + version(), WxAccount.avatar(this), WxAccount.nickname(this)));

        LinearLayout quote = Ui.card(this);
        Ui.section(this, quote, "每日一言", Ui.GOLD);
        final TextView qText = new TextView(this);
        qText.setText("正在获取今天的句子…");
        qText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        qText.setTextColor(Ui.TEXT);
        qText.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 2));
        quote.addView(qText);
        final TextView qFrom = new TextView(this);
        qFrom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        qFrom.setTextColor(Ui.SUB);
        qFrom.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 12));
        quote.addView(qFrom);
        root.addView(quote);

        LinearLayout panelCard = Ui.card(this);
        Ui.section(this, panelCard, "微信悬浮面板", Ui.ACCENT);
        Ui.actionRow(this, panelCard, "打开微信面板", "点一下 → 切回微信自动弹出",
                Ui.ACCENT, new Runnable() {
                    @Override
                    public void run() {
                        openPanel();
                    }
                });
        Ui.infoRow(this, panelCard,
                "面板跑在微信进程里（长按菜单、步数、批量标签只能在那边操作）。"
                        + "也可以从微信进：我 → 设置 → 更多 → 关怀模式 → 长按「开启」。", Ui.SUB);
        root.addView(panelCard);

        DailyQuote.load(this, new DailyQuote.Cb() {
            @Override
            public void on(String text, String from, boolean fresh) {
                qText.setText("\u201C" + text + "\u201D");
                qFrom.setText("—— " + from + (fresh ? "" : "（本地）"));
            }
        });

        LinearLayout group = Ui.card(this);
        Ui.section(this, group, "交流群", Ui.ACCENT);
        Ui.infoRow(this, group, "欢迎加入交流群讨论（群号：" + GROUP_ID + "）", Ui.ACCENT);
        root.addView(group);

        LinearLayout g1 = Ui.card(this);
        Ui.section(this, g1, "聊天行为", Ui.C_CHAT);
        sw(g1, Prefs.K_RECALL, "消息防撤回", "保留对方撤回的消息，并在气泡旁边标注方向");
        sw(g1, Prefs.K_MOMENTS, "朋友圈保留", "对方删掉的评论与动态，本机留副本");
        sw(g1, Prefs.K_ADS, "屏蔽广告位", "关闭开屏、小程序与朋友圈信息流广告");
        root.addView(g1);

        LinearLayout g2 = Ui.card(this);
        Ui.section(this, g2, "本地限制与媒体", Ui.C_LOCAL);
        sw(g2, Prefs.K_LIMITS, "放宽本地限制", "放宽表情包数量、消息多选、状态字数上限");
        sw(g2, Prefs.K_MEDIA, "自动查看原图", "浏览图片时自动切到原图，无需手动点");
        sw(g2, Prefs.K_DICE, "骰子点数自选", "长按表情面板的骰子可指定点数");
        sw(g2, Prefs.K_FIST, "猜拳结果自选", "长按表情面板的猜拳可指定出拳");
        root.addView(g2);

        LinearLayout g5 = Ui.card(this);
        Ui.section(this, g5, "转发增强", Ui.C_CHAT);
        sw(g5, Prefs.K_FWD_MENU, "长按菜单加转发入口",
                "不改微信任何判定，只在长按菜单里多一项「转发（乐信）」");
        sw(g5, Prefs.K_FWD_SELECT, "解除多选上限", "放宽多选消息的数量限制");
        Ui.infoRow(this, g5, "原则：不伪造消息、不经手上传，只放行或调用微信自己的链路。", Ui.SUB);
        root.addView(g5);

        LinearLayout g6 = Ui.card(this);
        Ui.section(this, g6, "语音 · 底栏 · 标签", Ui.C_PLAY);
        sw(g6, Prefs.K_VOICE_TEXT, "自动语音转文字",
                "收到语音自动调微信官方转文字（不接第三方识别）");
        sw(g6, Prefs.K_FLOAT_FOOTER, "悬浮聊天底栏", "聊天输入栏改成悬浮卡片（圆角 + 阴影 + 侧边距）");
        sw(g6, Prefs.K_HOME_FLOAT_TAB, "主页悬浮底栏", "主页底部 TabBar 改成悬浮圆角卡片");
        sw(g6, Prefs.K_BATCH_LABEL, "批量分类标签", "给多个好友批量打标签（走微信官方接口）");
        Ui.infoRow(this, g6, "批量打标签的「选标签 → 选好友」流程在微信面板里操作"
                + "（App 进程调不到微信的接口）。", Ui.SUB);
        root.addView(g6);

        LinearLayout g4 = Ui.card(this);
        Ui.section(this, g4, "系统与隐私", Ui.C_LOCAL);
        sw(g4, Prefs.K_SCAN, "解除扫码限制", "相册、长按图片识别二维码不再受限");
        sw(g4, Steps.K_ON, "运动步数修改", "传感器层改写步数，微信侧无改动；当前：" + Steps.label());
        Ui.actionRow(this, g4, "诊断日志",
                (Storage.diag() ? "已开启" : "已关闭") + "（改完要重进微信才生效）",
                Ui.OK, new Runnable() {
                    @Override
                    public void run() {
                        boolean on = !Storage.diag();
                        Storage.setOn(Storage.K_DIAG, on);
                        Log.setRuntime(on);
                        toast(on ? "诊断日志已打开：复现一次问题后到 LSPosed 日志里看"
                                : "诊断日志已关闭（发布版默认不写任何日志）");
                    }
                });
        root.addView(g4);

        LinearLayout g3 = Ui.card(this);
        Ui.section(this, g3, "界面美化", Ui.C_SKIN);
        Ui.actionRow(this, g3, "主题与背景", "透明度、裁剪、圆角、模糊（独立界面）",
                Ui.C_SKIN, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent i = new Intent();
                            i.setClassName("io.github.lex0m.lexin",
                                    "io.github.lex0m.lexin.ThemeActivity");
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Throwable ignored) {
                            // 界面不可用
                        }
                    }
                });
        root.addView(g3);

        LinearLayout st = Ui.card(this);
        Ui.section(this, st, "适配状态", Ui.OK);
        Map<String, Boolean> all = Status.all();
        if (all.isEmpty()) {
            // 跨进程：面板在模块 App 进程里，Status 是微信进程的内存 Map。
            // 读微信进程推上来的状态镜像（BgProvider.call 写的 status.txt）。
            String pushed = BgProvider.readStatus(this);
            if (pushed != null && !pushed.isEmpty()) {
                for (String line : pushed.split("\n")) {
                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) continue;
                    all.put(line.substring(0, eq), "1".equals(line.substring(eq + 1)));
                }
            }
        }
        if (all.isEmpty()) {
            Ui.infoRow(this, st, "还没有状态：进微信一次让它装上，然后回这里看。", Ui.SUB);
        } else {
            for (Map.Entry<String, Boolean> e : all.entrySet()) {
                st.addView(Ui.statusLine(this, e.getValue(), e.getKey(),
                        e.getValue() ? "已生效" : "未适配（自动跳过）"));
            }
        }
        root.addView(st);

        root.addView(Ui.note(this, "仅供个人学习研究，禁止在国内网络平台传播或售卖。"));

        setContentView(scroll);
    }

    private void sw(LinearLayout card, String key, String label, String desc) {
        final String k = key;
        Ui.switchRow(this, card, label, desc, Prefs.on(k, Prefs.def(k)), Ui.C_CHAT, new Ui.Toggle() {
            @Override
            public void on(boolean value) {
                Prefs.setOn(k, value);
            }
        });
    }

    /** 让微信进程把悬浮面板打开（发广播过去，切回微信就能看到）。 */
    private void openPanel() {
        try {
            android.content.Intent i = new android.content.Intent(Bridge.ACTION);
            i.setPackage(Entry.HOST);
            i.putExtra("open_from", System.currentTimeMillis());
            sendBroadcast(i);
            toast("已发送，切回微信即可看到面板");
        } catch (Throwable t) {
            toast("发送失败：请从微信里长按关怀模式「开启」进入");
        }
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return Features.MODULE_VERSION;
        }
    }
}
