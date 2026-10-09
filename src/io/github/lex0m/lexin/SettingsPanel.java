package io.github.lex0m.lexin;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置面板：跑在宿主进程内的全屏对话框（跨进程读写配置在 Android 15 上走不通）。 */
final class SettingsPanel {

    private static final String T = "svc-ui: ";
    private static volatile boolean showing = false;

    static void show(final Activity host) {
        if (host == null || showing) return;
        try {
            Prefs.init(host);
            build(host);
        } catch (Throwable t) {
            showing = false;
            log("panel failed: " + t.getClass().getSimpleName());
        }
    }

    private static void build(final Activity host) {
        showing = true;

        final Dialog d = new Dialog(host,
                android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#F6F6F8")));
        }

        ScrollView scroll = new ScrollView(host);
        scroll.setBackgroundColor(Color.parseColor("#F6F6F8"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(host, 18), dp(host, 22) + statusBarPx(host), dp(host, 18), dp(host, 28));
        scroll.addView(root);

        root.addView(header(host, d, "系统服务组件"));
        root.addView(note(host, "全部开关仅作用于本机，不发送任何数据"));

        // ── 分组 1：聊天行为 ──
        LinearLayout g1 = card(host, root);
        addSwitch(host, g1, Prefs.K_RECALL, "消息防撤回",
                "保留对方撤回的消息，并在气泡上标注");
        addSwitch(host, g1, Prefs.K_MOMENTS, "朋友圈保留",
                "对方删除的评论与动态，本地保留副本");
        addSwitch(host, g1, Prefs.K_ADS, "屏蔽广告位",
                "关闭开屏广告与评论区广告");

        // ── 分组 2：本地限制与媒体 ──
        LinearLayout g2 = card(host, root);
        addSwitch(host, g2, Prefs.K_LIMITS, "放宽本地限制",
                "放宽表情包数量、消息多选、状态字数上限");
        addSwitch(host, g2, Prefs.K_MEDIA, "自动查看原图",
                "浏览图片时自动切到原图，无需手动点");

        // ── 分组 3：表情玩法 ──
        LinearLayout g3 = card(host, root);
        addSwitch(host, g3, Prefs.K_DICE, "骰子点数自选",
                "长按表情面板的骰子可指定点数（或保持随机）");
        addSwitch(host, g3, Prefs.K_FIST, "猜拳结果自选",
                "长按表情面板的猜拳可指定出拳");

        // ── 分组 4：外观 ──
        //
        // 三块功能，各带自己的参数。面板跑在微信进程内，所以配置就存在微信的存储里。
        LinearLayout g4 = card(host, root);
        addSwitch(host, g4, Prefs.K_THEME, "启用界面美化", "总开关");

        addAction(host, g4, "全屏背景", bgLabel(), new Runnable() {
            @Override
            public void run() {
                ImagePicker.pick(host, Beautify.K_BG);
            }
        });
        addAction(host, g4, "背景透明度", alphaLabel(), new Runnable() {
            @Override
            public void run() {
                choose(host, "背景透明度", ALPHA_LABELS,
                        indexOf(Storage.num(Beautify.K_BG_ALPHA, 200), ALPHAS),
                        new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_BG_ALPHA, ALPHAS[i]);
                                Storage.setOn(Prefs.K_THEME, true);
                                toast(host, "透明度 " + ALPHA_LABELS[i] + "（重进界面生效）");
                            }
                        });
            }
        });
        addAction(host, g4, "背景大小", modeLabel(), new Runnable() {
            @Override
            public void run() {
                choose(host, "背景大小", MODE_LABELS,
                        indexOfName(Storage.str(Beautify.K_BG_MODE, "fill"), MODE_KEYS),
                        new Pick() {
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
        addAction(host, g4, "自定义尺寸", customLabel(), new Runnable() {
            @Override
            public void run() {
                askCustomSize(host);
            }
        });
        addAction(host, g4, "清除背景", "恢复微信默认", new Runnable() {
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

        addSwitch(host, g4, Beautify.K_CORNER_ON, "UI 圆角化", "给界面容器加圆角");
        addAction(host, g4, "圆角半径", Storage.num(Beautify.K_CORNER_DP, 12) + "dp", new Runnable() {
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

        addSwitch(host, g4, Beautify.K_BLUR_ON, "毛玻璃", "GPU 模糊（Android 12+）");
        addAction(host, g4, "模糊强度", "半径 " + Storage.num(Beautify.K_BLUR_RADIUS, 20), new Runnable() {
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

        // ── 分组 5：聊天行独立成卡 ──
        //
        // 第二层可以有自己的图、裁剪、模糊和透明度，列表行与壁纸的参数互不干扰。
        LinearLayout g5 = card(host, root);
        addSwitch(host, g5, Beautify.K_CARD_ON, "聊天条卡片化", "缩进留边、圆角、独立背景");
        addSwitch(host, g5, Beautify.K_CARD_GLASS, "聊天条玻璃背景",
                "全透明磨砂玻璃（透过聊天条看到背景，文字清晰）");

        addAction(host, g5, "聊天条背景图", imgLabel(Beautify.K_CARD_IMG), new Runnable() {
            @Override
            public void run() {
                ImagePicker.pick(host, Beautify.K_CARD_IMG);
            }
        });
        addAction(host, g5, "聊天条裁剪", cropLabel(Beautify.K_CARD_CX, Beautify.K_CARD_CY,
                Beautify.K_CARD_CW, Beautify.K_CARD_CH), new Runnable() {
            @Override
            public void run() {
                pickCrop(host, Beautify.K_CARD_CX, Beautify.K_CARD_CY,
                        Beautify.K_CARD_CW, Beautify.K_CARD_CH);
            }
        });
        addAction(host, g5, "聊天条透明度", Storage.num(Beautify.K_CARD_ALPHA, 200) + "/255",
                new Runnable() {
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
        addAction(host, g5, "圆角半径", Storage.num(Beautify.K_CARD_CORNER, 16) + "dp", new Runnable() {
            @Override
            public void run() {
                choose(host, "圆角半径", RADIUS_LABELS,
                        indexOf(Storage.num(Beautify.K_CARD_CORNER, 16), RADII), new Pick() {
                            @Override
                            public void on(int i) {
                                Storage.setNum(Beautify.K_CARD_CORNER, RADII[i]);
                                Storage.setOn(Prefs.K_THEME, true);
                                toast(host, "圆角 " + RADIUS_LABELS[i] + "（重进界面生效）");
                            }
                        });
            }
        });
        addAction(host, g5, "左右内缩", Storage.num(Beautify.K_CARD_INSET, 10) + "dp", new Runnable() {
            @Override
            public void run() {
                final int[] vals = {0, 4, 8, 12, 16, 24};
                String[] labels = {"0", "4dp", "8dp", "12dp", "16dp", "24dp"};
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
        addAction(host, g5, "卡片高度", Storage.num(Beautify.K_CARD_H, 0) + "dp", new Runnable() {
            @Override
            public void run() {
                final int[] vals = {-24, -16, -8, 0, 8, 16, 24, 32};
                String[] labels = {"-24", "-16", "-8", "0", "8", "16", "24", "32"};
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
        addAction(host, g5, "卡片间隔", Storage.num(Beautify.K_CARD_GAP, 8) + "dp", new Runnable() {
            @Override
            public void run() {
                final int[] vals = {0, 2, 4, 8, 12, 16, 24};
                String[] labels = {"0", "2dp", "4dp", "8dp", "12dp", "16dp", "24dp"};
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
        addSwitch(host, g5, Beautify.K_CARD_BLUR_ON, "聊天条模糊", "与背景模糊各自独立");
        addAction(host, g5, "聊天条模糊强度",
                "半径 " + Storage.num(Beautify.K_CARD_BLUR_R, 12), new Runnable() {
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

        // 背景裁剪放在背景分组里
        addAction(host, g4, "背景裁剪", cropLabel(Beautify.K_BG_CX, Beautify.K_BG_CY,
                Beautify.K_BG_CW, Beautify.K_BG_CH), new Runnable() {
            @Override
            public void run() {
                pickCrop(host, Beautify.K_BG_CX, Beautify.K_BG_CY,
                        Beautify.K_BG_CW, Beautify.K_BG_CH);
            }
        });
        addInfo(host, g4, "作者 " + author(host) + " · 版本 " + version(host));

        root.addView(note(host, "切换即时生效；部分项需重进会话或重启微信。"));

        d.setContentView(scroll);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                showing = false;
            }
        });
        d.show();
    }

    // ── actions ──────────────────────────────────────────────────────────────




    // ── appearance parameter tables ──────────────────────────────────────────

    private static final int[] ALPHAS = {255, 220, 180, 128, 90, 50};
    private static final String[] ALPHA_LABELS = {"100%", "86%", "70%", "50%", "35%", "20%"};
    private static final int[] RADII = {2, 4, 8, 12, 16, 24, 32};
    private static final String[] RADIUS_LABELS =
            {"2dp", "4dp", "8dp", "12dp", "16dp", "24dp", "32dp"};
    private static final int[] BLURS = {5, 10, 20, 30, 45, 60};
    private static final String[] MODE_KEYS = {"fill", "fit", "stretch", "center", "custom"};
    private static final String[] MODE_LABELS =
            {"填充（裁剪多余）", "适应（留边）", "拉伸（变形填满）", "原图居中", "自定义尺寸"};

    /** Parameter picker. */
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
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int which) {
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

    /** Free-form width/height percentages for the custom sizing mode. */
    private static void askCustomSize(final Activity host) {
        try {
            LinearLayout box = new LinearLayout(host);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (16 * host.getResources().getDisplayMetrics().density);
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
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
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

    // ── crop helpers ─────────────────────────────────────────────────────────

    /** Percentage rectangles. Presets cover the practical "align it like this" cases. */
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

    private static String labelFor(String key) {
        try {
            String p = Prefs.str(key, "");
            if (p == null || p.isEmpty()) return "未设置（点击选择图片）";
            int i = p.lastIndexOf('/');
            return "已设置：" + (i >= 0 ? p.substring(i + 1) : p);
        } catch (Throwable ignored) {
            return "未设置（点击选择图片）";
        }
    }

    private static String shortPath(String p) {
        int i = p.lastIndexOf('/');
        return i >= 0 && i < p.length() - 1 ? p.substring(i + 1) : p;
    }

    /** Preset palette - a full colour picker is overkill for a bar tint. */
    private static final String[] COLOUR_NAMES = {
            "跟随微信默认", "纯黑 #000000", "深空灰 #2C2C2E", "墨绿 #1F3B2C",
            "藏蓝 #14243D", "酒红 #3B1F26", "米白 #F2EFE9", "淡紫 #2A2233",
    };
    private static final int[] COLOUR_VALUES = {
            0, 0xFF000000, 0xFF2C2C2E, 0xFF1F3B2C,
            0xFF14243D, 0xFF3B1F26, 0xFFF2EFE9, 0xFF2A2233,
    };

    private static void pickColour(final Activity host) {
        final int current = Prefs.num(Theme.K_BAR_TINT, 0);
        int checked = 0;
        for (int i = 0; i < COLOUR_VALUES.length; i++) {
            if (COLOUR_VALUES[i] == current) { checked = i; break; }
        }
        try {
            new android.app.AlertDialog.Builder(host)
                    .setTitle("标题栏 / 状态栏配色")
                    .setSingleChoiceItems(COLOUR_NAMES, checked,
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int which) {
                                    Prefs.setNum(Theme.K_BAR_TINT, COLOUR_VALUES[which]);
                                    Prefs.setOn(Prefs.K_THEME, true);
                                    toast(host, "已设置：" + COLOUR_NAMES[which]
                                            + "（重进主界面生效）");
                                    d.dismiss();
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static String barColourLabel() {
        int c = Prefs.num(Theme.K_BAR_TINT, 0);
        for (int i = 0; i < COLOUR_VALUES.length; i++) {
            if (COLOUR_VALUES[i] == c) return COLOUR_NAMES[i];
        }
        return "跟随微信默认";
    }

    private static String packLabel() {
        try {
            ThemePack.ensureLoaded();
            String n = ThemePack.name();
            return (n == null || "(none)".equals(n)) ? "未找到主题包（点此重载）" : n;
        } catch (Throwable ignored) {
            return "未找到主题包";
        }
    }


    /** 署名，取自资源（跟 strings.xml 走）。 */
    private static String author(Context ctx) {
        try {
            return ctx.getString(R.string.app_author);
        } catch (Throwable t) {
            return "lex";
        }
    }

    /** 面板里显示的版本号。 */
    private static String version(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private static void toast(Context ctx, String s) {        try {
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

    // ── view vocabulary (resource-free) ──────────────────────────────────────

    private static View header(final Context ctx, final Dialog d, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4), 0, 0, dp(ctx, 8));

        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        tv.setTextColor(Color.parseColor("#1C1C1E"));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        TextView close = new TextView(ctx);
        close.setText("\u2715");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        close.setTextColor(Color.parseColor("#8A8A8E"));
        close.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    d.dismiss();
                } catch (Throwable ignored) {
                    showing = false;
                }
            }
        });
        row.addView(close);
        return row;
    }

    private static TextView note(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(Color.parseColor("#8A8A8E"));
        tv.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 10));
        return tv;
    }

    private static LinearLayout card(Context ctx, LinearLayout parent) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(ctx, 12));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 14);
        parent.addView(c, lp);
        return c;
    }

    private static void addSwitch(Context ctx, LinearLayout card, final String key,
                                  String label, String desc) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(Color.parseColor("#1C1C1E"));
        texts.addView(t);

        TextView d = new TextView(ctx);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setTextColor(Color.parseColor("#8A8A8E"));
        texts.addView(d);

        final Switch sw = new Switch(ctx);
        sw.setChecked(Prefs.on(key, false));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                try {
                    Prefs.setOn(key, on);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        });

        row.addView(texts);
        row.addView(sw);
        card.addView(row);
        divider(ctx, card);
    }

    private static void addAction(Context ctx, LinearLayout card, String label,
                                  String value, final Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTextColor(Color.parseColor("#1C1C1E"));
        texts.addView(t);

        TextView d = new TextView(ctx);
        d.setText(value);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        d.setTextColor(Color.parseColor("#8A8A8E"));
        texts.addView(d);

        TextView arrow = new TextView(ctx);
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
        divider(ctx, card);
    }

    private static void addInfo(Context ctx, LinearLayout card, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(Color.parseColor("#8A8A8E"));
        tv.setPadding(dp(ctx, 14), dp(ctx, 2), dp(ctx, 14), dp(ctx, 12));
        card.addView(tv);
    }

    private static void divider(Context ctx, LinearLayout card) {
        View v = new View(ctx);
        v.setBackgroundColor(Color.parseColor("#EFEFF4"));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2)));
        card.addView(v);
    }

    private static int statusBarPx(Context ctx) {
        try {
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return ctx.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            // 继续往下
        }
        return (int) (24 * ctx.getResources().getDisplayMetrics().density);
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
