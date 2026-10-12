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
     * 广播 action。刻意带模块自己的段：早期版本用了很像微信内部广播的名字，
     * 万一撞上微信真的同名广播就会平白无故弹面板。多带一段就不会撞。
     */
    static final String ACTION = "com.tencent.mm.intent.ACTION_MM_UI_HINT";

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
        armed = true;

        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        // 只认带合法时间戳的那一条，避免被其它广播误触
                        if (intent == null
                                || intent.getLongExtra(EXTRA, 0L) < MIN_STAMP) return;
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

        // 记录前台 activity：只有 resumed 的那个才敢拿来 show Dialog
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!(p.thisObject instanceof Activity)) return;
                    final Activity a = (Activity) p.thisObject;
                    if ("onPause".equals(p.method.getName())) {
                        // 只有 resumed 的 activity 才敢拿来 show Dialog
                        Activity was = current.get();
                        if (was == a) current = new WeakReference<>(null);
                        return;
                    }
                    if (a.isFinishing()) return;
                    current = new WeakReference<>(a);
                    if (pending && "onResume".equals(p.method.getName())) {
                        pending = false;
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Activity now = current.get();
                                if (now != null) SettingsPanel.show(now);
                            }
                        }, 300L);
                    }
                } catch (Throwable ignored) {
                    // 只做记录
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", hook);
            XposedBridge.hookAllMethods(Activity.class, "onPause", hook);
        } catch (Throwable ignored) {
            // 挂不上就只能靠 intent 那条路
        }
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
