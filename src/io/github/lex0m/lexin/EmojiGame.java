package io.github.lex0m.lexin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Field;
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
    private static final String MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c";
    /** 猜拳的 kind id。 */
    private static final String MD5_MORRA = "9bd1281af3a31710a45b84d736363691";
    /** 表情面板点击时的 kind id（8.0.80 实测）。 */
    private static final int KIND_FIST = 428;
    private static final int KIND_DICE = 429;
    /** 菜单下标（1=石头 2=剪刀 3=布）-> 宿主内部取值。 */
    private static final int[] FIST_ORDER = {0, 1, 0, 2};

    private static final AtomicInteger parked = new AtomicInteger(-1);
    private static volatile long parkedExpiry = 0L;
    /** 防止连点时弹出多个框。 */
    private static final AtomicBoolean prompting = new AtomicBoolean(false);

    /** 仅诊断用：最后一个挂上的面板方法（放行时**不用**它，见 replay()）。 */
    private static volatile Method panelMethod;

    static void install() {
        int panel = hookPanel();
        int rng = hookRng();
        int armed = panel + rng;
        Status.armed("骰子/猜拳", armed);
        Status.note("骰子/猜拳", "选择框 " + panel + " · 随机数 " + rng);
        Log.d(T + "armed=" + armed + " panel=" + panel + " rng=" + rng);
    }

    /**
     * 面板入口：{@code (View, Context, int, 回调) -> void} 这一整个家族。
     *
     * 8.0.80 里**同签名的方法有 14 个**（{@code yr.p} / {@code yr.m0} /
     * {@code plugin.emoji.ui.l0} / {@code jx4.g0} / {@code q45.j} …），而锚点表只给了
     * {@code yr.p} 一个。上一版还只挑第一个匹配的方法 —— 于是「点骰子」这条链一次都没被挂上，
     * 选择框从来不弹，`骰子/猜拳` 也就成了永远 0 命中的死功能。
     *
     * 现在：锚点给的类 + 表里配置的同族类全都挂，并且每个方法都挂（a/b 两个都在）。
     * 判定「是不是骰子/猜拳」仍然靠 kind id，挂多了只是多几条入口，不影响正确性。
     */
    private static int hookPanel() {
        java.util.LinkedHashSet<Class<?>> owners = new java.util.LinkedHashSet<>();
        Class<?> primary = Anchors.cls(Anchors.EMOJI_PANEL);
        if (primary != null) owners.add(primary);
        // 同族类：表情面板那一层还有几个平行实现，逐个尝试（拿不到就跳过）
        for (String cn : PANEL_FAMILY) {
            try {
                Class<?> c = Loaders.load(cn);
                if (c != null) owners.add(c);
            } catch (Throwable ignored) {
                // 这个版本没有就跳过
            }
        }
        int hooked = 0;
        for (Class<?> owner : owners) {
            for (final Method m : owner.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 4 || m.getReturnType() != void.class) continue;
                if (pt[0] != View.class || pt[1] != Context.class || pt[2] != int.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Boolean game = diceGame(param);
                                if (game == null) return;
                                boolean dice = game.booleanValue();
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
                    hooked++;
                    panelMethod = m;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        Log.d(T + "panel hooked=" + hooked + " owners=" + owners.size());
        return hooked > 0 ? 1 : 0;
    }

    /**
     * 同族类名单（8.0.80 实测同签名的 14 处收敛成这几个类）。
     * 只放**类名**，方法靠签名匹配；某版本没有这些类时静默跳过。
     */
    private static final String[] PANEL_FAMILY = {
            "yr.m0", "com.tencent.mm.plugin.emoji.ui.l0",
            "q45.j", "jx4.g0", "jx4.w0", "tg5.c", "tg5.d",
    };

    /** 随机数入口：Q(int,int) -> int。 */
    /**
     * 判断这次点的是不是骰子/猜拳 —— 照抄 wcx 的做法：面板点击方法的第 4 个参数是回调对象，
     * 它身上有一个 IEmojiInfo 字段，取 md5 比对即可（跟列表位置、kind id 都无关）。
     * TRUE=骰子 / FALSE=猜拳 / null=不是游戏。
     */
    private static Boolean diceGame(XC_MethodHook.MethodHookParam p) {
        // ① 首选：第 3 个参数就是 kind id（8.0.80 实测 429=骰子 / 428=猜拳）。
        //
        // 以前这里走的是「第 4 个参数（回调对象）里找 IEmojiInfo 字段」这条路 ——
        // 8.0.80 上第 4 个参数是 vr.u0（Kotlin lambda，只有一个 int 字段），
        // 根本没有 IEmojiInfo ⇒ 永远返回 null ⇒ 选择框从来不弹，骰子/猜拳彻底哑掉。
        try {
            int kind = Silent.asInt(p.args[2]);
            // 诊断：把真实 kind 记进状态。用户点一次骰子/猜拳，status.txt 就能看到
            // 到底是几 —— 不用再靠猜常量。命中时下面会覆盖成正常文案。
            Status.note("骰子/猜拳", "面板 kind=" + kind
                    + " · args=" + (p.args == null ? 0 : p.args.length)
                    + " · 第4参=" + (p.args != null && p.args.length > 3 && p.args[3] != null
                        ? p.args[3].getClass().getSimpleName() : "null"));
            if (kind == KIND_DICE) return Boolean.TRUE;
            if (kind == KIND_FIST) return Boolean.FALSE;
        } catch (Throwable ignored) {
            // 落到下一条
        }
        // ② 兼容旧版：回调对象里还带 IEmojiInfo 的版本
        try {
            Object cb = p.args.length > 3 ? p.args[3] : null;
            if (cb == null) return null;
            Object emoji = null;
            for (Field f : cb.getClass().getDeclaredFields()) {
                if (S.WX_P6.equals(f.getType().getName())) {
                    f.setAccessible(true);
                    emoji = f.get(cb);
                    break;
                }
            }
            if (emoji == null) return null;
            Object md5 = emoji.getClass().getMethod("getMd5").invoke(emoji);
            if (MD5_DICE.equals(md5)) return Boolean.TRUE;
            if (MD5_MORRA.equals(md5)) return Boolean.FALSE;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

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
                        int type = Silent.asInt(param.args[0]);
                        if (type != 2 && type != 5) return;
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
        Status.hit("骰子/猜拳");        // 选择框真的弹出来了才算命中
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
                                        // 菜单下标不能直接当宿主取值：骰子是 which-1（0..5），
                                        // 猜拳要过 FIST_ORDER（1=石头 2=剪刀 3=布 -> 宿主值）。
                                        // v1.6.0 重构时把这两步映射丢了，FIST_ORDER 定义了从没用过
                                        // —— 猜拳石头/布错位、骰子整体偏移一格。按 v1.3 的实现改回来。
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
            // ★ 必须用 param.method —— 这次真正被拦下的那个方法，
            //   不能用静态字段 panelMethod：现在同族挂了十几个入口，
            //   那个字段指向的是**最后一个挂上的**方法，不是这次触发的这一个
            //   ⇒ 重放会调用到别的方法（或者干脆不是同一个类上的静态成员），
            //   表现就是「选择框弹了、选完没有反应」。
            java.lang.reflect.Member mem = param.method;
            if (!(mem instanceof Method)) return;
            Method m = (Method) mem;
            Object target = Modifier.isStatic(m.getModifiers()) ? null : param.thisObject;
            XposedBridge.invokeOriginalMethod(m, target, param.args);
        } catch (Throwable t) {
            // 最坏情况就是这一下点击没反应；把原因记进状态，方便现场判断
            Status.note("骰子/猜拳", "重放失败：" + t.getClass().getSimpleName());
        }
    }

    private EmojiGame() {}
}
