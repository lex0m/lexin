package io.github.lex0m.lexin;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/**
 * 设置面板：跑在宿主进程内的全屏对话框（跨进程读写配置在 Android 15 上走不通）。
 *
 * 版式：渐变标题卡 → 每日一言 → 交流群 → 适配状态 → 各功能分组卡片。
 * 全部用代码画，配色和圆角统一走 Ui。
 */
final class SettingsPanel {

    private static final String T = "svc-ui: ";
    private static final String GROUP_ID = "186737276";

    private static volatile boolean showing = false;
    private static volatile Dialog current;

    static boolean isShowing() {
        return showing;
    }

    static void show(final Activity host) {
        if (host == null) return;
        if (showing) {
            // 标志位卡住（面板被系统收掉、页面被杀）会让入口"点了没反应"，所以自检一次
            Dialog d = current;
            if (d != null && d.isShowing()) return;
            showing = false;
        }
        try {
            Prefs.init(host);
        } catch (Throwable ignored) {
            // 读不到配置也照样把面板打开
        }
        // 首次打开：强制看完免责声明与传播限制（读过就不再出现）
        Disclaimer.gate(host, new Runnable() {
            @Override
            public void run() {
                try {
                    log("gate passed, building panel");
                    build(host);
                    log("panel built, showing=" + showing);
                } catch (Throwable t) {
                    showing = false;
                    String why = "panel failed: " + t.getClass().getSimpleName()
                            + " " + t.getMessage();
                    Storage.diag(why);
                    log(why);
                }
            }
        });
    }

    static void close() {
        try {
            Dialog d = current;
            if (d != null) d.dismiss();
        } catch (Throwable ignored) {
            // 忽略
        }
        showing = false;
    }

    private static void build(final Activity host) {
        showing = true;

        final Dialog d = new Dialog(host,
                android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Ui.BG));
        }

        ScrollView scroll = new ScrollView(host);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);

        LinearLayout root = Ui.page(host);
        scroll.addView(root);

        // ── 顶栏 ──
        LinearLayout hero = Ui.hero(host, "乐信",
                "欢迎使用乐信 · " + Anchors.summary(), "v" + version(host),
                WxAccount.avatar(host), WxAccount.nickname(host),
                new Runnable() {
                    @Override
                    public void run() {
                        close();
                    }
                });
        root.addView(hero);

        // ── 每日一言 ──
        LinearLayout quote = Ui.card(host);
        Ui.section(host, quote, "每日一言", Ui.GOLD);
        final TextView qText = new TextView(host);
        qText.setText("正在获取今天的句子…");
        qText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        qText.setTextColor(Ui.TEXT);
        qText.setLineSpacing(Ui.dp(host, 4), 1.08f);
        qText.setPadding(Ui.dp(host, 14), Ui.dp(host, 4), Ui.dp(host, 14), Ui.dp(host, 2));
        quote.addView(qText);
        final TextView qFrom = new TextView(host);
        qFrom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        qFrom.setTextColor(Ui.SUB);
        qFrom.setPadding(Ui.dp(host, 14), 0, Ui.dp(host, 14), Ui.dp(host, 12));
        quote.addView(qFrom);
        root.addView(quote);
        DailyQuote.load(host, new DailyQuote.Cb() {
            @Override
            public void on(final String text, final String from, boolean fresh) {
                try {
                    qText.setText("\u201C" + text + "\u201D");
                    qFrom.setText("—— " + from + (fresh ? "" : "（本地）"));
                } catch (Throwable ignored) {
                    // 界面没了就算了
                }
            }
        });

        // ── 交流群 ──
        LinearLayout group = Ui.card(host);
        Ui.section(host, group, "交流群", Ui.ACCENT);
        final TextView g = new TextView(host);
        g.setText("欢迎加入交流群讨论（群号：" + GROUP_ID + "）");
        g.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        g.setTextColor(Ui.ACCENT);
        g.getPaint().setFakeBoldText(true);
        g.setPadding(Ui.dp(host, 14), Ui.dp(host, 4), Ui.dp(host, 14), Ui.dp(host, 4));
        g.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) host.getSystemService(
                                    Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("群号", GROUP_ID));
                    toast(host, "群号已复制：" + GROUP_ID);
                } catch (Throwable ignored) {
                    toast(host, "群号：" + GROUP_ID);
                }
            }
        });
        group.addView(g);
        Ui.infoRow(host, group, "点一下可以复制群号；有问题或建议欢迎进群反馈。", Ui.SUB);
        root.addView(group);

        // ── 适配状态 ──
        LinearLayout st = Ui.card(host);
        Ui.section(host, st, "当前微信适配状态", Ui.OK);
        for (Map.Entry<String, Boolean> e : Status.all().entrySet()) {
            st.addView(Ui.statusLine(host, e.getValue(), e.getKey(),
                    e.getValue() ? "已生效" : "未适配（该功能自动跳过）"));
        }
        // actionRow 内部已经把这一行加进卡片了，这里不要再 addView 一次
        Ui.actionRow(host, st, "独立设置页",
                "打开乐信自己的设置页（不依赖微信）", Ui.OK, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            android.content.Intent i = new android.content.Intent();
                            i.setClassName("io.github.lex0m.lexin",
                                    "io.github.lex0m.lexin.SettingsActivity");
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            host.startActivity(i);
                        } catch (Throwable ignored) {
                            toast(host, "打不开，试试从 LSPosed 启动");
                        }
                    }
                });
        Ui.actionRow(host, st, "重新自动适配",
                "清掉学习缓存，重新扫一遍微信 dex", Ui.OK, new Runnable() {
                    @Override
                    public void run() {
                        Anchors.forget();
                        AutoScan.schedule();
                        toast(host, "已开始重新适配，稍后重启微信生效");
                    }
                });
        root.addView(st);

        // ── 功能分组 ──
        LinearLayout g1 = Ui.card(host);
        Ui.section(host, g1, "聊天行为", Ui.C_CHAT);
        addSwitch(host, g1, Prefs.K_RECALL, "消息防撤回",
                "保留对方撤回的消息，并在气泡旁边标注方向", Ui.C_CHAT);
        addSwitch(host, g1, Prefs.K_MOMENTS, "朋友圈保留",
                "对方删掉的评论与动态，本机留副本", Ui.C_CHAT);
        addSwitch(host, g1, Prefs.K_ADS, "屏蔽广告位",
                "关闭开屏、小程序与朋友圈信息流广告", Ui.C_CHAT);
        root.addView(g1);

        LinearLayout g2 = Ui.card(host);
        Ui.section(host, g2, "本地限制与媒体", Ui.C_LOCAL);
        addSwitch(host, g2, Prefs.K_LIMITS, "放宽本地限制",
                "放宽表情包数量、消息多选、状态字数上限", Ui.C_LOCAL);
        addSwitch(host, g2, Prefs.K_MEDIA, "自动查看原图",
                "浏览图片时自动切到原图，无需手动点", Ui.C_LOCAL);
        root.addView(g2);

        LinearLayout g3 = Ui.card(host);
        Ui.section(host, g3, "表情玩法", Ui.C_PLAY);
        addSwitch(host, g3, Prefs.K_DICE, "骰子点数自选",
                "长按表情面板的骰子可指定点数（或保持随机）", Ui.C_PLAY);
        addSwitch(host, g3, Prefs.K_FIST, "猜拳结果自选",
                "长按表情面板的猜拳可指定出拳", Ui.C_PLAY);
        root.addView(g3);

        // ── 系统与隐私 ──
        LinearLayout g6 = Ui.card(host);
        Ui.section(host, g6, "系统与隐私", Ui.C_LOCAL);
        addSwitch(host, g6, Prefs.K_SCAN, "解除扫码限制",
                "相册、长按图片识别二维码不再受限（按相机场景处理）", Ui.C_LOCAL);
        addSwitch(host, g6, Steps.K_ON, "运动步数修改",
                "传感器层改写，微信侧零改动；当前：" + Steps.label(), Ui.C_LOCAL);
        addAction(host, g6, "步数模式", STEP_MODE_LABELS[indexOf(Storage.num(Steps.K_MODE, 2), STEP_MODES)],
                Ui.C_LOCAL, new Runnable() {
                    @Override
                    public void run() {
                        choose(host, "步数模式", STEP_MODE_LABELS,
                                indexOf(Storage.num(Steps.K_MODE, 2), STEP_MODES), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Steps.K_MODE, STEP_MODES[i]);
                                        Storage.setOn(Steps.K_ON, true);
                                        toast(host, "已切换：" + STEP_MODE_LABELS[i] + "（重进微信生效）");
                                    }
                                });
                    }
                });
        addAction(host, g6, "步数参数", stepParamLabel(), Ui.C_LOCAL, new Runnable() {
            @Override
            public void run() {
                int mode = Storage.num(Steps.K_MODE, 2);
                if (mode == 1) {
                    final int[] vals = {12, 15, 20, 30, 50};
                    final String[] labels = {"1.2 倍", "1.5 倍", "2 倍", "3 倍", "5 倍"};
                    choose(host, "步数倍率", labels,
                            indexOf(Storage.num(Steps.K_MUL, 15), vals), new Pick() {
                                @Override
                                public void on(int i) {
                                    Storage.setNum(Steps.K_MUL, vals[i]);
                                    Storage.setOn(Steps.K_ON, true);
                                    toast(host, "倍率 " + labels[i] + "（重进微信生效）");
                                }
                            });
                } else if (mode == 3) {
                    final int[] vals = {6000, 8000, 10000, 12000, 15000, 20000, 30000};
                    final String[] labels = {"6000", "8000", "10000", "12000", "15000", "20000", "30000"};
                    choose(host, "今日目标步数", labels,
                            indexOf(Storage.num(Steps.K_TARGET, 12000), vals), new Pick() {
                                @Override
                                public void on(int i) {
                                    Storage.setNum(Steps.K_TARGET, vals[i]);
                                    Storage.setOn(Steps.K_ON, true);
                                    toast(host, "今日目标 " + labels[i] + " 步（重进微信生效）");
                                }
                            });
                } else {
                    final int[] vals = {100, 200, 300, 500, 800, 1200};
                    final String[] labels = {"100", "200", "300", "500", "800", "1200"};
                    choose(host, "每小时增加步数", labels,
                            indexOf(Storage.num(Steps.K_RATE, 200), vals), new Pick() {
                                @Override
                                public void on(int i) {
                                    Storage.setNum(Steps.K_RATE, vals[i]);
                                    Storage.setOn(Steps.K_ON, true);
                                    toast(host, "每小时 +" + labels[i] + " 步（重进微信生效）");
                                }
                            });
                }
            }
        });
        addAction(host, g6, "额外步数上限", Storage.num(Steps.K_CAP, 20000) + " 步", Ui.C_LOCAL,
                new Runnable() {
                    @Override
                    public void run() {
                        final int[] vals = {5000, 10000, 20000, 30000, 50000, 80000};
                        final String[] labels = {"5000", "10000", "20000", "30000", "50000", "80000"};
                        choose(host, "额外步数上限", labels,
                                indexOf(Storage.num(Steps.K_CAP, 20000), vals), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Steps.K_CAP, vals[i]);
                                        toast(host, "上限 " + labels[i] + " 步（重进微信生效）");
                                    }
                                });
                    }
                });
        Ui.infoRow(host, g6, "步数在传感器层改写，微信不碰；"
                + "倍率按真实步数放大，自动增加/指定目标随一天时间平滑增长，"
                + "超过上限就停在上限，避免数字离谱。", Ui.SUB);
        root.addView(g6);

        // ── 外观 ──
        LinearLayout g4 = Ui.card(host);
        Ui.section(host, g4, "界面美化", Ui.C_SKIN);
        addSwitch(host, g4, Prefs.K_THEME, "启用界面美化", "总开关", Ui.C_SKIN);
        addAction(host, g4, "全屏背景", bgLabel(), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                ImagePicker.pick(host, Beautify.K_BG);
            }
        });
        addAction(host, g4, "背景透明度", alphaLabel(), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                choose(host, "背景透明度", ALPHA_LABELS,
                        indexOf(Storage.num(Beautify.K_BG_ALPHA, 200), ALPHAS), new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_BG_ALPHA, ALPHAS[i]);
                                Storage.setOn(Prefs.K_THEME, true);
                                toast(host, "透明度 " + ALPHA_LABELS[i] + "（重进界面生效）");
                            }
                        });
            }
        });
        addAction(host, g4, "背景大小", modeLabel(), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                choose(host, "背景大小", MODE_LABELS,
                        indexOfName(Storage.str(Beautify.K_BG_MODE, "fill"), MODE_KEYS), new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setStr(Beautify.K_BG_MODE, MODE_KEYS[i]);
                                Storage.setOn(Prefs.K_THEME, true);
                                if ("custom".equals(MODE_KEYS[i])) askCustomSize(host);
                                else toast(host, "背景大小 " + MODE_LABELS[i] + "（重进界面生效）");
                            }
                        });
            }
        });
        addAction(host, g4, "自定义尺寸", customLabel(), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                askCustomSize(host);
            }
        });
        addAction(host, g4, "背景裁剪", cropLabel(Beautify.K_BG_CX, Beautify.K_BG_CY,
                Beautify.K_BG_CW, Beautify.K_BG_CH), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                pickCrop(host, Beautify.K_BG_CX, Beautify.K_BG_CY,
                        Beautify.K_BG_CW, Beautify.K_BG_CH);
            }
        });
        addAction(host, g4, "清除背景", "恢复微信默认", Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                try {
                    java.io.File f = new java.io.File(host.getFilesDir(), Beautify.BG_FILE);
                    if (f.exists()) f.delete();
                } catch (Throwable ignored) {
                    // 忽略
                }
                Storage.setStr(Beautify.K_BG, "");
                toast(host, "已清除背景");
                dismissAndReopen(host);
            }
        });
        addSwitch(host, g4, Beautify.K_CORNER_ON, "UI 圆角化", "给界面容器加圆角", Ui.C_SKIN);
        addAction(host, g4, "圆角半径", Storage.num(Beautify.K_CORNER_DP, 12) + "dp", Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        choose(host, "圆角半径", RADIUS_LABELS,
                                indexOf(Storage.num(Beautify.K_CORNER_DP, 12), RADII), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CORNER_DP, RADII[i]);
                                        Storage.setOn(Beautify.K_CORNER_ON, true);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "圆角 " + RADIUS_LABELS[i] + "（重进界面生效）");
                                    }
                                });
                    }
                });
        addSwitch(host, g4, Beautify.K_BLUR_ON, "毛玻璃", "GPU 模糊（Android 12+）", Ui.C_SKIN);
        addAction(host, g4, "模糊强度", "半径 " + Storage.num(Beautify.K_BLUR_RADIUS, 20), Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        final String[] labels = new String[BLURS.length];
                        for (int i = 0; i < BLURS.length; i++) labels[i] = String.valueOf(BLURS[i]);
                        choose(host, "模糊强度", labels,
                                indexOf(Storage.num(Beautify.K_BLUR_RADIUS, 20), BLURS), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_BLUR_RADIUS, BLURS[i]);
                                        Storage.setOn(Beautify.K_BLUR_ON, true);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "模糊强度 " + labels[i] + "（重进界面生效）");
                                    }
                                });
                    }
                });
        root.addView(g4);

        // ── 聊天条卡片化 ──
        LinearLayout g5 = Ui.card(host);
        Ui.section(host, g5, "聊天条卡片化", Ui.C_SKIN);
        addSwitch(host, g5, Beautify.K_CARD_ON, "聊天条卡片化", "缩进留边、圆角、独立背景", Ui.C_SKIN);
        addSwitch(host, g5, Beautify.K_CARD_GLASS, "聊天条玻璃背景",
                "全透明磨砂玻璃（透过聊天条看到背景，文字清晰）", Ui.C_SKIN);
        addAction(host, g5, "聊天条背景图", imgLabel(Beautify.K_CARD_IMG), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                ImagePicker.pick(host, Beautify.K_CARD_IMG);
            }
        });
        addAction(host, g5, "聊天条裁剪", cropLabel(Beautify.K_CARD_CX, Beautify.K_CARD_CY,
                Beautify.K_CARD_CW, Beautify.K_CARD_CH), Ui.C_SKIN, new Runnable() {
            @Override
            public void run() {
                pickCrop(host, Beautify.K_CARD_CX, Beautify.K_CARD_CY,
                        Beautify.K_CARD_CW, Beautify.K_CARD_CH);
            }
        });
        addAction(host, g5, "聊天条透明度", Storage.num(Beautify.K_CARD_ALPHA, 200) + "/255",
                Ui.C_SKIN, new Runnable() {
                    @Override
                    public void run() {
                        choose(host, "聊天条透明度", ALPHA_LABELS,
                                indexOf(Storage.num(Beautify.K_CARD_ALPHA, 200), ALPHAS), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_ALPHA, ALPHAS[i]);
                                        Storage.setOn(Beautify.K_CARD_ON, true);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "聊天条透明度 " + ALPHA_LABELS[i] + "（重进界面生效）");
                                    }
                                });
                    }
                });
        addAction(host, g5, "聊天条圆角", Storage.num(Beautify.K_CARD_CORNER, 16) + "dp", Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        choose(host, "聊天条圆角", RADIUS_LABELS,
                                indexOf(Storage.num(Beautify.K_CARD_CORNER, 16), RADII), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_CORNER, RADII[i]);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "聊天条圆角 " + RADIUS_LABELS[i] + "（重进界面生效）");
                                    }
                                });
                    }
                });
        addAction(host, g5, "左右内缩", Storage.num(Beautify.K_CARD_INSET, 10) + "dp", Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        final int[] vals = {0, 4, 8, 12, 16, 24};
                        final String[] labels = {"0", "4dp", "8dp", "12dp", "16dp", "24dp"};
                        choose(host, "左右内缩", labels,
                                indexOf(Storage.num(Beautify.K_CARD_INSET, 10), vals), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_INSET, vals[i]);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "内缩 " + vals[i] + "dp（重进界面生效）");
                                    }
                                });
                    }
                });
        addAction(host, g5, "卡片高度", Storage.num(Beautify.K_CARD_H, 0) + "dp", Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        final int[] vals = {-24, -16, -8, 0, 8, 16, 24, 32};
                        final String[] labels = {"-24", "-16", "-8", "0", "8", "16", "24", "32"};
                        choose(host, "卡片高度", labels,
                                indexOf(Storage.num(Beautify.K_CARD_H, 0), vals), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_H, vals[i]);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "卡片高度 " + vals[i] + "dp（重进界面生效）");
                                    }
                                });
                    }
                });
        addAction(host, g5, "卡片间隔", Storage.num(Beautify.K_CARD_GAP, 8) + "dp", Ui.C_SKIN,
                new Runnable() {
                    @Override
                    public void run() {
                        final int[] vals = {0, 2, 4, 8, 12, 16, 24};
                        final String[] labels = {"0", "2dp", "4dp", "8dp", "12dp", "16dp", "24dp"};
                        choose(host, "卡片间隔", labels,
                                indexOf(Storage.num(Beautify.K_CARD_GAP, 8), vals), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_GAP, vals[i]);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "卡片间隔 " + vals[i] + "dp（重进界面生效）");
                                    }
                                });
                    }
                });
        addSwitch(host, g5, Beautify.K_CARD_BLUR_ON, "聊天条模糊", "与背景模糊各自独立", Ui.C_SKIN);
        addAction(host, g5, "聊天条模糊强度",
                "半径 " + Storage.num(Beautify.K_CARD_BLUR_R, 12), Ui.C_SKIN, new Runnable() {
                    @Override
                    public void run() {
                        final String[] labels = new String[BLURS.length];
                        for (int i = 0; i < BLURS.length; i++) labels[i] = String.valueOf(BLURS[i]);
                        choose(host, "聊天条模糊强度", labels,
                                indexOf(Storage.num(Beautify.K_CARD_BLUR_R, 12), BLURS), new Pick() {
                                    @Override
                                    public void on(int i) {
                                        Storage.setNum(Beautify.K_CARD_BLUR_R, BLURS[i]);
                                        Storage.setOn(Beautify.K_CARD_BLUR_ON, true);
                                        Storage.setOn(Prefs.K_THEME, true);
                                        toast(host, "聊天条模糊 " + labels[i] + "（重进界面生效）");
                                    }
                                });
                    }
                });
        root.addView(g5);

        // ── 页脚 ──
        LinearLayout about = Ui.card(host);
        Ui.section(host, about, "关于", Ui.SUB);
        Ui.infoRow(host, about, "作者 " + author(host) + " · 版本 " + version(host)
                + " · 微信 " + WxVersion.label(), Ui.TEXT);
        Ui.infoRow(host, about, "本模块不收集、不上传任何数据；所有开关只作用于本机。"
                + "仅供个人学习研究，禁止在国内网络平台传播或售卖。", Ui.SUB);
        root.addView(about);
        root.addView(Ui.note(host, "切换即时生效；标了「重进界面」的项需要退出当前页面再进来。"));

        d.setContentView(scroll);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                showing = false;
                current = null;
            }
        });
        current = d;
        log("dialog show(), view=" + d.getWindow().getDecorView().getClass().getName());
        d.show();
        log("dialog shown, isShowing=" + d.isShowing());
    }

    // ── 行的语法糖（统一走 Ui，配色按分组） ─────────────────────────────────

    private static void addSwitch(Context ctx, LinearLayout card, final String key,
                                  String label, String desc, int accent) {
        Ui.switchRow(ctx, card, label, desc, Prefs.on(key, Prefs.def(key)), accent, new Ui.Toggle() {
            @Override
            public void on(boolean value) {
                Prefs.setOn(key, value);
            }
        });
    }

    private static void addAction(Context ctx, LinearLayout card, String label, String value,
                                  int accent, final Runnable action) {
        Ui.actionRow(ctx, card, label, value, accent, action);
    }

    // ── 参数表 ──────────────────────────────────────────────────────────────

    private static final int[] ALPHAS = {255, 220, 180, 128, 90, 50};
    private static final String[] ALPHA_LABELS = {"100%", "86%", "70%", "50%", "35%", "20%"};
    private static final int[] RADII = {2, 4, 8, 12, 16, 24, 32};
    private static final String[] RADIUS_LABELS =
            {"2dp", "4dp", "8dp", "12dp", "16dp", "24dp", "32dp"};
    private static final int[] BLURS = {5, 10, 20, 30, 45, 60};
    private static final int[] STEP_MODES = {2, 1, 3};
    private static final String[] STEP_MODE_LABELS =
            {"自动增加（每小时 +N）", "倍率（按真实步数放大）", "直接指定步数（立刻生效）"};

    private static String stepParamLabel() {
        switch (Storage.num(Steps.K_MODE, 2)) {
            case 1:
                return (Storage.num(Steps.K_MUL, 15) / 10.0) + " 倍";
            case 3:
                return Storage.num(Steps.K_TARGET, 12000) + " 步";
            default:
                return "每小时 +" + Storage.num(Steps.K_RATE, 200) + " 步";
        }
    }

    private static final String[] MODE_KEYS = {"fill", "fit", "stretch", "center", "custom"};
    private static final String[] MODE_LABELS =
            {"填充（裁剪多余）", "适应（留边）", "拉伸（变形填满）", "原图居中", "自定义尺寸"};

    private interface Pick {
        void on(int index);
    }

    private static int indexOf(int value, int[] pool) {
        for (int i = 0; i < pool.length; i++) if (pool[i] == value) return i;
        return 0;
    }

    private static int indexOfName(String value, String[] pool) {
        for (int i = 0; i < pool.length; i++) if (pool[i].equals(value)) return i;
        return 0;
    }

    private static void choose(final Activity host, String title, final String[] labels,
                               int checked, final Pick pick) {
        try {
            new android.app.AlertDialog.Builder(host)
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
            toast(host, "设置失败");
        }
    }

    private static void askCustomSize(final Activity host) {
        try {
            LinearLayout box = Ui.column(host);
            int pad = Ui.dp(host, 16);
            box.setPadding(pad, pad, pad, pad);

            final android.widget.EditText wIn = numInput(host,
                    Storage.num(Beautify.K_BG_W, 100), "宽度 %（相对屏幕）");
            final android.widget.EditText hIn = numInput(host,
                    Storage.num(Beautify.K_BG_H, 100), "高度 %（相对屏幕）");
            box.addView(wIn);
            box.addView(hIn);

            new android.app.AlertDialog.Builder(host)
                    .setTitle("自定义背景尺寸")
                    .setView(box)
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            Storage.setNum(Beautify.K_BG_W, parse(wIn, 100));
                            Storage.setNum(Beautify.K_BG_H, parse(hIn, 100));
                            Storage.setStr(Beautify.K_BG_MODE, "custom");
                            Storage.setOn(Prefs.K_THEME, true);
                            toast(host, "已设置 " + parse(wIn, 100) + "% x " + parse(hIn, 100)
                                    + "%（重进界面生效）");
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {
            toast(host, "设置失败");
        }
    }

    private static android.widget.EditText numInput(Activity host, int value, String hint) {
        android.widget.EditText e = new android.widget.EditText(host);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        e.setText(String.valueOf(value));
        return e;
    }

    private static int parse(android.widget.EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void dismissAndReopen(final Activity host) {
        try {
            showing = false;
            show(host);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String bgLabel() {
        String p = Storage.str(Beautify.K_BG, "");
        if (p == null || p.isEmpty()) return "未设置（点击选择图片）";
        return "已设置";
    }

    private static String alphaLabel() {
        int v = Storage.num(Beautify.K_BG_ALPHA, 200);
        for (int i = 0; i < ALPHAS.length; i++) if (ALPHAS[i] == v) return ALPHA_LABELS[i];
        return v + "/255";
    }

    private static String modeLabel() {
        return MODE_LABELS[indexOfName(Storage.str(Beautify.K_BG_MODE, "fill"), MODE_KEYS)];
    }

    private static String customLabel() {
        return Storage.num(Beautify.K_BG_W, 100) + "% x " + Storage.num(Beautify.K_BG_H, 100) + "%";
    }

    // ── 裁剪 ────────────────────────────────────────────────────────────────

    private static final String[] CROP_LABELS = {
            "整张图", "居中放大", "上方三分之一", "中间三分之一", "下方三分之一",
            "左半", "右半", "横向居中 60%", "纵向居中 60%"};
    private static final int[][] CROP_VALUES = {
            {0, 0, 100, 100}, {10, 10, 80, 80}, {0, 0, 100, 33}, {0, 33, 100, 34},
            {0, 66, 100, 34}, {0, 0, 50, 100}, {50, 0, 50, 100},
            {20, 0, 60, 100}, {0, 20, 100, 60}};

    private static String cropLabel(String kx, String ky, String kw, String kh) {
        int x = Storage.num(kx, 0), y = Storage.num(ky, 0);
        int w = Storage.num(kw, 100), h = Storage.num(kh, 100);
        for (int i = 0; i < CROP_VALUES.length; i++) {
            int[] v = CROP_VALUES[i];
            if (v[0] == x && v[1] == y && v[2] == w && v[3] == h) return CROP_LABELS[i];
        }
        return "自定义 " + x + "," + y + " " + w + "x" + h + "%";
    }

    private static void pickCrop(final Activity host, final String kx, final String ky,
                                 final String kw, final String kh) {
        choose(host, "裁剪区域", CROP_LABELS,
                indexOfName(cropLabel(kx, ky, kw, kh), CROP_LABELS), new Pick() {
                    @Override
                    public void on(int i) {
                        int[] v = CROP_VALUES[i];
                        Storage.setNum(kx, v[0]);
                        Storage.setNum(ky, v[1]);
                        Storage.setNum(kw, v[2]);
                        Storage.setNum(kh, v[3]);
                        Storage.setOn(Prefs.K_THEME, true);
                        toast(host, "已裁剪：" + CROP_LABELS[i] + "（重进界面生效）");
                    }
                });
    }

    private static String imgLabel(String key) {
        try {
            String v = Storage.str(key, "");
            return (v == null || v.isEmpty()) ? "未设置（点击选择）" : "已设置";
        } catch (Throwable ignored) {
            return "点击选择";
        }
    }

    /** 面板在宿主进程里，拿不到模块自己的资源，署名直接写常量（与 strings.xml 一致）。 */
    private static String author(Context ctx) {
        return "lex";
    }

    /** 面板在微信进程里跑，ctx.getPackageName() 是微信 —— 要显式问模块自己那一版。 */
    private static String version(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo("io.github.lex0m.lexin", 0).versionName;
        } catch (Throwable t) {
            return MODULE_VERSION;
        }
    }

    /** 取不到包信息时的兜底（与 AndroidManifest 的 versionName 保持一致）。 */
    private static final String MODULE_VERSION = "1.1";

    private static void toast(Context ctx, String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void log(String s) {
        try {
            Log.d(T + s);
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
