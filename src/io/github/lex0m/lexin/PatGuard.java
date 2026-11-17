package io.github.lex0m.lexin;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 拍一拍 · 防误触（v1.7）。
 *
 * 为什么从这个点下手（而不是去 hook 发消息）：
 *   拍一拍的发送链（`PatMsgExtension → NetSceneSendPat` / CGI 849 `sendpat`）在 8.0.80 上
 *   被 Tinker 补丁替换，静态扫 dex 拿不到（`sendPatMsg` 这类字符串在 base.apk 里
 *   存在但零代码引用）。但 **UI 层那一侧是干净的**：
 *   `com.tencent.mm.plugin.patmsg.ui.AvatarPatImageView` 这个类名从未混淆，
 *   而且它自己就暴露了 `getDoubleClickListener()` —— 双击头像的入口。
 *
 * 做法：把 `getDoubleClickListener()` 的返回值改成 null。
 *   ⇒ 微信拿不到双击监听器 ⇒ 双击头像不再拍出去。
 *   这是**纯本地拦截**：别人拍我照常、聊天记录里照常显示，只是我自己不会误触发。
 *
 * ★ 不做的事：不去模拟点击、不去构造网络请求（项目铁律 R1：不做输入注入）。
 */
final class PatGuard {

    private static final String T = "svc-pat: ";
    private static final String CLS = "com.tencent.mm.plugin.patmsg.ui.AvatarPatImageView";

    static final String NAME = "拍一拍防误触";

    /** 双击判定窗口（毫秒）。两次按下间隔小于它就算一次双击。 */
    private static final long DOUBLE_MS = 320L;
    private static volatile long lastDown = 0L;
    /**
     * 正在"吞手势"的窗口（v1.7.6）。
     *
     * 只吃掉第二次 ACTION_DOWN 不够 —— 微信有可能是在 **UP** 或 MOVE 上判定双击的
     * （真机第一版 hits 涨了但拍一拍照发，就是这个原因）。所以判定双击之后，
     * 把这一小段时间内该 View 的**所有**触摸事件都吃掉，直到抬手为止。
     */
    private static volatile boolean swallowing = false;

    /** 双击监听接口（8.0.80 = nw3.k，方法 a(View)Z）。 */
    private static final String IFACE = "nw3.k";
    /** 持有监听器的头像 View（8.0.80 = com.tencent.mm.plugin.patmsg.ui.AvatarPatImageView）。 */
    private static final String VIEW = "com.tencent.mm.plugin.patmsg.ui.AvatarPatImageView";

    static void install() {
        if (Status.ok(NAME)) return;
        try {
            if (!Prefs.onDefault(Prefs.K_PAT_BLOCK)) {
                Status.armed(NAME, 0);
                Status.note(NAME, "未开启（默认关，开了重启微信才挂）");
                return;
            }
        } catch (Throwable ignored) {
        }

        // ★ v1.7.3 重做（原来的 onTouchEvent 方案会把微信触摸卡死，已废弃）。
        //
        // 依据 WeKit 的 DisablePat 实现 + 本机 dex 实测：
        //   微信的头像双击走 `nw3.l#getDoubleClickListener() -> nw3.k`，
        //   真正回调是 `nw3.k#a(View)Z`（返回 boolean，true = 已消费）。
        //   所以最干净的做法不是拦触摸，而是**给这个 View 换一个监听器**：
        //   动态代理实现 nw3.k，`a()` 永远返回 true ⇒ 双击被消费、拍一拍不发出。
        //
        // 代价：不碰 MotionEvent，一次都不碰 —— 上一版的教训。
        Class<?> iface = Silent.cls(Features.loader, IFACE);
        Class<?> view = Silent.cls(Features.loader, VIEW);
        if (iface == null || view == null) {
            Status.armed(NAME, 0);
            Status.note(NAME, "当前微信版本类名变了（未适配）");
            return;
        }
        if (!iface.isInterface()) {
            Status.armed(NAME, 0);
            Status.note(NAME, "双击接口形态变了（未适配）");
            return;
        }

        Method target = null;
        for (Method m : view.getDeclaredMethods()) {
            if ("getDoubleClickListener".equals(m.getName()) && m.getParameterCount() == 0) {
                target = m;
                break;
            }
        }
        if (target == null) {
            Status.armed(NAME, 0);
            Status.note(NAME, "View 上没有 getDoubleClickListener（未适配）");
            return;
        }

        final Class<?> fi = iface;
        try {
            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_PAT_BLOCK)) return;
                        if (p.getResult() == null) return;      // 本来就没监听器，别造一个
                        p.setResult(swallow(fi));
                        Status.hit(NAME);
                    } catch (Throwable ignored) {
                    }
                }
            });
            Status.armed(NAME, 1);
            Status.note(NAME, "双击已消费（换监听器实现，不碰触摸；单击/长按正常）");
            Log.d(T + "armed: proxy listener for " + IFACE);
        } catch (Throwable t) {
            Status.armed(NAME, 0);
            Status.note(NAME, "挂载失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 造一个"什么都吃"的监听器：`a(View)Z` 返回 true ⇒ 双击被消费。
     *
     * 用动态代理而不是自己实现接口 —— 接口方法名是混淆的（`a`），
     * 代理只要按签名判断，方法名变了也不影响。
     */
    private static Object swallow(final Class<?> iface) {
        return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method m, Object[] args) {
                        String n = m.getName();
                        Class<?> r = m.getReturnType();
                        if ("toString".equals(n)) return "lexin-pat-guard";
                        if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                        if ("equals".equals(n)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                        // 真正的回调：a(View)Z —— 一律 true（已消费）
                        if (r == boolean.class || r == Boolean.class) return Boolean.TRUE;
                        if (r == void.class) return null;
                        if (r == int.class || r == Integer.class) return 0;
                        if (r == long.class || r == Long.class) return 0L;
                        return null;
                    }
                });
    }

    private PatGuard() {}
}
