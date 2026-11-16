package io.github.lex0m.lexin;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动查看原图。
 *
 * v1.0 挂的是图片浏览页里两个混淆方法名（y9 / M9），微信 8.0.78 上这两个名字已经没了，
 * 功能实际是哑的。现在改成**按界面文字找按钮并点它** ——
 * 「查看原图」这四个字微信不太会改，类名改了也不影响。
 */
final class MediaSaver {

    private static final String T = "svc-media: ";
    private static final String[] LABELS = {"查看原图", "查看原视频", "原图"};

    private static volatile String galleryName;

    /** 同一张图最多重试几次（按钮是异步出现的）。 */
    private static final int ATTEMPTS = 4;
    private static final long RETRY_MS = 450L;

    private static volatile boolean armed;

    static void install() {
        if (armed) return;          // 重试会再调一次，回调不能注册两份
        Class<?> gallery = Anchors.cls(Anchors.GALLERY);
        if (gallery == null) {
            Status.set("自动查看原图", false);
            return;
        }
        galleryName = gallery.getName();
        // v1.6：不再 hook framework 的 Activity.onResume（框架热方法是最容易被扫的检测面），
        // 改用官方生命周期回调 —— 零 hook、零 Xposed 栈帧。
        try {
            Application app = (Application) Features.app;
            if (app == null) {
                Status.armed("自动查看原图", 0);
                return;
            }
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity act) {
                    try {
                        if (act == null) return;
                        String an = act.getClass().getName();
                        Log.d(T + "resume " + an);
                        boolean match = isGallery(act);
                        lastPage = shortName(an);
                        if (!Prefs.onDefault(Prefs.K_MEDIA)) {
                            remember(shortName(an), match);
                            return;
                        }
                        // ★ 8.0.80 实测（用户现场反馈定案）：
                        //   在微信内部点开图片**不会触发 Activity.resume**（图片预览是在
                        //   当前页面里换视图），只有「从别的 App 切回微信」才会 resume。
                        //   所以「resume 才启动观察」这条链，正常点图永远等不到 ——
                        //   这也正好解释了那个怪现象：切回来才自动下原图。
                        //   现在改成**页面停留期间持续观察**：resume 起、pause 停。
                        startWatch(act);
                        remember(shortName(an), match);
                    } catch (Throwable ignored) {
                        // 仅外观，不打扰浏览页
                    }
                }

                @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override
                public void onActivityPaused(Activity a) {
                    // 页面离开就停观察，不做后台轮询（省电、也少一分散检测面）
                    watching = false;
                }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) { }
            });
            armed = true;
            Status.armed("自动查看原图", 1);
            Log.d(T + "armed on " + galleryName);
        } catch (Throwable t) {
            Status.armed("自动查看原图", 0);
        }
    }

    /** 最近若干个页面（去重、保序、最多 5 个），以及有没有识别出图片页。 */
    private static final java.util.LinkedHashSet<String> seenPages =
            new java.util.LinkedHashSet<>();
    private static volatile boolean gallerySeen = false;

    private static void remember(String shortName, boolean gallery) {
        try {
            if (gallery) gallerySeen = true;
            synchronized (seenPages) {
                seenPages.remove(shortName);
                seenPages.add(shortName);
                while (seenPages.size() > 5) {
                    java.util.Iterator<String> it = seenPages.iterator();
                    it.next();
                    it.remove();
                }
            }
            StringBuilder sb = new StringBuilder();
            synchronized (seenPages) {
                for (String p : seenPages) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(p);
                }
            }
            Status.note("自动查看原图",
                    (watching ? "观察中 | " : "未观察 | ")
                            + (gallerySeen ? "已识别图片页 | " : "") + sb);
        } catch (Throwable ignored) {
            // 诊断信息本身不该影响功能
        }
    }

    /** 类名只留最后两段，进状态快照时不占地方也不泄漏完整包结构。 */
    private static String shortName(String full) {
        if (full == null) return "?";
        int i = full.lastIndexOf('.');
        int j = i > 0 ? full.lastIndexOf('.', i - 1) : -1;
        return j >= 0 ? full.substring(j + 1) : full;
    }

    /** 画廊页判定：运行期可能是锚点类的子类，按父类链匹配。 */
    private static boolean isGallery(Activity act) {
        String want = galleryName;
        if (act == null || want == null) return false;
        Class<?> k = act.getClass();
        while (k != null && k != Object.class) {
            if (want.equals(k.getName())) return true;
            k = k.getSuperclass();
        }
        return false;
    }

    /** 轮询间隔：太长会错过按钮出现的时机，太短白耗电。 */
    private static final long POLL_MS = 600L;
    /** 同一次停留最多观察多久（防呆：万一 pause 没来，也不会一直扫下去）。 */
    private static final long MAX_WATCH_MS = 5 * 60 * 1000L;
    /** 正在观察中。 */
    private static volatile boolean watching = false;
    /** 最近一次 resume 的页面短名（写诊断用）。 */
    private static volatile String lastPage = "?";

    /**
     * 页面 resume 后开始观察：每一轮扫一遍当前页面的视图树，找「查看原图」并点它。
     * 页面 pause 时由 {@code onActivityPaused} 把 watching 置 false 停下。
     */
    private static void startWatch(final Activity act) {
        final View root = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (root == null) return;
        if (watching) return;
        watching = true;
        final long until = System.currentTimeMillis() + MAX_WATCH_MS;
        final int[] tries = {0};
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!watching || System.currentTimeMillis() > until) {
                        watching = false;
                        return;
                    }
                    if (root.getWindowVisibility() == View.VISIBLE && root.isShown()) {
                        tries[0]++;
                        scanStats[0] = 0;      // 本轮看到几个候选
                        scanStats[1] = 0;      // 本轮扫了几个节点
                        reject = "";
                        if (clickOriginal(root)) {
                            Status.hit("自动查看原图");
                            Status.note("自动查看原图", "已点「查看原图」（第 " + tries[0] + " 次扫描）");
                            Log.d(T + "original pressed");
                        } else if (tries[0] % 5 == 0) {
                            // 每 5 轮（约 3 秒）报一次现场，便于判断「没这条按钮」还是「扫不到」
                            Status.note("自动查看原图", "扫描 " + tries[0] + " 轮 · 节点 "
                                    + scanStats[1] + " · 候选 " + scanStats[0]
                                    + (reject.isEmpty() ? "" : " · " + reject)
                                    + " · 页面 " + lastPage);
                        }
                    }
                    root.postDelayed(self[0], POLL_MS);
                } catch (Throwable ignored) {
                    watching = false;
                }
            }
        };
        root.postDelayed(self[0], 400L);
    }

    /** 每轮最多看多少个节点 / 多深（聊天页视图树很大，不能无脑全扫）。 */
    private static final int[] scanStats = new int[2];
    /** 本轮为什么没点到（诊断用）。 */
    private static volatile String reject = "";
    private static final int MAX_NODES = 3000;
    private static final int MAX_DEPTH = 26;

    /** 深度优先找「可点的、带原图字样」的可见控件并点下去。 */
    private static boolean clickOriginal(View v) {
        return clickOriginal(v, 0, new int[]{0});
    }

    private static boolean clickOriginal(View v, int depth, int[] budget) {
        if (v == null || depth > MAX_DEPTH || budget[0] > MAX_NODES) return false;
        budget[0]++;
        scanStats[1]++;
        try {
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && matches(t.toString())) {
                    scanStats[0]++;
                }
            }
            if (v instanceof TextView && v.isShown() && v.isEnabled()) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && matches(t.toString())) {
                    View clickable = clickableSelfOrParent(v);
                    if (clickable != null) {
                        clickable.performClick();
                        return true;
                    }
                    // 看得到、点不到：把原因留下来（是文字对了但不可点，还是别的情况）
                    reject = "文字对但不可点：" + t;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (clickOriginal(g.getChildAt(i), depth + 1, budget)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 继续找
        }
        return false;
    }

    private static boolean matches(String s) {
        for (String l : LABELS) {
            if (s.contains(l)) return true;
        }
        return false;
    }

    private static View clickableSelfOrParent(View v) {
        View cur = v;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            Object parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private MediaSaver() {}
}
