package com.sysprobe.svc;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 骰子/猜拳控制。8.0.78 上实测的调用链：面板点击 → 随机数 → 结果。 */
final class EmojiGame {

    /** Emoji panel kind id for the dice (probed on 8.0.78). */
    private static final int KIND_DICE = 429;
    /** Emoji panel kind id for rock-paper-scissors (probed on 8.0.78). */
    private static final int KIND_FIST = 428;
    /** Menu index (1=rock, 2=scissors, 3=paper) -> the host's own value. */
    private static final int[] FIST_ORDER = {0, 1, 0, 2};

    private static final AtomicInteger parked = new AtomicInteger(-1);
    private static volatile long parkedExpiry = 0L;
    /** Guards against overlapping dialogs when the user taps repeatedly. */
    private static final AtomicBoolean prompting = new AtomicBoolean(false);

    private static Method panelMethod;
    /** While > now, every RNG call is logged with its stack (fist hunt). */
    private static volatile long rngProbeUntil = 0L;
    private static final java.util.Set<String> rngSeen =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void install() throws Throwable {
        ClassLoader cl = Features.loader;

        Class<?> panel = Silent.cls(cl, "vr.p");
        Class<?> callback = Silent.cls(cl, "sr.u0");
        if (panel != null && callback != null) {
            panelMethod = Silent.method(panel, "a", android.view.View.class, Context.class,
                    int.class, callback);
        }
        if (panelMethod != null) {
            XposedBridge.hookMethod(panelMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int k = Silent.asInt(param.args[2]);
                        if (k != KIND_DICE && k != KIND_FIST) return;

                        // 两个玩法走同一个随机数入口；猜拳用 3 值区间，骰子用 6
                        boolean dice = (k == KIND_DICE);
                        if (dice && !Prefs.on(Prefs.K_DICE, true)) return;
                        if (!dice && !Prefs.on(Prefs.K_FIST, true)) return;
                        if (!prompting.compareAndSet(false, true)) return;

                        param.setResult(null);            // suspend dispatch
                        prompt((Context) param.args[1], param, dice);
                    } catch (Throwable t) {
                        prompting.set(false);
                        replay(param);
                    }
                }
            });
        }

        Class<?> rng = Silent.cls(cl, "com.tencent.mm.sdk.platformtools.y8");
        Method q = Silent.method(rng, "Q", int.class, int.class);
        if (q != null) {
            XposedBridge.hookMethod(q, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 故意放在之后：先让宿主自己算完，内部记账才一致，我们只覆盖结果；早先「之前 + setResult」会把调用整个跳过，对宿主状态是无谓风险
                    try {
                        int v = parked.getAndSet(-1);
                        if (v >= 0 && System.currentTimeMillis() < parkedExpiry) {
                            param.setResult(v);
                        }
                    } catch (Throwable ignored) {
                        // 保留宿主掷出的结果
                    }
                }
            });
        }

        // 猜拳路径排查：只在上面开的窗口内记录随机数调用
        try {
            XposedBridge.hookAllMethods(java.util.Random.class, "nextInt",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            logRng("Random.nextInt", p);
                        }
                    });
            Method rand = null;
            try {
                rand = Math.class.getDeclaredMethod("random");
            } catch (Throwable ignored) { }
            if (rand != null) {
                XposedBridge.hookMethod(rand, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        logRng("Math.random", p);
                    }
                });
            }
        } catch (Throwable ignored) {
            // 仅探测
        }

        Log.d("svc-emoji: picker installed");
    }

    /** Logs an RNG call with its WeChat-side stack while the fist window is open. */
    private static void logRng(String what, XC_MethodHook.MethodHookParam p) {
        try {
            if (System.currentTimeMillis() > rngProbeUntil) return;
            if (rngSeen.size() > 12) return;

            StringBuilder sb = new StringBuilder();
            StackTraceElement[] st = new Throwable().getStackTrace();
            int n = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (!cn.startsWith("com.tencent.mm")) continue;
                sb.append(cn).append('#').append(e.getMethodName()).append(" | ");
                if (++n >= 3) break;
            }
            if (n == 0) return;                      // only host-side frames matter
            String key = what + sb;
            if (!rngSeen.add(key)) return;

            Object a0 = p.args != null && p.args.length > 0 ? p.args[0] : null;
            Log.d("svc-emoji: RNG " + what
                    + (a0 == null ? "" : "(" + a0 + ")")
                    + " stack=" + sb);
        } catch (Throwable ignored) {
            // 仅探测
        }
    }

    private static void prompt(final Context ctx, final XC_MethodHook.MethodHookParam param,
                              final boolean dice) {
        Runnable ui = new Runnable() {
            @Override
            public void run() {
                try {
                    final CharSequence[] items = dice
                            ? new CharSequence[]{"随机（不干预）", "1", "2", "3", "4", "5", "6"}
                            : new CharSequence[]{"随机（不干预）", "石头 \u270A", "剪刀 \u270C", "布 \u270B"};
                    new AlertDialog.Builder(ctx)
                            .setTitle(dice ? "骰子点数" : "猜拳出什么")
                            .setCancelable(true)
                            .setItems(items, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    if (which > 0) {
                                        parked.set(dice ? (which - 1) : FIST_ORDER[which]);   // WeChat rolls 0..5
                                        parkedExpiry = System.currentTimeMillis() + 5000L;
                                    }
                                    finish(param);
                                }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface d) {
                                    finish(param);
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface d) {
                                    // 最后兜底释放：别把闸门一直锁着
                                    if (prompting.get()) finish(param);
                                }
                            })
                            .show();
                } catch (Throwable ignored) {
                    finish(param);
                }
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            ui.run();
        } else {
            new Handler(Looper.getMainLooper()).post(ui);
        }
    }

    /** Replays the suspended dispatch exactly once, then releases the gate. */
    private static void finish(XC_MethodHook.MethodHookParam param) {
        try {
            replay(param);
        } finally {
            prompting.set(false);
            parkedExpiry = 0L;
        }
    }

    /** Bypasses our own hook - reflective invoke would re-enter it. */
    private static void replay(XC_MethodHook.MethodHookParam param) {
        try {
            Method m = panelMethod;
            if (m == null) return;
            Object target = Modifier.isStatic(m.getModifiers()) ? null : param.thisObject;
            XposedBridge.invokeOriginalMethod(m, target, param.args);
        } catch (Throwable ignored) {
            // 静默: worst case the tap is a no-op
        }
    }
}
