package io.github.lex0m.lexin;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 转发增强 · 探针（临时，用来在真机上确认"已选消息数"到底是哪个方法）。
 *
 * 只在诊断日志打开时输出，用来定位挂点；定位完就把结论写进版本表的槽位。
 */
final class FwdProbe {

    private static final String T = "svc-fwd: ";

    static void probe() {
        if (!Log.runtimeOn()) return;                 // 只在诊断模式下工作
        // ① 聊天适配器里两个无参 int 方法，看谁跟着选中数变
        Class<?> adapter = Anchors.cls(Anchors.CHAT_ADAPTER);
        if (adapter != null) {
            Hooker.count("adapter#" + adapter.getSimpleName(), adapter);
        }
        // ② 多选组件类（MicroMsg.ChatItemQuickSelectComponent 那个）
        for (Class<?> c : Anchors.all("chat_quick_select")) {
            Hooker.count("quick#" + c.getSimpleName(), c);
        }
        // ③ 长按菜单构造：把调用栈里出现过的微信类名打出来，用来定位菜单挂点
        try {
            Class<?> menu = Class.forName("com.tencent.mm.ui.chatting.component.a1", false, Features.loader);
            for (final Method m : menu.getDeclaredMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Log.d(T + "component.a1#" + m.getName() + " = " + p.getResult());
                    }
                });
            }
        } catch (Throwable ignored) {
            // 这个版本没有就算了
        }
        menuStackProbe();
        popupProbe();
        Log.d(T + "probe armed");
    }

    /**
     * 抓长按菜单的构造点：hook 系统 MenuBuilder.add(...)（所有菜单项都从这里加），
     * 谁在调它、以及调用方栈里的微信类，就是菜单构造点。
     */
    private static void menuStackProbe() {
        try {
            Class<?> mb = Class.forName("com.android.internal.view.menu.MenuBuilder",
                    false, Features.loader);
            XposedBridge.hookAllMethods(mb, "add", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        for (StackTraceElement e : st) {
                            String cn = e.getClassName();
                            if (cn.startsWith("android.") || cn.startsWith("java.")
                                    || cn.startsWith("com.android.internal")) continue;
                            sb.append(cn).append('#').append(e.getMethodName()).append(" <- ");
                            if (++n >= 4) break;
                        }
                        Log.d(T + "menu.add caller: " + sb);
                    } catch (Throwable ignored) {
                        // 探针永不抛
                    }
                }
            });
        } catch (Throwable t) {
            Log.d(T + "menu probe failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 弹窗探针：长按菜单是个弹窗，无论它走 PopupWindow 还是 Dialog，都要经过 show*。
     * 把调用栈里的微信类打出来 —— 那就是菜单构造点。
     */
    private static void popupProbe() {
        for (String cn : new String[]{
                "android.widget.PopupWindow", "android.app.Dialog"}) {
            try {
                Class<?> c = Class.forName(cn, false, Features.loader);
                XposedBridge.hookAllMethods(c, "show", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        dumpStack("show", p.thisObject);
                    }
                });
                if ("android.widget.PopupWindow".equals(cn)) {
                    XposedBridge.hookAllMethods(c, "showAtLocation", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            dumpStack("showAtLocation", p.thisObject);
                        }
                    });
                    XposedBridge.hookAllMethods(c, "showAsDropDown", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            dumpStack("showAsDropDown", p.thisObject);
                        }
                    });
                }
            } catch (Throwable ignored) {
                // 没有就算了
            }
        }
    }

    private static void dumpStack(String from, Object self) {
        try {
            // ① 弹窗对象自己的类名 —— 最直接的答案（微信自研的菜单弹窗会在这里现形）
            String cls = self == null ? "?" : self.getClass().getName();
            // ② 调用栈：不过滤、直接打前 12 帧（before 回调里原始调用方还在栈上）
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                if (e.getClassName().startsWith("io.github.lex0m.lexin")) continue;   // 自己的帧不要
                sb.append(e.getClassName()).append('#').append(e.getMethodName()).append(" <- ");
                if (++n >= 12) break;
            }
            Log.d(T + "popup." + from + " self=" + cls + "  stack=" + sb);
        } catch (Throwable ignored) {
            // 探针永不抛
        }
    }

    private static void unused() {
    }

    private static final class Hooker {
        static void count(final String tag, Class<?> c) {
            for (final Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt != int.class && rt != long.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Log.d(T + tag + "#" + m.getName() + " = " + p.getResult());
                        }
                    });
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
    }

    private FwdProbe() {}
}
