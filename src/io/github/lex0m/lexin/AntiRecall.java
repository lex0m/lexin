package io.github.lex0m.lexin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 防撤回（双向）。
 *
 * 拦截：微信收到「对方撤回」时会把原消息改写成一条系统提示。我们在 sysmsg 解析结果里
 * 抹掉 $.sysmsg.$type，微信就不认这条撤回指令 —— 原消息原样留在列表里。
 *
 * 标记：被撤回的消息压暗，并在**气泡旁边**贴一个小胶囊标签标出方向。
 * 标签用 ViewOverlay 画（不参与布局、不挤压气泡），并且画之前会做一次矩形相交判定 ——
 * 只有在不压住气泡的空白处才画，压住就退到另一侧，两侧都放不下就不画（只压暗）。
 * 这是 v1.1 修掉的问题：v1.0 把标签居中贴到行上，正好盖在原消息上面。
 *
 * 记录只存「会话+消息id+方向」，不存任何消息内容，且写进微信自己的配置里 ——
 * 磁盘上不留模块专属文件。
 */
final class AntiRecall {

    private static final String T = "svc-recall: ";

    private static final String TYPE_KEY = ".sysmsg.$type";
    private static final String NEW_MSG_ID = ".sysmsg.revokemsg.newmsgid";
    private static final String REPLACE_MSG = ".sysmsg.revokemsg.replacemsg";
    private static final String SESSION_KEY = ".sysmsg.revokemsg.session";
    private static final String REVOKE_TYPE = "revokemsg";
    private static final String SELF_MARK = "你撤回";

    private static final String F_SVR_ID = "field_msgSvrId";
    private static final String F_TALKER = "field_talker";
    private static final String VIEWITEMS_PKG = "com.tencent.mm.ui.chatting.viewitems";

    private static final char KEY_SEP = '\u001F';
    /** 被撤回的消息压暗到这个透明度：能看出「变淡了」，文字仍然看得清。 */
    private static final float RECALL_ALPHA = 0.55f;
    private static final String BADGE_OTHER = "对方撤回";
    private static final String BADGE_SELF = "自己撤回";
    /** 记录上限：超了按最久没用过的丢，避免无限长。 */
    private static final int MAX_ENTRIES = 300;
    private static final String STORE_KEY = "rl";

    /** 组合键 -> true=我方撤回 / false=对方撤回。LRU 有上限。 */
    private static final Map<String, Boolean> recalled =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** 行 -> 它当前绑定的组合键。 */
    private static final Map<View, String> bound =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 行 -> 已应用的标记，便于复用前精确还原。 */
    private static final Map<View, Mark> marked =
            Collections.synchronizedMap(new WeakHashMap<View, Mark>());

    private static final class Mark {
        View content;
        float previousAlpha = 1f;
        Drawable tag;
        /** 行原本的底部内边距（-1 = 没改过），给标签腾地方时临时加大。 */
        int paddingBottom = -1;
    }

    private static volatile boolean scheduled = false;
    private static volatile long lastSave = 0L;
    /** 落盘串行化：撤回密集时别每次 new 线程、也别并发写同一个键。 */
    private static final java.util.concurrent.ExecutorService SAVER =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "s" + Integer.toHexString((int) (System.nanoTime() & 0xFFF)));
                    t.setDaemon(true);
                    return t;
                }
            });

    // ── 记录读写 ────────────────────────────────────────────────────────────

    /** 撤回记录存宿主配置里（key:talker 0x1F svrId，值：0/1 方向）。 */
    private static void persist(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastSave < 1500L) {
            if (scheduled) return;
            scheduled = true;
        } else {
            lastSave = now;
        }
        SAVER.execute(new Runnable() {
            @Override
            public void run() {
                scheduled = false;
                try {
                    StringBuilder sb = new StringBuilder(4096);
                    synchronized (recalled) {
                        for (Map.Entry<String, Boolean> e : recalled.entrySet()) {
                            sb.append(e.getValue() ? '1' : '0').append(e.getKey()).append('\n');
                        }
                    }
                    Storage.setStr(STORE_KEY, sb.toString());
                } catch (Throwable ignored) {
                    // 尽力而为
                }
            }
        });
    }

    private static void restore() {
        try {
            String blob = Storage.str(STORE_KEY, "");
            if (blob == null || blob.isEmpty()) return;
            int n = 0;
            for (String line : blob.split("\n")) {
                if (line.length() < 2) continue;
                recalled.put(line.substring(1), line.charAt(0) == '1');
                if (++n >= MAX_ENTRIES) break;
            }
            Log.d(T + "restored " + n);
        } catch (Throwable ignored) {
            // 尽力而为
        }
    }

    // ── 安装 ────────────────────────────────────────────────────────────────

    static void install() {
        restore();
        boolean core = hookSysmsg();
        boolean binder = hookBinder();
        // 兜底路径会挂在框架的热方法上。能走版本表的绑定器就不挂，少一分痕迹。
        if (!binder) hookAdapters();
        Status.set("消息防撤回", core);
        Status.note("消息防撤回", core ? "撤回指令已在本地失效，原消息保留" : "当前微信版本未适配");
    }

    /** 撤回后，把屏幕上已经画出这条消息的行全部重刷一遍标记。 */
    private static void onRecalled(final String key) {
        persist(false);
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
                    synchronized (recalled) {
                        Boolean self = recalled.get(key);
                        for (View v : hits) mark(v, self != null && self);
                    }
                } catch (Throwable ignored) {
                    // 仅外观
                }
            }
        });
    }

    // ── 拦截：收到的撤回 ────────────────────────────────────────────────────

    private static boolean hookSysmsg() {
        Class<?> parser = Anchors.cls(Anchors.SYSMSG);
        if (parser == null) {
            Log.d(T + "sysmsg anchor missing");
            return false;
        }
        Method parse = Silent.method(parser, "d", String.class, String.class, String.class);
        if (parse == null) {
            Log.d(T + "sysmsg method missing");
            return false;
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
                    if (!Prefs.onDefault(Prefs.K_RECALL)) return;

                    Object newId = map.get(NEW_MSG_ID);
                    if (newId == null) return;
                    Object session = map.get(SESSION_KEY);
                    String key = makeKey(session == null ? "" : String.valueOf(session),
                            String.valueOf(newId));

                    Object repl = map.get(REPLACE_MSG);
                    boolean selfRecall = repl != null && String.valueOf(repl).contains(SELF_MARK);
                    recalled.put(key, selfRecall);
                    if (!selfRecall) {
                        // 抹掉类型：微信不再认这条撤回指令，原消息保留，也不会再插一条撤回提示
                        map.put(TYPE_KEY, null);
                    }
                    onRecalled(key);
                } catch (Throwable ignored) {
                    // 不打扰宿主的解析结果
                }
            }
        });
        Log.d(T + "sysmsg armed -> " + parser.getName());
        return true;
    }

    // ── 标记路径一：聊天行绑定器（版本表命中时最准） ────────────────────────

    private static boolean hookBinder() {
        Class<?> binder = Anchors.cls(Anchors.CHAT_BINDER);
        if (binder == null) return false;
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
                            if (row == null) return;
                            Object msg = findMsg(param.args[1], 2);
                            if (msg == null) {
                                Object pos = param.args[2];
                                if (pos instanceof Number) {
                                    msg = msgFromAdapter(param.thisObject,
                                            ((Number) pos).intValue());
                                }
                            }
                            bind(row, msg);
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
        Log.d(T + "binder hooked=" + hooked);
        return hooked > 0;
    }

    // ── 标记路径二：框架适配器（类名随版本变，但框架 API 不变） ─────────────

    /**
     * 兜底路径：不管微信怎么改类名，聊天列表总得走 ListView 或 RecyclerView。
     * 我们挂在框架侧的适配器方法上，只处理「适配器正是聊天列表」的那种情况。
     */
    private static void hookAdapters() {
        // ListView 系（老版本 / 部分页面）
        try {
            Class<?> base = Class.forName("android.widget.BaseAdapter", false, Features.loader);
            XposedBridge.hookAllMethods(base, "getView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!isChatAdapter(param.thisObject)) return;
                        Object pos = param.args[0];
                        Object row = param.getResult();
                        if (!(row instanceof View) || !(pos instanceof Number)) return;
                        Object msg = msgFromAdapter(param.thisObject, ((Number) pos).intValue());
                        bind((View) row, msg);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
        } catch (Throwable ignored) {
            // 没有这个类就跳过
        }

        // RecyclerView 系（8.0.x 主力路径）
        try {
            Class<?> rv = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter",
                    false, Features.loader);
            XposedBridge.hookAllMethods(rv, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!isChatAdapter(param.thisObject)) return;
                        Object holder = param.args[0];
                        Object pos = param.args[1];
                        if (holder == null || !(pos instanceof Number)) return;
                        View row = holderItemView(holder);
                        if (row == null) return;
                        Object msg = findMsg(holder, 2);
                        if (msg == null) {
                            msg = msgFromAdapter(param.thisObject, ((Number) pos).intValue());
                        }
                        bind(row, msg);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            });
        } catch (Throwable ignored) {
            // 没有这个类就跳过
        }
    }

    /** 这个适配器是不是聊天列表：类名对上锚点即可（字符串比较，开销可以忽略）。 */
    private static boolean isChatAdapter(Object adapter) {
        if (adapter == null) return false;
        String want = adapterName;
        return want != null && want.equals(adapter.getClass().getName());
    }

    private static volatile String adapterName;

    private static View holderItemView(Object holder) {
        try {
            Field f = firstFieldOfType(holder.getClass(), View.class);
            Object v = f == null ? null : f.get(holder);
            if (v instanceof View) return (View) v;
        } catch (Throwable ignored) {
            // 继续往下
        }
        try {
            Method m = Silent.anyMethod(holder.getClass(), "getItemView");
            Object v = Silent.call(m, holder);
            if (v instanceof View) return (View) v;
        } catch (Throwable ignored) {
            // 忽略
        }
        return rowOf(holder);
    }

    /** 取这个类（沿父类链）里第一个指定类型的字段：行的 View / ViewHolder 的 itemView 都靠它。 */
    private static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == type) {
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    // ── 行的绑定与标记 ──────────────────────────────────────────────────────

    private static void bind(View row, Object msg) {
        // 行会被 RecyclerView 复用：先无条件清掉上一次的标记，否则会串到别的消息上
        clearMark(row);
        if (msg == null || !Prefs.onDefault(Prefs.K_RECALL)) return;
        String key = keyOf(msg);
        bound.put(row, key);
        Boolean self;
        synchronized (recalled) {
            self = recalled.get(key);
        }
        // 只给「对方撤回」贴标记：自己撤回微信自己就有「你撤回了一条消息」，
        // 再叠一个标签就成了两个提示打架。
        if (self != null && !self) mark(row, false);
    }

    private static void mark(View row, boolean selfRecall) {
        if (row == null || marked.containsKey(row)) return;
        View content = contentOf(row);
        if (content == null) return;
        Mark m = new Mark();
        m.content = content;
        m.previousAlpha = content.getAlpha();
        try {
            content.setAlpha(RECALL_ALPHA);
        } catch (Throwable ignored) {
            return;
        }
        m.tag = attachTag(row, content, selfRecall);
        marked.put(row, m);
    }

    private static void clearMark(View row) {
        Mark m = marked.remove(row);
        if (m == null) return;
        try {
            if (m.content != null) m.content.setAlpha(m.previousAlpha);
        } catch (Throwable ignored) {
            // 仅外观
        }
        try {
            if (m.tag != null) row.getOverlay().remove(m.tag);
        } catch (Throwable ignored) {
            // 仅外观
        }
        try {
            if (m.paddingBottom >= 0) {
                row.setPadding(row.getPaddingLeft(), row.getPaddingTop(),
                        row.getPaddingRight(), m.paddingBottom);
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /**
     * 把「已撤回」胶囊贴在**气泡正下方**。
     *
     * 版式（v1.1 最终版）：
     *   行底部临时加一段内边距（只加高度，不挤压气泡），腾出标签的位置；
     *   标签水平居中于气泡、垂直紧贴气泡下沿。
     *
     * 为什么要临时加内边距：overlay 画在行内部、会被行边界裁掉，
     * 行原来几乎没有气泡以下的空间，直接往气泡下面画是看不见的。
     *
     * 为什么位置不再飘：所有尺寸都在绘制前重新量一次，量不到就等下一帧重来；
     * 以前是拿"绑定那一刻"的旧尺寸算，切窗口重排后就会跑到别处去。
     */
    private static Drawable attachTag(final View row, final View bubble, final boolean selfRecall) {
        try {
            Context ctx = row.getContext();
            final android.graphics.drawable.Drawable tag = makeTag(ctx,
                    selfRecall ? BADGE_SELF : BADGE_OTHER,
                    Color.WHITE, selfRecall ? 0xFF3B82F6 : 0xFFE5484D);
            final int gap = dp(ctx, 3);
            final int want = gap * 2 + tag.getIntrinsicHeight();

            final int[] tries = {0};
            Runnable place = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (++tries[0] > 12) return;          // 最多重试十来帧，别无限重投
                        Mark now = marked.get(row);
                        if (now == null || now.tag != tag) return;
                        if (row.getParent() == null) return;

                        // 还没量好就等下一帧，别拿 0 尺寸算
                        if (row.getWidth() <= 0 || row.getHeight() <= 0) {
                            row.postDelayed(this, 100L);
                            return;
                        }

                        // 腾地方：只动底边距
                        if (row.getPaddingBottom() < want) {
                            if (now.paddingBottom < 0) now.paddingBottom = row.getPaddingBottom();
                            row.setPadding(row.getPaddingLeft(), row.getPaddingTop(),
                                    row.getPaddingRight(), want);
                            row.postDelayed(this, 60L);
                            return;
                        }

                        int[] rowLoc = new int[2];
                        int[] bubLoc = new int[2];
                        row.getLocationInWindow(rowLoc);
                        bubble.getLocationInWindow(bubLoc);
                        int bl = bubLoc[0] - rowLoc[0];
                        int bt = bubLoc[1] - rowLoc[1];
                        int bw = bubble.getWidth();
                        int bh = bubble.getHeight();
                        if (bw <= 0 || bh <= 0) {
                            row.postDelayed(this, 100L);
                            return;
                        }
                        Rect b = new Rect(bl, bt, bl + bw, bt + bh);

                        int tw = tag.getIntrinsicWidth();
                        int th = tag.getIntrinsicHeight();
                        int rw = row.getWidth();
                        int rh = row.getHeight();

                        // 水平：居中于气泡（夹在行内）
                        int x = b.left + (b.width() - tw) / 2;
                        x = Math.max(gap, Math.min(x, rw - gap - tw));
                        // 垂直：紧贴气泡下沿
                        int y = b.bottom + gap;
                        if (y + th > rh - 1) y = Math.max(0, rh - th - 1);

                        tag.setBounds(x, y, x + tw, y + th);
                        row.getOverlay().remove(tag);
                        row.getOverlay().add(tag);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            };
            row.post(place);
            return tag;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean overlaps(int x, int y, int w, int h, Rect b) {
        return x < b.right && x + w > b.left && y < b.bottom && y + h > b.top;
    }

    private static int dp(Context ctx, int v) {
        try {
            return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable ignored) {
            return v * 2;
        }
    }

    /**
     * overlay 上画的小胶囊。
     *
     * 用 BitmapDrawable 而不是自绘 Drawable —— 挂在微信视图树上的东西只允许是框架类，
     * 一个模块自己的 Drawable 子类留在行视图的 overlay 里，等于把名字写脸上。
     */
    private static android.graphics.drawable.BitmapDrawable makeTag(
            Context ctx, String text, int fg, int bg) {
        float density = 1f;
        try {
            density = ctx.getResources().getDisplayMetrics().scaledDensity;
        } catch (Throwable ignored) {
            // 用默认值
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(fg);
        paint.setTextSize(9f * density);
        paint.setFakeBoldText(true);

        float padH = 6f * density;
        int w = (int) Math.ceil(paint.measureText(text) + padH * 2) + 2;
        int h = (int) Math.ceil(paint.getTextSize() * 1.7f) + 2;
        float radius = 7f * density;

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(bg);
        canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, bgPaint);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, padH, baseline, paint);

        return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
    }

    // ── 反射小工具 ──────────────────────────────────────────────────────────

    private static String makeKey(String talker, String svrId) {
        return (talker == null ? "" : talker) + KEY_SEP + svrId;
    }

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

    /** 气泡/内容容器（不是整行）。 */
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
            // 继续往下
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

    private static Object findMsg(Object value, int depth) {
        if (value == null || depth < 0) return null;
        String msgClass = msgClassName;
        if (msgClass == null) return null;
        if (msgClass.equals(value.getClass().getName())) return value;
        if (value instanceof View || value instanceof CharSequence) return null;
        try {
            Class<?> c = value.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray()) continue;
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    Object nested = f.get(value);
                    if (nested == null) continue;
                    if (msgClass.equals(nested.getClass().getName())) return nested;
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
            Method getItem = Silent.anyMethodDeep(self.getClass(), "getItem");
            if (getItem == null) return null;
            Object m = Silent.call(getItem, self, pos);
            if (m != null && m.getClass().getName().equals(msgClassName)) return m;
        } catch (Throwable ignored) {
            // 继续往下
        }
        return null;
    }

    /** 精确匹配 android.view.View，避免拿到 RecyclerView 之类的其它 View 字段。 */
    private static View rowOf(Object holder) {
        if (holder == null) return null;
        try {
            Field f = firstFieldOfType(holder.getClass(), View.class);
            if (f == null) return null;
            Object v = f.get(holder);
            return v instanceof View ? (View) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static volatile String msgClassName;

    /** 由 Features 在安装前调用：锚点解析一次，后面所有路径复用。 */
    static void prepare() {
        Class<?> msg = Anchors.cls(Anchors.MSG);
        msgClassName = msg == null ? "com.tencent.mm.storage.e9" : msg.getName();
        Class<?> adapter = Anchors.cls(Anchors.CHAT_ADAPTER);
        adapterName = adapter == null ? null : adapter.getName();
    }

    private AntiRecall() {}
}
