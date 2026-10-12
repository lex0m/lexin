package io.github.lex0m.lexin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 首次打开时的免责声明 + 传播限制提示。
 *
 * 规则（用户明确要求）：第一次打开强制阅读，倒数 2 秒后「我已阅读」才可点；
 * 点过之后永久不再出现（每个侧各记一次，见 Prefs 的进程内存储）。
 */
final class Disclaimer {

    /** 文案版本：以后改文案就把这个数 +1，老用户会再看一次。 */
    private static final int VERSION = 1;
    private static final String KEY = "disc_v";
    private static final int WAIT_SECONDS = 2;

    private static final String[] PARAGRAPHS = {
            "一、本模块为个人学习与技术研究用途的 Xposed 模块，不提供任何商业服务，"
                    + "与腾讯公司及微信官方无任何关联。",
            "二、使用本模块产生的全部后果（包括但不限于账号异常、功能异常、数据丢失）"
                    + "由使用者自行承担，作者不承担任何责任。",
            "三、本模块不收集、不上传任何个人信息与聊天内容；所有开关与记录只保存在本机，"
                    + "不修改任何上行报文。",
            "四、请在下载后 24 小时内自行删除；若喜欢相关功能，请支持官方客户端。",
    };

    private static final String[] BAN = {
            "禁止在中国大陆地区的任何网络平台传播本模块：包括但不限于国内网盘、论坛、"
                    + "贴吧、公众号、应用市场、短视频平台与各类社交群文件分享。",
            "禁止售卖、代装、二次打包分发或用于任何商业用途。",
            "仅供个人自用与私下技术交流。若你从上述渠道获得本模块，请立即删除。",
    };

    /** 是否还需要弹：不需要就直接跑 after。 */
    static void gate(final Activity host, final Runnable after) {
        try {
            if (Storage.num(KEY, 0) >= VERSION) {
                after.run();
                return;
            }
        } catch (Throwable ignored) {
            // 读配置失败就当作已经看过，别卡住用户
            after.run();
            return;
        }
        show(host, after);
    }

    private static void show(final Activity host, final Runnable after) {
        try {
            final AlertDialog[] holder = new AlertDialog[1];
            AlertDialog.Builder b = new AlertDialog.Builder(host);
            b.setTitle("免责声明与传播限制");
            b.setView(content(host));
            b.setCancelable(false);
            b.setPositiveButton("我已阅读并知晓（" + WAIT_SECONDS + "）",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            Log.d("svc-disc: positive clicked");
                            Storage.setNum(KEY, VERSION);
                            d.dismiss();
                            after.run();
                            Log.d("svc-disc: after run returned");
                        }
                    });
            b.setNegativeButton("不同意", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        d.dismiss();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    try {
                        if (host != null) host.finish();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    try {
                        if (SettingsPanel.isShowing()) SettingsPanel.close();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            });
            AlertDialog d = b.create();
            holder[0] = d;
            d.show();

            // 倒数：先禁用「我已阅读」，避免闭眼连点
            final android.widget.Button ok = d.getButton(DialogInterface.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setEnabled(false);
                ok.setTextColor(0xFFB9BDC7);
                final int[] left = {WAIT_SECONDS};
                final Handler h = new Handler(Looper.getMainLooper());
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        left[0]--;
                        try {
                            if (left[0] > 0) {
                                ok.setText("我已阅读并知晓（" + left[0] + "）");
                                h.postDelayed(this, 1000L);
                            } else {
                                ok.setText("我已阅读并知晓");
                                ok.setEnabled(true);
                                ok.setTextColor(Ui.ACCENT);
                            }
                        } catch (Throwable ignored) {
                            // 界面没了就算了
                        }
                    }
                }, 1000L);
            } else {
                // 拿不到按钮就退化成「等 2 秒自动允许」，至少不会卡死
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Storage.setNum(KEY, VERSION);
                    }
                }, WAIT_SECONDS * 1000L);
            }
        } catch (Throwable ignored) {
            // 弹不出来就直接放行，别把用户挡在门外
            after.run();
        }
    }

    private static ScrollView content(Activity host) {
        ScrollView sc = new ScrollView(host);
        LinearLayout box = Ui.column(host);
        int pad = Ui.dp(host, 16);
        box.setPadding(pad, pad, pad, pad);

        box.addView(banner(host, "使用前请仔细阅读以下内容"));
        for (String p : PARAGRAPHS) box.addView(para(host, p));

        box.addView(banner(host, "禁止国内网络传播"));
        for (String p : BAN) box.addView(para(host, p));

        TextView tip = new TextView(host);
        tip.setText("继续使用即表示你已阅读并同意以上全部内容。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tip.setTextColor(Ui.WARN);
        tip.setPadding(0, Ui.dp(host, 10), 0, 0);
        box.addView(tip);

        sc.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sc.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(host, 320)));
        return sc;
    }

    private static TextView banner(Activity host, String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(Ui.ACCENT);
        tv.getPaint().setFakeBoldText(true);
        tv.setGravity(Gravity.START);
        tv.setPadding(0, Ui.dp(host, 8), 0, Ui.dp(host, 4));
        return tv;
    }

    private static TextView para(Activity host, String text) {
        TextView tv = new TextView(host);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(Ui.TEXT);
        tv.setLineSpacing(Ui.dp(host, 3), 1.05f);
        tv.setPadding(0, Ui.dp(host, 2), 0, Ui.dp(host, 2));
        return tv;
    }

    private Disclaimer() {}
}
