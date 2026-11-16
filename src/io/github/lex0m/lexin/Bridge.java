package io.github.lex0m.lexin;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 模块侧 → 微信侧 的一条单向通道：告诉微信"把面板打开"。
 *
 * 为什么不用 startActivity 拉起微信：本机微信开了「应用双开」（user 999），
 * 第三方 App 只要发起对微信的 activity 启动，小米的 XSpace 就会弹
 * 「请选择要使用的应用」（实测三种 intent 写法都躲不掉）。所以改成：
 *   模块侧（桌面图标 / LSPosed ▶）→ 发一条显式广播给微信 → 微信进程里的模块接住 → 开面板。
 * 广播不涉及 activity 启动，不触发那个选择器。
 *
 * 面板要在 activity 处于 resumed 时才敢 show（否则会 BadTokenException），
 * 所以这里记录当前 resumed 的 activity；不在前台就先挂着，等下一次 onResume。
 */
final class Bridge {

    private static final String T = "svc-bridge: ";
    /**
     * 广播 action。**必须是模块自己的短串**。
     *
     * v1.6.1 这里用的是 S.WX_P8，解出来是微信自己的
     * {@code com.tencent.mm.ui.intent.ACTION_MM_UI_HINT} —— 跟注释说的"带模块自己的段"相反：
     *   ① 撞微信内部广播，微信自己发一次就弹一次面板；
     *   ② 任何第三方 App 发同名广播都能把全屏面板弹出来（接收器 exported 且只验时间戳）。
     * 现在改成模块私有的短 action，并加 MAC（见 Sig），伪造的直接丢。
     */
    static final String ACTION = "x.b";

    private static volatile WeakReference<Activity> current =
            new WeakReference<>(null);
    /** 有值才认这次广播（模块侧会带上当前时间戳）。 */
    private static final String EXTRA = "open_from";
    private static final long MIN_STAMP = 1700000000000L;
    private static volatile boolean pending = false;
    private static volatile boolean armed = false;

    /** 宿主进程里调一次：注册接收器 + 跟踪前台 activity。 */
    static void install(final Context app) {
        if (armed) return;
        // 注意：这里不再先置位。注册失败时保持 false，下次重试还有机会；
        // 先置位再注册会让一次偶发失败变成"永久没有面板桥"。

        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        // 只认带合法时间戳 + 正确 MAC 的那一条。
                        // MAC 用构建期注入的 AnchorKey（只有本 APK 里有），第三方算不出来。
                        if (intent == null) return;
                        long stamp = intent.getLongExtra(EXTRA, 0L);
                        if (stamp < MIN_STAMP) return;
                        if (!Sig.ok(ACTION + "|" + stamp, intent.getStringExtra(Sig.EXTRA))) return;
                        requestPanel();
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            };
            IntentFilter f = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            Log.d(T + "receiver armed");
        } catch (Throwable t) {
            Log.d(T + "receiver failed: " + t.getClass().getSimpleName());
        }

        // 记录前台 activity：只有 resumed 的那个才敢拿来 show Dialog。
        // v1.6：改用官方生命周期回调 —— 以前这里挂的是 framework 的 Activity.onResume/onPause，
        // 属于"全局可见、最容易扫到"的那一类 hook，收益却只是记一个当前 Activity。
        try {
            android.app.Application application = (android.app.Application) app;
            application.registerActivityLifecycleCallbacks(
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityResumed(final Activity a) {
                            try {
                                if (a == null || a.isFinishing()) return;
                                // 诊断：运行中的 Activity 类 vs 用 Features.loader 解析出的类，
                                // 身份不同 = 微信用了另一份类（Tinker 补丁），hook 必然打不中。
                                try {
                                    Class<?> viaBase = Class.forName(a.getClass().getName(),
                                            false, Features.loader);
                                    Log.d("svc-core: act=" + a.getClass().getName()
                                            + " sameClass=" + (viaBase == a.getClass())
                                            + " actLoader=" + a.getClass().getClassLoader());
                                } catch (Throwable ignored) {
                                    // 诊断失败不影响面板
                                }
                                current = new WeakReference<>(a);
                                // 运行时 loader 只在真有 Activity 之后才可见（Tinker 的
                                // DelegateLastClassLoader），拿到就换过去并重挂核心 hook。
                                Features.adoptLoader(a.getClass().getClassLoader());
                                if (pending) {
                                    pending = false;
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            Activity now = current.get();
                                            if (now != null && !now.isFinishing()) {
                                                SettingsPanel.show(now);
                                            } else {
                                                // 这 300ms 里页面被切走了：请求放回去，下次 resume 再开
                                                pending = true;
                                            }
                                        }
                                    }, 300L);
                                }
                            } catch (Throwable ignored) {
                                // 只做记录
                            }
                        }

                        @Override
                        public void onActivityPaused(Activity a) {
                            try {
                                Activity was = current.get();
                                if (was == a) current = new WeakReference<>(null);
                            } catch (Throwable ignored) {
                                // 只做记录
                            }
                        }

                        @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
                        @Override public void onActivityStarted(Activity a) { }
                        @Override public void onActivityStopped(Activity a) { }
                        @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
                        @Override public void onActivityDestroyed(Activity a) { }
                    });
            Log.d(T + "lifecycle armed");
        } catch (Throwable t) {
            Log.d(T + "lifecycle failed: " + t.getClass().getSimpleName());
        }
        armed = true;
    }

    /** 收到"开面板"的请求：前台开着就立刻开，否则挂起。 */
    private static void requestPanel() {
        Activity a = current.get();
        if (a != null && !a.isFinishing()) {
            SettingsPanel.show(a);
            return;
        }
        pending = true;
        Log.d(T + "panel pending until host resumes");
    }
}
