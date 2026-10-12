package io.github.lex0m.lexin;

import android.app.Activity;
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

    static void install() {
        Class<?> gallery = Anchors.cls(Anchors.GALLERY);
        if (gallery == null) {
            Status.set("自动查看原图", false);
            return;
        }
        galleryName = gallery.getName();
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object act = param.thisObject;
                        if (!(act instanceof Activity)) return;
                        if (!galleryName.equals(act.getClass().getName())) return;
                        if (!Prefs.onDefault(Prefs.K_MEDIA)) return;
                        schedule((Activity) act);
                    } catch (Throwable ignored) {
                        // 仅外观，不打扰浏览页
                    }
                }
            });
            Status.set("自动查看原图", true);
            Log.d(T + "armed on " + galleryName);
        } catch (Throwable t) {
            Status.set("自动查看原图", false);
        }
    }

    private static void schedule(final Activity act) {
        final View root = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (root == null) return;
        final boolean[] done = {false};
        for (int i = 0; i < ATTEMPTS; i++) {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (done[0]) return;                 // 已经切到原图，别再重复点
                        if (clickOriginal(root)) {
                            done[0] = true;
                            Log.d(T + "original pressed");
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                }
            }, RETRY_MS * i);
        }
    }

    /** 深度优先找「可点的、带原图字样」的可见控件并点下去。 */
    private static boolean clickOriginal(View v) {
        if (v == null) return false;
        try {
            if (v instanceof TextView && v.isShown() && v.isEnabled()) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && matches(t.toString())) {
                    View clickable = clickableSelfOrParent(v);
                    if (clickable != null) {
                        clickable.performClick();
                        return true;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (clickOriginal(g.getChildAt(i))) return true;
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
