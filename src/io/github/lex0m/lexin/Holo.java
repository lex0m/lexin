package io.github.lex0m.lexin;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 全息透明（v1.7.13）。
 *
 * 让微信的窗口本身变成半透明，**系统桌面（或下层应用）实时透出来**。
 * 和「全屏背景」是两条完全不同的路：
 *
 *   全屏背景：我们自己画一张图在那层，图是静态的、要从磁盘/相册读，读不到就没有。
 *   全息透明：不动任何一张位图，只把宿主的窗口调成半透明格式 —— 底下是什么就透什么，
 *             动态壁纸、滚动壁纸、别的 App 都能透，零解码、零内存。
 *
 * ★ 为什么必须改窗口格式：窗口默认是不透明 Surface（RGBX），View 画了透明像素也只是
 *   显示上一帧的残影。要让 alpha 真的参与合成，得把 format 设成 TRANSLUCENT，
 *   让 SurfaceFlinger 按 RGBA 混合。这一步只能在窗口 attach 之前做最稳，所以挂 onCreate 的 before。
 *
 * ★ 强度分两档，别混为一谈：
 *   - `holo_alpha`（40~100）：整窗 alpha。100 = 只清底（文字最清晰、背景全靠透明）；
 *     90 以下 = 整体蒙一层，相当于给"清不干净的残白"兜底。
 *   - 清底由 {@link Beautify#clearOpaquePages} 那条链负责，透出来的是桌面而不是壁纸，
 *     判据在 {@link Beautify#clearAllowed()}（有壁纸或全息开着才允许清）。
 *
 * ★ 关掉时必须把 alpha 还成 1.0 并把窗口背景还给微信：只改一半会让微信停在
 *   "半透明但底色是灰"的中间态，比不开还难看。
 */
final class Holo {

    static final String K_HOLO = "holo_on";
    static final String K_HOLO_ALPHA = "holo_alpha";

    private static final String T = "svc-holo: ";

    /** 默认 70：能明显看出"底下有东西"，文字对比度还够用 */
    private static final int DEF_ALPHA = 70;
    private static final int MIN_ALPHA = 40;
    private static final int MAX_ALPHA = 100;

    /** 我们动过属性的窗口。关开关时按这份记录还原，不猜原值。 */
    private static final Map<Window, Boolean> touched =
            Collections.synchronizedMap(new WeakHashMap<Window, Boolean>());

    private static volatile int lastPercent = -1;

    private Holo() { }

    /** 开关状态；读不到配置一律当"关"，绝不因为读不到就把宿主改花。 */
    static boolean on() {
        try {
            return Storage.on(K_HOLO, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 整窗不透明度（40~100，越大越不透明）。 */
    static int percent() {
        try {
            int p = Storage.num(K_HOLO_ALPHA, DEF_ALPHA);
            return Math.max(MIN_ALPHA, Math.min(MAX_ALPHA, p));
        } catch (Throwable ignored) {
            return DEF_ALPHA;
        }
    }

    static void install() {
        int n = 0;
        // onCreate(before)：窗口还没 attach，改 format 最可靠（attach 之后再改要重建 surface）
        try {
            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!on()) return;
                        if (!(p.thisObject instanceof Activity)) return;
                        Window w = ((Activity) p.thisObject).getWindow();
                        if (w != null) apply(w);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 没有这个重载就跳过
        }
        // onResume(after)：微信自己（或它的主题引擎）改过属性时再压回去
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Window w = ((Activity) p.thisObject).getWindow();
                        if (w == null) return;
                        // 关着也要走一趟：运行中关掉开关时靠这次还原
                        apply(w);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 没有这个重载就跳过
        }
        // ★ 关键：Activity.onCreate/onResume 这两个 hook 只对"装好之后新开的页"有用。
        //   模块是启动后 1.2s 才装的，而微信主页的 onCreate/onResume 早在 500ms 内就跑完了
        //   ⇒ 冷启动进主页那一下永远不透明，得等用户切一次页面才生效（真机 11:20 实测）。
        //   两个补丁：① 挂 ActivityThread.handleResumeActivity（每次页面真正显示都走它）
        //            ② 安装时把已经在跑的 Activity 补一遍。
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            XposedBridge.hookAllMethods(at, "handleResumeActivity", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity a = activityOf(p);
                        if (a != null && a.getWindow() != null) {
                            apply(a.getWindow());
                            if (on()) Beautify.refresh(a.getWindow().getDecorView());
                        }
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            n++;
        } catch (Throwable ignored) {
            // 拿不到 ActivityThread 就靠 Activity 那两个 hook
        }
        try {
            applyExisting();
        } catch (Throwable ignored) {
            // 补不了就算了，切一次页面也会生效
        }

        try {
            Status.set("全息透明", n > 0);
            Status.armed("全息透明", n);
            Status.note("全息透明", "hooks=" + n + " · on=" + on() + " · alpha=" + percent()
                    + " · sdk=" + android.os.Build.VERSION.SDK_INT);
        } catch (Throwable ignored) {
            // 状态上报失败不影响功能
        }
        Log.d(T + "install hits=" + n + " on=" + on() + " alpha=" + percent());
    }

    /** 从 handleResumeActivity 的 (token, ...) 里反查 ActivityClientRecord.activity */
    private static Activity activityOf(XC_MethodHook.MethodHookParam p) {
        try {
            if (p.args == null || p.args.length < 1) return null;
            Object token = p.args[0];
            if (token == null) return null;
            Map<?, ?> acts = (Map<?, ?>) XposedHelpers.getObjectField(p.thisObject, "mActivities");
            if (acts == null) return null;
            Object rec = acts.get(token);
            if (rec == null) return null;
            Object a = XposedHelpers.getObjectField(rec, "activity");
            return a instanceof Activity ? (Activity) a : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 安装时把已经在跑的 Activity 补一遍：冷启动进主页那一下靠这个，不然要等用户切页 */
    private static void applyExisting() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cam = at.getDeclaredMethod("currentActivityThread");
            cam.setAccessible(true);
            Object thread = cam.invoke(null);
            if (thread == null) return;
            Map<?, ?> acts = (Map<?, ?>) XposedHelpers.getObjectField(thread, "mActivities");
            if (acts == null) return;
            int n = 0;
            for (Object rec : new java.util.ArrayList<Object>(acts.values())) {
                try {
                    Object a = XposedHelpers.getObjectField(rec, "activity");
                    if (a instanceof Activity) {
                        Window w = ((Activity) a).getWindow();
                        if (w != null) {
                            apply(w);
                            n++;
                        }
                    }
                } catch (Throwable ignored) {
                    // 单个记录取不到就跳过
                }
            }
            Log.d(T + "applyExisting n=" + n);
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static void apply(Window w) {
        try {
            if (w == null) return;
            boolean want = on();

            if (!want) {
                if (touched.remove(w) == null) return;      // 没动过就别动
                WindowManager.LayoutParams lp0 = w.getAttributes();
                if (lp0 != null) {
                    lp0.flags &= ~WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
                    lp0.alpha = 1f;
                    w.setAttributes(lp0);
                }
                w.setBackgroundDrawable(new ColorDrawable(0xFFEDEDED));
                Log.d(T + "restored");
                return;
            }

            // ★ 每个窗口只压一次。反复 setAttributes 会反复重建 Surface，真机表现就是
            //   「内容叠三层 / 竖向条纹」的重影（11:28 那张）。窗口属性本来就是一次性的。
            if (touched.containsKey(w)) return;

            int pct = percent();
            float wantAlpha = Math.max(0.01f, Math.min(1f, pct / 100f));

            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp == null) return;
            // ① 半透明 Surface：不改它，alpha 只是一个被忽略的数字
            lp.format = PixelFormat.TRANSLUCENT;
            lp.alpha = wantAlpha;
            // ② 让系统把壁纸留在窗口下面。没有这个 flag，WMS 会把 WallpaperWindowToken
            //    整个隐藏掉（真机 dump：ImageWallpaper invisible reason=hidden by parent），
            //    于是半透明的微信下面只有黑色 —— 这就是「怎么调都看不到壁纸」的真正原因。
            lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
            w.setAttributes(lp);
            // ③ 窗口自身那层底让开，否则清掉 View 背景后露出的还是窗口底
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            touched.put(w, Boolean.TRUE);
            Log.d(T + "applied alpha=" + pct + "% showWallpaper");
        } catch (Throwable ignored) {
            // 仅外观：任何一步失败都不该影响宿主
        }
    }
}
