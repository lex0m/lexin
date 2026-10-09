package com.sysprobe.svc;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Set;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 隐藏的设置入口，挂在「我」页面。 */
final class Settings {

    private static final String T = "svc-ui: ";
    /** Bland label: reads as one more system row. */
    private static final String ENTRY_LABEL = "\u901a\u7528";
    /** Host page that carries the hidden trigger. */
    private static final String CARE_MODE_CLASS =
            "com.tencent.mm.plugin.setting.ui.setting.SettingsCareModeIntro";
    /** Label of the button that answers the long press. */
    private static final String TRIGGER_TEXT = "\u5f00\u542f";
    private static final AtomicBoolean dumped = new AtomicBoolean(false);

    /** Catalogues the host's Fragments - the tab pages live inside LauncherUI. */
    private static void probeActivities() {
        try {
            final Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Class<?> frag = null;
            for (String n : new String[]{S.CLS_FRAGMENT, "android.app.Fragment"}) {
                frag = Silent.cls(Features.loader, n);
                if (frag != null) break;
            }
            if (frag == null) return;
            for (Method m : frag.getDeclaredMethods()) {
                if (!"onViewCreated".equals(m.getName()) || m.getParameterCount() != 2) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String cn = p.thisObject.getClass().getName();
                            if (!cn.startsWith("com.tencent.mm")) return;
                            if (seen.size() > 30) return;
                            if (seen.add(cn)) Log.d(T + "FRAG " + cn);
                        } catch (Throwable ignored) {
                            // 仅探测
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
            // 仅探测
        }
    }

    static void install() throws Throwable {
        hookCareMode();
    }

    /** 隐藏触发方式：长按关怀模式页的主按钮（比插一行设置项更不显眼）。 */
    private static void hookCareMode() {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object act = p.thisObject;
                        if (!(act instanceof Activity)) return;
                        if (!CARE_MODE_CLASS.equals(act.getClass().getName())) return;

                        final Activity host = (Activity) act;
                        final View decor = host.getWindow().getDecorView();
                        // onCreate 时布局还没好，推迟一趟
                        decor.post(new Runnable() {
                            @Override
                            public void run() {
                                attachTrigger(decor, host);
                            }
                        });
                    } catch (Throwable ignored) {
                        // 不打扰宿主页面
                    }
                }
            });
            Log.d(T + "care-mode trigger armed");
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
    }

    /** Walks the tree and plants the long-press on the page's action button. */
    private static void attachTrigger(View v, final Activity host) {
        if (v == null) return;
        try {
            if (v instanceof TextView) {
                CharSequence label = ((TextView) v).getText();
                if (label != null && label.toString().contains(TRIGGER_TEXT)) {
                    v.setLongClickable(true);
                    v.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View w) {
                            // 面板在宿主进程内打开
                            //
                            // 这不是风格选择：Android 15 上所有跨进程通道都实测被封——模块私有文件（SELinux）、媒体卷（FUSE）、ContentProvider（No content provider）。所以设置界面只能跑在微信进程内，读写都是同进程 SharedPreferences。
                            //
                            // 附带好处：背景图也落在微信自己的私有目录，/sdcard 上一个文件都不留。
                            try {
                                Storage.diag("trigger: show panel");
                                SettingsPanel.show(host);
                                Storage.diag("trigger: panel shown");
                            } catch (Throwable t) {
                                // 把原因暴露出来：这里静默 catch 的话，面板会毫无线索地消失。
                                Storage.diag("trigger: panel FAILED "
                                        + t.getClass().getName() + " " + t.getMessage());
                            }
                            return true;
                        }
                    });
                    Storage.diag("trigger: planted on '" + label + "'");
                    bark("trigger planted");
                    return;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    attachTrigger(g.getChildAt(i), host);
                }
            }
        } catch (Throwable ignored) {
            // 不打扰宿主页面
        }
    }

    private static volatile boolean canOpenReported = false;

    /** One-shot structural dump, depth-limited and capped, so the log stays small. */
    private static void dump(View v, int depth) {
        if (v == null || depth > 6) return;
        if (!dumped.compareAndSet(false, true)) return;
        try {
            StringBuilder sb = new StringBuilder();
            walk(v, 0, sb, 0);
            Log.d(T + "MORETAB TREE\n" + sb);
        } catch (Throwable ignored) {
            // 仅探测
        }
    }

    private static int walk(View v, int depth, StringBuilder sb, int budget) {
        if (v == null || depth > 5 || budget > 60) return budget;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        sb.append(indent)
                .append(v.getClass().getSimpleName())
                .append(" id=").append(v.getId())
                .append(" vis=").append(v.getVisibility());
        Object tag = v.getTag();
        if (tag != null) sb.append(" tag=").append(tag.getClass().getSimpleName());
        sb.append('\n');
        budget++;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && budget <= 60; i++) {
                budget = walk(g.getChildAt(i), depth + 1, sb, budget);
            }
        }
        return budget;
    }

    /** 往「我」列表追加一行：那个列表带标准 list id，作为 footer 加进去最稳。 */
    private static void injectEntry(View root, Activity host) {
        ListView list = findList(root);
        if (list == null) {
            bark("no list");
            return;
        }
        if (list.getAdapter() != null) {
            bark("adapter already attached - entry skipped");
            return;
        }
        try {
            final Activity ctx = host;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (14 * ctx.getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            row.setBackgroundColor(0xFFFFFFFF);

            TextView label = new TextView(ctx);
            label.setText(ENTRY_LABEL);
            label.setTextSize(16f);
            label.setTextColor(0xFF1C1C1E);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            TextView arrow = new TextView(ctx);
            arrow.setText("\u203A");
            arrow.setTextSize(18f);
            arrow.setTextColor(0xFFC7C7CC);
            row.addView(arrow);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(ctx);
                }
            });

            list.addFooterView(row, null, false);
            bark("entry injected");
        } catch (Throwable tr) {
            bark("inject failed: " + tr.getClass().getSimpleName());
        }
    }

    private static ListView findList(View v) {
        if (v instanceof ListView) return (ListView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ListView r = findList(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Rate-limited one-shot diagnostics. */
    private static final Set<String> barked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void bark(String msg) {
        if (barked.size() > 12) return;
        if (barked.add(msg)) Log.d(T + msg);
    }

    /** Placeholder so the class compiles before the real screen lands. */
    static void open(Activity from) {
        try {
            // 这个界面在模块的包名下（不是宿主），所以 intent 要指向自己的包，并以新任务启动。
            Intent i = new Intent();
            i.setClassName("com.sysprobe.svc", "com.sysprobe.svc.SettingsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            from.startActivity(i);
        } catch (Throwable ignored) {
            // 界面还没实现
        }
    }
}
