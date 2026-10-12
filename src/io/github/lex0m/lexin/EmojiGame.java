package io.github.lex0m.lexin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 骰子 / 猜拳自选。
 *
 * 两个挂钩点：
 *   1) 表情面板的发送入口（点了骰子/猜拳时弹选择框）—— 按签名找，不认死类名
 *   2) 随机数入口 Q(int,int) —— 在宿主算完之后覆盖结果，不改它的内部记账
 */
final class EmojiGame {

    private static final String T = "svc-emoji: ";

    /** 表情面板里骰子的 kind id（8.0.x 实测）。 */
    private static final int KIND_DICE = 429;
    /** 猜拳的 kind id。 */
    private static final int KIND_FIST = 428;
    /** 菜单下标（1=石头 2=剪刀 3=布）-> 宿主内部取值。 */
    private static final int[] FIST_ORDER = {0, 1, 0, 2};

    private static final AtomicInteger parked = new AtomicInteger(-1);
    private static volatile long parkedExpiry = 0L;
    /** 防止连点时弹出多个框。 */
    private static final AtomicBoolean prompting = new AtomicBoolean(false);

    private static volatile Method panelMethod;

    static void install() {
        int armed = 0;
        armed += hookPanel();
        armed += hookRng();
        Status.set("骰子/猜拳", armed > 0);
        Log.d(T + "armed=" + armed);
    }

    /** 面板入口：a(android.view.View, android.content.Context, int, 回调) */
    private static int hookPanel() {
        Class<?> panel = Anchors.cls(Anchors.EMOJI_PANEL);
        if (panel == null) return 0;
        Method target = null;
        for (Method m : panel.getDeclaredMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 4 || m.getReturnType() != void.class) continue;
            if (pt[0] != View.class || pt[1] != Context.class || pt[2] != int.class) continue;
            target = m;
            break;
        }
        if (target == null) {
            Log.d(T + "panel signature not found in " + panel.getName());
            return 0;
        }
        final Method method = target;
        panelMethod = method;
        try {
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        int k = Silent.asInt(param.args[2]);
                        if (k != KIND_DICE && k != KIND_FIST) return;

                        boolean dice = (k == KIND_DICE);
                        if (dice && !Prefs.onDefault(Prefs.K_DICE)) return;
                        if (!dice && !Prefs.onDefault(Prefs.K_FIST)) return;
                        if (!prompting.compareAndSet(false, true)) {
                            param.setResult(null);
                            return;
                        }
                        param.setResult(null);            // 暂停派发，等用户选
                        prompt((Context) param.args[1], param, dice);
                    } catch (Throwable t) {
                        prompting.set(false);
                        replay(param);
                    }
                }
            });
            Log.d(T + "panel armed -> " + panel.getName() + "#" + method.getName());
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 随机数入口：Q(int,int) -> int。 */
    private static int hookRng() {
        Class<?> owner = Anchors.cls(Anchors.RNG);
        Method q = Silent.method(owner, "Q", int.class, int.class);
        if (q == null) {
            Log.d(T + "rng anchor missing");
            return 0;
        }
        try {
            q.setAccessible(true);
            XposedBridge.hookMethod(q, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 放在之后：让宿主先算完，内部状态一致，我们只覆盖展示结果
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
            Log.d(T + "rng armed -> " + owner.getName());
            return 1;
        } catch (Throwable ignored) {
            return 0;
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
                                        parked.set(dice ? (which - 1) : FIST_ORDER[which]);
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

    /** 放行被我们暂停的派发，且只放行一次。 */
    private static void finish(XC_MethodHook.MethodHookParam param) {
        try {
            replay(param);
        } finally {
            prompting.set(false);
            // 注意：这里不能清 parkedExpiry —— 用户选完点数是先放行派发、随机数随后才被调用，
            // 提前清掉窗口会让"自选点数"静默失效（永远回退成随机）。
        }
    }

    private static void replay(XC_MethodHook.MethodHookParam param) {
        try {
            Method m = panelMethod;
            if (m == null) return;
            Object target = Modifier.isStatic(m.getModifiers()) ? null : param.thisObject;
            XposedBridge.invokeOriginalMethod(m, target, param.args);
        } catch (Throwable ignored) {
            // 最坏情况就是这一下点击没反应
        }
    }

    private EmojiGame() {}
}
