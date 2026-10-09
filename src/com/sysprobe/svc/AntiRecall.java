package com.sysprobe.svc;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** 防撤回（双向，带气泡变暗与角标）。拦截分两条通道：收到的和发出的。 */
final class AntiRecall {

    private static final String T = "svc-recall: ";
    private static final String TYPE_KEY = ".sysmsg.$type";
    private static final String NEW_MSG_ID = ".sysmsg.revokemsg.newmsgid";
    private static final String REPLACE_MSG = ".sysmsg.revokemsg.replacemsg";
    private static final String SESSION_KEY = ".sysmsg.revokemsg.session";
    private static final String REVOKE_TYPE = "revokemsg";
    private static final String SELF_MARK = "你撤回";

    private static final String MSG_CLASS = "com.tencent.mm.storage.e9";
    private static final String ADAPTER_CLASS = "com.tencent.mm.ui.chatting.adapter.k";
    private static final String SCENE_CLASS = "com.tencent.mm.modelsimple.d1";
    private static final String F_SVR_ID = "field_msgSvrId";
    private static final String F_TALKER = "field_talker";
    private static final String VIEWITEMS_PKG = "com.tencent.mm.ui.chatting.viewitems";

    private static final char KEY_SEP = '\u001F';
    private static final float RECALL_ALPHA = 0.45f;
    private static final String BADGE_OTHER = "对方撤回";
    private static final String BADGE_SELF = "自己撤回";

    /** 组合键 -> true 表示我方撤回，false 表示对方。 */
    private static final Map<String, Boolean> recalled = new ConcurrentHashMap<>();
    /** scene 实例 -> 它即将撤回的消息。 */
    private static final Map<Object, Object> pendingScene =
            Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    /** 行 -> 它当前绑定的组合键。 */
    private static final Map<View, String> bound =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 行 -> 已应用的样式，便于复用前精确还原。 */
    private static final Map<View, Style> styled =
            Collections.synchronizedMap(new WeakHashMap<View, Style>());

    private static final class Style {
        View content;
        float previousAlpha = 1f;
        View badge;
    }

    private static volatile boolean feasibilityProbed = false;
    private static final Set<String> barked = ConcurrentHashMap.newKeySet();

    // ── persistence ──────────────────────────────────────────────────────────
    // 存在模块自己的私有目录（不落微信目录），并做了混淆，磁盘上读不出内容；只存键和方向，不存消息内容。

    private static final String STORE_FILE = ".st";
    private static final int XOR_KEY = 0x5A;

    private static void persistAsync() {
        try {
            final StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Boolean> e : recalled.entrySet()) {
                sb.append(e.getValue() ? '1' : '0').append(e.getKey()).append('\n');
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] raw = sb.toString().getBytes("UTF-8");
                        for (int i = 0; i < raw.length; i++) raw[i] ^= XOR_KEY;
                        java.io.FileOutputStream out =
                                new java.io.FileOutputStream(
                                        new java.io.File(Features.app.getFilesDir(), STORE_FILE));
                        out.write(raw);
                        out.close();
                    } catch (Throwable ignored) {
                        // 尽力而为
                    }
                }
            }, "p" + Integer.toHexString((int) (System.nanoTime() & 0xFFFF)));
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
            // 尽力而为
        }
    }

    private static void restore() {
        try {
            java.io.File f = new java.io.File(Features.app.getFilesDir(), STORE_FILE);
            if (!f.exists() || f.length() <= 0 || f.length() > 4_000_000) return;
            byte[] raw = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(raw);
            in.close();
            if (n <= 0) return;
            for (int i = 0; i < n; i++) raw[i] ^= XOR_KEY;
            for (String line : new String(raw, 0, n, "UTF-8").split("\n")) {
                if (line.length() < 2) continue;
                recalled.put(line.substring(1), line.charAt(0) == '1');
            }
            Log.d(T + "restored " + recalled.size() + " recall entries");
        } catch (Throwable ignored) {
            // 尽力而为
        }
    }

    private static void bark(String msg) {
        if (barked.size() > 40) return;
        if (barked.add(msg)) Log.d(T + msg);
    }

    static void install() throws Throwable {
        restore();
        hookSysmsg();
        // 故意不挂 hookScene()：跳过撤回队列的收尾会让消息直接消失，完全不跳过又会让「撤回中」一直转圈，所以这条路径保持原样。
        hookBinder();
    }

    // ── inbound ──────────────────────────────────────────────────────────────

    private static void hookSysmsg() {
        Class<?> parser = Silent.cls(Features.loader, "com.tencent.mm.sdk.platformtools.fa");
        Method parse = Silent.method(parser, "d", String.class, String.class, String.class);
        if (parse == null) {
            Log.d(T + "sysmsg anchor missing");
            return;
        }
        XposedBridge.hookMethod(parse, new XC_MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object res = param.getResult();
                    if (!(res instanceof Map)) return;
                    Map<String, Object> map = (Map<String, Object>) res;
                    if (!REVOKE_TYPE.equals(map.get(TYPE_KEY))) return;

                    Object newId = map.get(NEW_MSG_ID);
                    if (newId == null) return;
                    Object session = map.get(SESSION_KEY);
                    String key = makeKey(session == null ? "" : String.valueOf(session),
                            String.valueOf(newId));

                    Object repl = map.get(REPLACE_MSG);
                    boolean selfRecall = repl != null && String.valueOf(repl).contains(SELF_MARK);

                    recall(key, selfRecall);
                    if (!selfRecall) {
                        map.put(TYPE_KEY, null);       // inbound: stop the rewrite
                        Log.d(T + "blocked inbound " + key);
                    }
                    // 发出去的提示保持原样，那条路径由 scene 的 hook 负责
                } catch (Throwable ignored) {
                    // 不打扰宿主's parse result
                }
            }
        });
        Log.d(T + "sysmsg armed");
    }

    // ── outbound ─────────────────────────────────────────────────────────────

    private static void hookScene() {
        Class<?> scene = Silent.cls(Features.loader, SCENE_CLASS);
        if (scene == null) {
            Log.d(T + "scene class missing");
            return;
        }
        for (Constructor<?> c : scene.getDeclaredConstructors()) {
            Class<?>[] pt = c.getParameterTypes();
            if (pt.length != 3 || !MSG_CLASS.equals(pt[0].getName())) continue;
            try {
                c.setAccessible(true);
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args[0] != null) pendingScene.put(param.thisObject, param.args[0]);
                        } catch (Throwable ignored) {
                            // 忽略
                        }
                    }
                });
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }

        int hooked = 0;
        for (Method m : scene.getDeclaredMethods()) {
            if (!"onGYNetEnd".equals(m.getName()) || m.getParameterTypes().length != 6) continue;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int errType = Silent.asInt(param.args[1]);
                            int errCode = Silent.asInt(param.args[2]);
                            if (errType != 0 || errCode != 0) return;

                            Object msg = pendingScene.remove(param.thisObject);
                            if (msg == null) return;

                            // 直接跳过方法体（与 WeKit 默认分支一致）：重放队列收尾会让行状态错乱、消息可能消失，比被改写更糟。
                            String key = keyOf(msg);
                            if (key != null) recall(key, true);

                            param.setResult(null);        // keep the row's content
                            Log.d(T + "kept outbound " + key);
                        } catch (Throwable ignored) {
                            // 有疑问时让原生流程继续
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }
        Log.d(T + "scene hooked=" + hooked);
    }

    // ── marking ──────────────────────────────────────────────────────────────

    private static void hookBinder() {
        Class<?> binder = Silent.cls(Features.loader, "xl5.g");
        if (binder == null) {
            Log.d(T + "binder class missing");
            return;
        }
        int hooked = 0;
        for (final Method m : binder.getDeclaredMethods()) {
            if (!"h".equals(m.getName()) || m.getParameterTypes().length != 6) continue;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View row = rowOf(param.args[0]);
                            if (!feasibilityProbed && row != null) {
                                feasibilityProbed = true;
                                Object tag = row.getTag();
                                Method mcm = null;
                                try {
                                    Class<?> tc = tag == null ? null : tag.getClass();
                                    while (tc != null && tc != Object.class && mcm == null) {
                                        for (Method mm : tc.getDeclaredMethods()) {
                                            if ("getMainContainerView".equals(mm.getName())
                                                    && mm.getParameterCount() == 0) {
                                                mcm = mm;
                                                break;
                                            }
                                        }
                                        tc = tc.getSuperclass();
                                    }
                                } catch (Throwable ignored) { }
                                bark("FEASIBILITY row=" + row.getClass().getName()
                                        + " tag=" + (tag == null ? "null" : tag.getClass().getName())
                                        + " mainContainer=" + (mcm != null));
                            }
                            if (row == null) return;

                            Object msg = findMsg(param.args[1], 2);
                            if (msg == null) {
                                Object pos = param.args[2];
                                if (pos instanceof Number) {
                                    msg = msgFromAdapter(param.thisObject, ((Number) pos).intValue());
                                }
                            }

                            // 先清干净：行会被 RecyclerView 复用，残留样式会串到别的消息上。
                            clearStyle(row);

                            if (msg == null) return;
                            String key = keyOf(msg);
                            if (key == null) return;
                            bound.put(row, key);
                            Boolean self = recalled.get(key);
                            if (self != null) applyStyle(row, self.booleanValue());
                        } catch (Throwable ignored) {
                            // 仅外观
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }
        Log.d(T + "marker hooked=" + hooked);
    }

    /** 记录一次撤回，并重排所有已经显示它的行。 */
    private static void recall(final String key, final boolean selfRecall) {
        recalled.put(key, selfRecall);
        persistAsync();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    List<View> hits = new ArrayList<>();
                    synchronized (bound) {
                        for (Map.Entry<View, String> e : bound.entrySet()) {
                            if (key.equals(e.getValue())) hits.add(e.getKey());
                        }
                    }
                    Boolean self = recalled.get(key);
                    for (View v : hits) applyStyle(v, self != null && self);
                } catch (Throwable ignored) {
                    // 仅外观
                }
            }
        });
    }

    // ── styling ──────────────────────────────────────────────────────────────

    private static void applyStyle(View row, boolean selfRecall) {
        if (styled.containsKey(row)) return;
        View content = contentOf(row);
        if (content == null) return;
        Style st = new Style();
        st.content = content;
        st.previousAlpha = content.getAlpha();
        try {
            content.setAlpha(RECALL_ALPHA);
        } catch (Throwable ignored) {
            return;
        }
        st.badge = makeBadge(row, content, selfRecall);
        styled.put(row, st);
    }

    private static void clearStyle(View row) {
        Style st = styled.remove(row);
        if (st == null) return;
        try {
            if (st.content != null) st.content.setAlpha(st.previousAlpha);
        } catch (Throwable ignored) {
            // 仅外观
        }
        try {
            if (st.badge != null && st.badge.getParent() instanceof ViewGroup) {
                ((ViewGroup) st.badge.getParent()).removeView(st.badge);
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 小角标，必须带布局参数一起加（不带不安全）。 */
    private static View makeBadge(View row, View anchor, boolean selfRecall) {
        try {
            if (!(row instanceof ViewGroup)) return null;
            TextView tv = new TextView(row.getContext());
            tv.setText(selfRecall ? BADGE_SELF : BADGE_OTHER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            tv.setTextColor(Color.parseColor("#E57373"));
            tv.setPadding(6, 2, 6, 2);
            ((ViewGroup) row).addView(tv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            // 居中到行上：子视图参与行的 LinearLayout，偏移要在测量后用 translation 施加，用布局会把气泡挤开。
            final TextView badge = tv;
            row.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        badge.setTranslationX(
                                Math.max(0f, (row.getWidth() - badge.getWidth()) / 2f));
                        badge.setTranslationY(
                                Math.max(0f, (row.getHeight() - badge.getHeight()) / 2f));
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
            return tv;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String makeKey(String talker, String svrId) {
        return (talker == null ? "" : talker) + KEY_SEP + svrId;
    }

    /** 直接从消息对象取组合键。 */
    private static String keyOf(Object msg) {
        try {
            Field fTalker = Silent.fieldDeep(msg.getClass(), F_TALKER);
            Field fId = Silent.fieldDeep(msg.getClass(), F_SVR_ID);
            Object talker = Silent.get(fTalker, msg);
            Object id = Silent.get(fId, msg);
            if (!(id instanceof Number)) return null;
            return makeKey(talker == null ? "" : String.valueOf(talker),
                    String.valueOf(((Number) id).longValue()));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 气泡/内容容器，不是整行。 */
    private static View contentOf(View row) {
        try {
            Object holder = row.getTag();
            if (holder != null) {
                Method m = Silent.anyMethod(holder.getClass(), "getMainContainerView");
                if (m != null) {
                    Object v = Silent.call(m, holder);
                    if (v instanceof View) return (View) v;
                }
            }
        } catch (Throwable ignored) {
            // 继续往下 to the DFS fallback
        }
        return findTagged(row);
    }

    /** 兜底：从深到浅找带 viewitems 标记的可见视图。 */
    private static View findTagged(View v) {
        try {
            Object tag = v.getTag();
            if (tag != null
                    && tag.getClass().getName().startsWith(VIEWITEMS_PKG)
                    && v.getVisibility() == View.VISIBLE) {
                return v;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findTagged(g.getChildAt(i));
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }

    private static boolean hasOnSceneEnd(Class<?> iface) {
        try {
            for (Method m : iface.getDeclaredMethods()) {
                if ("onSceneEnd".equals(m.getName())) return true;
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return false;
    }

    private static Object findMsg(Object value, int depth) {
        if (value == null || depth < 0) return null;
        if (MSG_CLASS.equals(value.getClass().getName())) return value;
        if (value instanceof View || value instanceof CharSequence) return null;
        try {
            Class<?> c = value.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray()) continue;
                    f.setAccessible(true);
                    Object nested = f.get(value);
                    if (nested == null) continue;
                    if (MSG_CLASS.equals(nested.getClass().getName())) return nested;
                    if (depth > 0) {
                        Object deeper = findMsg(nested, depth - 1);
                        if (deeper != null) return deeper;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }

    private static Object msgFromAdapter(Object self, int pos) {
        if (self == null) return null;
        try {
            Object adapter = null;
            Class<?> c = self.getClass();
            while (c != null && c != Object.class && adapter == null) {
                for (Field f : c.getDeclaredFields()) {
                    if (ADAPTER_CLASS.equals(f.getType().getName())) {
                        f.setAccessible(true);
                        adapter = f.get(self);
                        if (adapter != null) break;
                    }
                }
                c = c.getSuperclass();
            }
            if (adapter == null) return null;
            Method getItem = Silent.anyMethod(adapter.getClass(), "getItem");
            Object m = Silent.call(getItem, adapter, pos);
            if (m != null && MSG_CLASS.equals(m.getClass().getName())) return m;
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }

    /** 精确匹配 android.view.View，见类注释。 */
    private static View rowOf(Object holder) {
        if (holder == null) return null;
        try {
            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == View.class) {
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (v instanceof View) return (View) v;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }
}
