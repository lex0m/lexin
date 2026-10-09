package io.github.lex0m.lexin;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** 全屏背景 + 列表卡片化 + 圆角。背景只画在不滚动的层上；卡片是半透明纯色，滚动时零计算。 */
final class Beautify {

    private static final String T = "svc-beautify: ";

    /** 构建标记，每次编译递增，用来确认宿主跑的是哪个版本 */
    static final String BUILD_TAG = "1.0";

    // ── background ────────────────────────────────────────────────────────────

    static final String K_BG = "bf_bg";
    static final String BG_FILE = ".bg.dat";
    static final String K_BG_ALPHA = "bf_alpha";
    static final String K_BG_MODE = "bf_mode";
    /** 旧的百分比键，已被尺寸模式取代 */
    static final String K_BG_SCALE = "bf_scale";
    static final String K_BG_W = "bf_w";
    static final String K_BG_H = "bf_h";
    static final String K_BG_CX = "bf_cx";
    static final String K_BG_CY = "bf_cy";
    static final String K_BG_CW = "bf_cw";
    static final String K_BG_CH = "bf_ch";

    // ── frosted background ────────────────────────────────────────────────────

    static final String K_BLUR_ON = "bf_blur_on";
    static final String K_BLUR_RADIUS = "bf_blur_r";

    // ── rounded corners (general UI) ──────────────────────────────────────────

    static final String K_CORNER_ON = "bf_corner_on";
    static final String K_CORNER_DP = "bf_corner_dp";

    // ── chat rows as cards ────────────────────────────────────────────────────

    static final String K_CARD_ON = "bf_card_on";
    static final String K_CARD_ALPHA = "bf_card_alpha";
    static final String K_CARD_CORNER = "bf_card_corner";
    static final String K_CARD_INSET = "bf_card_inset";
    static final String K_CARD_GAP = "bf_card_gap";
    /** 卡片高度增量（dp，可为负）。微信行高由内容决定，所以按「原始高度 + 增量」做，原始值只记一次保证幂等。 */
    static final String K_CARD_H = "bf_card_h";
    static final String K_CARD_DARK = "bf_card_dark";

    /** 所有页面都透出壁纸，不只会话列表 */
    static final String K_ALL_PAGES = "bf_all_pages";

    /** 调试用：dump 视图树。页面处理按实际结构写，不靠猜 */
    static final String K_DUMP = "bf_dump";

    // 保留这些键只为设置界面能编译；玻璃与每卡带图是有意去掉的：卡片带图会跟着列表滚动，背景看起来在跟着走。
    static final String K_CARD_GLASS = "bf_card_glass";
    static final String K_CARD_BLUR_ON = "bf_card_blur_on";
    static final String K_CARD_BLUR_R = "bf_card_blur_r";
    static final String K_CARD_IMG = "bf_card_img";
    static final String K_CARD_CX = "bf_ccx";
    static final String K_CARD_CY = "bf_ccy";
    static final String K_CARD_CW = "bf_ccw";
    static final String K_CARD_CH = "bf_cch";
    static final String CARD_FILE = ".card.dat";

    /** 微信 8.0.78 的会话列表类名（取自锚点表） */
    private static final String CONV_LIST = S.CLS_CONV_LIST;
    private static final String CONV_RECYCLER =
            S.CLS_CONV_RECYCLER;
    private static final String BOTTOM_TAB = S.CLS_BTAB;

    /** 卡片尺寸上限，防止误设把列表弄坏 */
    private static final int MAX_CARD_INSET_DP = 60;
    private static final int MAX_CARD_CORNER_DP = 48;

    /** 标记我们自己的 setBackground 调用，让 veto 放行 */
    private static final ThreadLocal<Boolean> OUR_CALL = new ThreadLocal<>();

    private static final Map<View, Boolean> cornered =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 已清过内部底板的行。清理幂等但不免费（要逐个看子视图），滚动路径上要跳过已处理的。 */
    private static final Map<View, Boolean> fillsStripped =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 已画过壁纸的视图。与 cornered 分开两个 map：共用一个会互相抢占，导致标题栏丢圆角、部分页面丢背景。 */
    /** 每个视图的壁纸切片缓存。重复裁切会在主线程分配整屏 bitmap，滑动时明显卡顿。 */
    private static final Map<View, BarDrawable> sliceCache =
            Collections.synchronizedMap(new WeakHashMap<View, BarDrawable>());
    private static final Map<View, int[]> sliceGeo =
            Collections.synchronizedMap(new WeakHashMap<View, int[]>());

    private static final Map<View, Boolean> painted =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 顶部栏切片缓存：切页时微信会重设栏背景，有缓存才能立刻补回 */
    private static final Map<View, BarDrawable> barCache =
            Collections.synchronizedMap(new WeakHashMap<View, BarDrawable>());
    private static final Map<View, Boolean> watched =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 已挂 hook 的 adapter（按类名，每个列表一次） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> adapterHooked =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean scrollHooked = false;

    /** dump 节流：一次只写一帧、有总数上限。虚拟列表首帧只有已绑定的行，要滚动才会出现真正的行。 */
    private static final int MAX_LIST_DUMPS = 60;
    private static long lastListDump = 0L;
    private static int listDumps = 0;

    /** 自愈重绘的节流 */
    private static long lastHeal = 0L;

    /** 全局节流：把同一波重绘请求合并成一次 */
    private static long lastPaint = 0L;

    /** 整页 dump 的帧数上限 */
    private static final int MAX_PAGE_DUMPS = 20;
    private static long lastPageDump = 0L;
    private static int pageDumps = 0;

    /** 已隐藏的下拉条节点。微信每次动画都把它重新设为可见，这里直接拒绝 VISIBLE，否则滚动中会冒出第二份标题。 */
    private static final Map<View, Boolean> pullStripHidden =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 当前页面是否允许重排：只有主页四个 tab。服务页/收藏页是自带尺寸的网格，套卡片会出白块和错位，保持原样。 */
    private static volatile boolean cardsAllowed = true;

    private static Bitmap imgBg;
    /** 顶部栏用的不透明壁纸副本。半透明会让栏后的东西透出来，标题就渲染两次。 */
    private static Bitmap imgBgOpaque;
    private static String imgKey = "";

    /** 替换背景前记录的窗口原色（只记一次）。系统状态栏是透明的，窗口画什么就露出什么，所以要把它原来的颜色还给状态栏，图标才有对比度。 */
    private static int bandColour = 0;
    /** 当前应用到窗口的状态栏条高度，避免重复设置 */
    private static int bandH = -1;
    /** 已经装过背景的窗口（Window#getBackground 是隐藏 API，只能自己记） */
    private static WeakReference<android.view.Window> bgWin = null;

    // ── install ───────────────────────────────────────────────────────────────

    static void install() throws Throwable {
        if (!Storage.on(Storage.K_THEME, false)) return;
        prewarm();
        // 启动时不再写版本标记文件——它会在微信目录里留一个以模块命名的文件，等于指纹；标记只留在代码里。
        int n = 0;
        n += hookActivity();
        n += hookFragments();
        n += hookPageContainers();
        n += hookTaskBarHint();
        n += hookActionBar();
        n += hookVisibility();
        n += hookVeto();
        hookScroll();
        Storage.diag(T + "armed=" + n);
    }

    /** 启动时预解码一次壁纸，之后每页都是缓存命中；否则首个页面要在主线程解码整屏图，背景会晚几秒才出现 */
    private static void prewarm() {
        try {
            if (Features.app == null) return;
            int w = Features.app.getResources().getDisplayMetrics().widthPixels;
            int h = Features.app.getResources().getDisplayMetrics().heightPixels;
            if (w > 0 && h > 0) ensureImage(Features.app.getFilesDir(), w, h);
        } catch (Throwable ignored) {
            // 背景保持惰性，首次绘制时解码
        }
    }

    private static int hookActivity() {
        int hits = 0;
        try {
            for (Method m : Activity.class.getDeclaredMethods()) {
                if (!"onResume".equals(m.getName()) || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!Storage.on(Storage.K_THEME, false)) return;
                            if (!(p.thisObject instanceof Activity)) return;
                            final Activity act = (Activity) p.thisObject;
                            View decor = act.getWindow() == null ? null
                                    : act.getWindow().getDecorView();
                            if (decor == null) return;

                            defer(decor, 0);
                        } catch (Throwable ignored) {
                            // 仅外观
                        }
                    }
                });
                hits++;
            }
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 等 decor 有尺寸再画：onResume 时它是 0x0，零尺寸的切片等于没画 */
    private static void defer(final View decor, final int attempt) {
        if (attempt > 6) return;
        decor.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (decor.getWidth() <= 0 || decor.getHeight() <= 0) {
                        defer(decor, attempt + 1);
                        return;
                    }
                    paint(decor);
                    watchLayout(decor);
                } catch (Throwable ignored) {
                    // 仅外观
                }
            }
        }, attempt == 0 ? 60L : 200L);
    }

    // ── 页面结构处理（照成品实现的做法） ──
    //
    // 每个版本的页面层级路径：8.0.14+（8.0.78 一致）通讯录页的关键两层如下
    //
    //   LayoutListenerView                     <- page root
    //   [0] 页容器（不滚动，承载背景）／[0] NestedScrollView（滚动内容，即遮罩层）／[1] 列表
    //
    // 早先按尺寸猜容器、往容器上贴切片是错的：滚动容器上的切片会跟着内容走，与背后固定的壁纸错位。壁纸只能放在不滚动的那一层，它上面全部透明。
    private static final String BOUNCE = S.CLS_BOUNCE;
    private static final String LAYOUT_LISTENER = S.CLS_LAYOUT_LISTENER;

    private static void applyPageStructure(View v, int depth, int[] budget) {
        if (v == null || depth > 14 || budget[0] <= 0) return;
        try {
            String n = nameOf(v);
            if (n.contains(S.BOUNCE)
                    || n.contains(S.LAYOUT_LISTENER)) {
                // 页容器设为透明，不再画图
                //
                // 页容器会动（下拉时弹一下），贴切片就会跟着走、与固定壁纸错位——这正是「一下拉背景就坏、松手又回来」；壁纸放在不动的窗口层，这层只需让开。
                if (v.getBackground() != null) {
                    setBackgroundSafely(v, null);
                    budget[0]--;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View child = g.getChildAt(i);
                        if (child == null) continue;
                        String cn = nameOf(child);
                        if (cn.contains("RecyclerView") || cn.contains("ListView")) {
                            setBackgroundSafely(child, null);   // list is transparent too
                        }
                        // 滚动内容遮罩层无条件清透明，让下面的壁纸透出；按 drawable 类型判断会一直漏掉白色那种（微信的遮罩不总是纯色）。
                        if (cn.contains("NestedScrollView")) {
                            setBackgroundSafely(child, null);
                        }
                        applyPageStructure(child, depth + 1, budget);
                    }
                    return;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    applyPageStructure(g.getChildAt(i), depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    // ── painting ──────────────────────────────────────────────────────────────

    private static void paint(View decor) {
        int sw = decor.getWidth();
        int sh = decor.getHeight();
        if (sw <= 0 || sh <= 0) return;

        // 合并突发重绘：页面可见性、切页滚动、fragment resume、自愈会在同一瞬间都要重画，一次重画就是全树遍历加重设背景，连着跑就是频闪。
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPaint < 450L) return;
        lastPaint = nowMs;

        // 打开的会话完全不碰：微信允许用户自定义聊天背景，盖我们的图就是「把我聊天背景顶掉」；会话里也不需要卡片化。
        try {
            if (decor.getContext() instanceof Activity
                    && ((Activity) decor.getContext()).getClass().getName().contains(S.CHAT)) {
                return;
            }
        } catch (Throwable ignored) {
            // 当作普通页面处理
        }

        // 只重排主页四个 tab，其它页面保留自己的布局、只给壁纸（原因见 cardsAllowed）
        cardsAllowed = isMainPage(decor);

        // 按屏幕尺寸解码，与 prewarm 一致；用 decor 尺寸（本机少 44px）会算出不同缓存键，首帧就在主线程重解码整屏图，页面要等一会儿才出背景。
        // 会话页把窗口背景还给微信：它按背后颜色挑聊天标题栏深浅（深色壁纸给它 #111111），所以还原成微信自己的 #EDEDED。
        if (chatOnScreen(decor)) {
            try {
                if (decor.getContext() instanceof Activity) {
                    android.view.Window w = ((Activity) decor.getContext()).getWindow();
                    if (w != null && !bgPlainLight) {
                        w.setBackgroundDrawable(
                                new android.graphics.drawable.ColorDrawable(0xFFEDEDED));
                        bgPlainLight = true;
                    }
                }
            } catch (Throwable ignored) {
                // 仅外观
            }
            return;
        }
        bgPlainLight = false;

        android.util.DisplayMetrics dm = decor.getResources().getDisplayMetrics();
        if (!ensureImage(decor.getContext().getFilesDir(), dm.widthPixels, dm.heightPixels)) {
            return;
        }

        // 窗口背景在这里画（图就绪之后）：下拉露出的是窗口而不是列表，不画的话第一次下拉壁纸就像消失了。
        try {
            if (decor.getContext() instanceof Activity) {
                android.view.Window win = ((Activity) decor.getContext()).getWindow();
                if (win != null) {
                    if (bandColour == 0) bandColour = windowColour(decor, win);
                    int band = statusBarHeight(decor);
                    // 只在真的变化时才重设窗口背景：每次给一个新 drawable 等于整屏重绘，而这段会在滚动／布局／切页时跑，看起来就是壁纸在闪。
                    if (bgWin == null || bgWin.get() != win || band != bandH) {
                        bandH = band;
                        bgWin = new WeakReference<android.view.Window>(win);
                        win.setBackgroundDrawable(new TopBandDrawable(imgBg, band, bandColour));
                    }
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }

        int offX = (imgBg.getWidth() - sw) / 2;
        int offY = (imgBg.getHeight() - sh) / 2;

        // 不再做位图切片：会动的层（页容器弹动、标题栏跟着走）上的切片会与窗口错位，反复重切／重设就是频闪。
        //
        // 壁纸只留在不动的窗口层，其它层一律透明，让它透出来。
        //
        // 这个分工是下拉问题的正解：页容器与标题栏会动，画在会动层上的图都会分离，留下杂条和双标题。
        slice(decor, sw, sh, offX, offY, new int[]{60}, 0);
        applyPageStructure(decor, 0, new int[]{40});
        styleCards(decor);
        paintBars(decor);

        // 第二遍处理，真正让其它页面显示壁纸的一步：setBackgroundColor 的拦截做不到（微信在测量前就设色，尺寸判断只能看到 0x0），这里布局已存在，页面级不透明容器能可靠识别并清掉。
        if (Storage.on(K_ALL_PAGES, true)) {
            clearOpaquePages(decor, 0, new int[]{400});
        }

        // 临时测量手段：之前三次都靠猜微信用了什么，这次直接 dump 事实（类名/尺寸/真实背景类型）。
        if (Storage.on(K_DUMP, false)) {
            try {
                StringBuilder sb = new StringBuilder();
                dumpTree(decor, 0, sb);
                java.io.FileWriter w = new java.io.FileWriter(
                        new File(decor.getContext().getFilesDir(), "tree.txt"), false);
                w.write(sb.toString());
                w.close();
            } catch (Throwable ignored) {
                // 仅测量
            }
        }
    }

    private static void dumpTree(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 16) return;
        try {
            for (int i = 0; i < depth; i++) sb.append("  ");
            Drawable bg = v.getBackground();
            sb.append(nameOf(v))
                    .append("  ").append(v.getWidth()).append("x").append(v.getHeight())
                    .append("  shown=").append(v.isShown())
                    .append("  bg=").append(bg == null ? "null" : nameOf(bg));
            if (bg instanceof android.graphics.drawable.ColorDrawable) {
                sb.append("(col=0x")
                        .append(Integer.toHexString(
                                ((android.graphics.drawable.ColorDrawable) bg).getColor()))
                        .append(")");
            }
            if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((android.graphics.drawable.GradientDrawable) bg).getColor();
                sb.append(csl == null ? "(gd=multi)"
                        : "(gd=0x" + Integer.toHexString(csl.getDefaultColor()) + ")");
            }
            try {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    if (mlp.leftMargin != 0 || mlp.rightMargin != 0 || mlp.bottomMargin != 0) {
                        sb.append("  margin=").append(mlp.leftMargin).append(',')
                                .append(mlp.topMargin).append(',')
                                .append(mlp.rightMargin).append(',').append(mlp.bottomMargin);
                    }
                }
            } catch (Throwable ignoredMargin) {
                // 没有布局参数可报
            }
            // dump 里带文本和屏幕坐标，才能把某一行与截图对上（否则「那条白条是哪一行」只能猜）
            if (v instanceof android.widget.TextView) {
                try {
                    CharSequence cs = ((android.widget.TextView) v).getText();
                    if (cs != null && cs.length() > 0) {
                        String s = cs.toString().replace('\n', ' ');
                        if (s.length() > 14) s = s.substring(0, 14);
                        sb.append("  text=").append(s);
                    }
                } catch (Throwable ignoredText) {
                    // 不值得报告
                }
            }
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                sb.append("  at=").append(loc[0]).append(',').append(loc[1]);
            } catch (Throwable ignoredLoc) {
                // 位置是附加信息，不是必须
            }
            sb.append("\n");
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    dumpTree(g.getChildAt(i), depth + 1, sb);
                }
            }
        } catch (Throwable ignored) {
            // 仅测量
        }
    }

    /** 按帧写出列表真实的行结构（类名/尺寸/背景类型/边距），供按事实改代码，不靠猜 */
    private static void dumpListOnce(View list) {
        if (list == null || !Storage.on(K_DUMP, false)) return;
        long now = System.currentTimeMillis();
        int seq;
        synchronized (Beautify.class) {
            if (listDumps >= MAX_LIST_DUMPS || now - lastListDump < 700L) return;
            lastListDump = now;
            listDumps++;
            seq = listDumps;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("list=").append(nameOf(list))
                    .append("  ").append(list.getWidth()).append('x').append(list.getHeight())
                    .append("  children=")
                    .append(list instanceof ViewGroup ? ((ViewGroup) list).getChildCount() : -1)
                    .append("  frame=").append(seq)
                    .append('\n');
            dumpTree(list, 0, sb);
            String n = list.getClass().getSimpleName().replace('.', '_');
            File f = new File(list.getContext().getFilesDir(),
                    "L" + (seq < 10 ? "0" : "") + seq + "-" + n
                            + "-" + list.getWidth() + "x" + list.getHeight() + ".txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
            // 仅测量
        }
    }

    /** 清掉页面级容器的纯色底，让壁纸在所有页面透出；只清纯色，渐变/图片/状态列表保持原样 */
    private static void clearOpaquePages(View v, int depth, int[] budget) {
        // 深度和预算以前都太小：真实页面树约 314 个节点、列表埋得很深，按「访问节点」扣预算会在到达目标前耗尽。
        //
        // 遍历便宜、清理才贵：只在清理时扣预算，深度给够。
        if (v == null || depth > 22 || budget[0] <= 0) return;
        try {
            if (!v.isShown()) return;
            if (isPullDownPanel(v)) return;   // the drop-down panel keeps its own look

            // 凡是会挡住不透明纯色底的地方都清：框架层、页面级容器、以及任意列表的行。
            //
            // 以前跳过列表内部（以为卡片逻辑会管），但卡片逻辑只认会话列表，通讯录的行被两边漏掉、一直白着。
            //
            // 我们自己的 CardDrawable 不是 ColorDrawable，已样式化的行会自动跳过。列表容器必须透明——微信常给它白色九宫格底，卡片缝隙就会露白。
            // through as a stray bar - the white lines between the cards on the 收藏 page
            // 以及会话列表上方那条会动的白条
            if (isGenericList(v) && !insideChatting(v)) {
                if (v.getBackground() != null) {
                    setBackgroundSafely(v, null);
                    budget[0]--;
                }
            } else if (!isBarLike(v) && !insideChatting(v)) {
                // 顺序有讲究：尺寸判断现在也接受全宽条（状态栏／标题栏），而行也是全宽条——行要留透明，只有容器才该被画上壁纸。
                //
                // 往容器上画切片错过两次：「我」页资料卡的白底有好几层（清一层还有下一层），滚动区内的容器还会带着切片移动，与固定壁纸错位。
                //
                // 清透明也是成品实现的做法：把遮罩和列表清透明，让唯一不滚动的那层透出来。
                if (hasOpaqueSolidBackground(v) && (cardsAllowed || isPageSized(v))) {
                    setBackgroundSafely(v, null);
                    budget[0]--;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++) {
                    clearOpaquePages(g.getChildAt(i), depth + 1, budget);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 该视图是否画了不透明纯色底 */
    private static boolean hasOpaqueSolidBackground(View v) {
        try {
            Drawable d = v.getBackground();
            if (d == null) return false;

            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                return ((c >>> 24) & 0xFF) > 200;
            }

            // 微信的列表背景是 XML shape（变成 GradientDrawable），第一版直接跳过它，导致通讯录一直白着而周围都透明了。
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((android.graphics.drawable.GradientDrawable) d).getColor();
                if (csl == null) return false;
                int c = csl.getDefaultColor();
                return ((c >>> 24) & 0xFF) > 200;
            }

            // 其它类型（我们自己的、位图、渐变、状态列表）都有含义，不动。
            //
            // 例外是微信用作白底板的两类：九宫格和状态列表——读不出颜色，所以以前全被跳过。
            // which is why the 服务 page's grid cells and the 收藏 page's cards stayed
            // 于是服务页的网格、收藏页的卡片一直实白（周围都跟了壁纸）。现在只在「底板尺寸」时清，小的（图标／角标／气泡）不动。
            String dn = nameOf(d);
            if (dn.contains("NinePatchDrawable") || dn.contains("StateListDrawable")) {
                return v.getWidth() >= dp(v, 48) && v.getHeight() >= dp(v, 20);
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解码并准备壁纸层。模糊用「大比例降采样再放大」实现，屏幕尺寸下与逐像素处理没差别，且不占每帧开销。 */
    private static boolean ensureImage(File dir, int w, int h) {
        String mode = Storage.str(K_BG_MODE, "fill");
        boolean blurOn = Storage.on(K_BLUR_ON, false);
        int radius = Storage.num(K_BLUR_RADIUS, 20);

        String key = w + "x" + h + "|" + mode + "|" + blurOn + ":" + radius
                + "|" + Storage.num(K_BG_CX, 0) + "," + Storage.num(K_BG_CY, 0)
                + "," + Storage.num(K_BG_CW, 100) + "," + Storage.num(K_BG_CH, 100)
                + "|" + Storage.num(K_BG_ALPHA, 200);
        if (key.equals(imgKey) && imgBg != null) return true;

        try {
            File f = new File(dir, BG_FILE);
            if (!f.exists() || f.length() <= 0) return false;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            if (blurOn && radius > 1) opts.inSampleSize = Math.max(2, radius);
            Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (src == null) return false;

            Bitmap cropped = cropPercent(src, Storage.num(K_BG_CX, 0), Storage.num(K_BG_CY, 0),
                    Storage.num(K_BG_CW, 100), Storage.num(K_BG_CH, 100));
            Bitmap fitted = scaleFor(cropped, mode, w, h);
            imgBg = applyAlpha(fitted, Storage.num(K_BG_ALPHA, 200));
            imgBgOpaque = applyAlpha(fitted, 255);
            imgKey = key;
            return imgBg != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 只给框住壁纸的层画图，其它不碰；列表行不画（会被拉出杂带），行用卡片代替 */
    /** 让顶部栏跟随壁纸：按栏的屏幕位置切一块不透明切片。透明不行——栏后的东西会透出来，标题会重影。 */
    private static void paintBars(View decor) {
        if (imgBgOpaque == null) return;
        try {
            paintBarsRec(decor, decor.getWidth(), decor.getHeight(), 0);
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static void paintBarsRec(View v, int sw, int sh, int depth) {
        if (v == null || depth > 8) return;
        try {
            if (isBottomTab(v)) {
                // 底栏用半透明卡片，比列表行更透：用行的透明度时，底栏在浅色壁纸上仍像一块实心白板，背景像是没铺到底栏。
                int corner = dp(v, clamp(Storage.num(K_CARD_CORNER, 16), 1, MAX_CARD_CORNER_DP));
                int alpha = 120;
                if (!(v.getBackground() instanceof CardDrawable)) {
                    setBackgroundSafely(v, new CardDrawable(0xFFFFFFFF, corner, alpha));
                }
            } else if (isBarLike(v) && v.getHeight() >= dp(v, 40) && v.getHeight() <= sh / 2) {
                // 标题栏是清掉，不是画图
                //
                // 标题栏会跟着页容器移动（下拉实测出现两个叠着的标题），所以这里不再贴固定切片，保持透明让窗口壁纸透出。
                if (insideChatting(v)) {
                    // 保持微信画的样子——见 fixChatBar 里关于拉锯的说明
                } else if (v.getBackground() != null
                        && !(v.getBackground() instanceof CardDrawable)) {
                    setBackgroundSafely(v, null);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    paintBarsRec(g.getChildAt(i), sw, sh, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static void slice(View v, int sw, int sh, int offX, int offY, int[] budget, int depth) {
        if (v == null || depth > 10 || budget[0] <= 0) return;
        try {
            if (!v.isShown()) return;
            if (insideConversationList(v)) return;

            int w = v.getWidth();
            int h = v.getHeight();
            boolean big = w >= sw * 0.8f && h >= dp(v, 20);

            // 顶部栏不画图：成品实现默认把标题栏设成纯色（不是透明，容易误读）；做成透明会让下层露出来、标题渲染两次。
            //
            // 所以栏保持透明（由拦截逻辑维持），只给真正框住壁纸的结构层画图。
            boolean wanted = isRootView(v);

            // 自愈判断：光有「已画过」标记不够——微信切 tab 时会重设标题栏背景，标记还在但图已被顶掉。
            Drawable current = v.getBackground();
            // 所有权只认我们自己的类：把 BitmapDrawable 也算进来时，微信自己的位图层会被当成「已经是我的」，于是跳过重画。
            boolean stillOurs = (current instanceof BarDrawable
                    || current instanceof TopBandDrawable) && painted.containsKey(v);

            if (big && wanted && !stillOurs) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);

                int sx = Math.max(0, offX);
                int sy = Math.max(0, loc[1] + offY);
                int ex = Math.min(imgBg.getWidth(), offX + w);
                int ey = Math.min(imgBg.getHeight(), loc[1] + h + offY);

                if (ex - sx > 0 && ey - sy > 0) {
                    Bitmap part = Bitmap.createBitmap(imgBg, sx, sy, ex - sx, ey - sy);
                    // 从状态栏区域内开始的层会把图画到状态栏上，所以这条带子留给微信。
                    int band = statusBarHeight(v) - loc[1];
                    if (band > 0) {
                        if (bandColour == 0) bandColour = windowColour(v, null);
                        setBackgroundSafely(v, new TopBandDrawable(part, band, bandColour));
                    } else {
                        // 一律用我们自己的类，绝不用 BitmapDrawable：微信自己的层也用它，「是不是我画的」就分不清（这条规则已被违反过两次）。
                        setBackgroundSafely(v, new BarDrawable(part));
                    }
                    painted.put(v, Boolean.TRUE);
                    budget[0]--;
                }
            }

            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++) {
                    slice(g.getChildAt(i), sw, sh, offX, offY, budget, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static boolean isConversationList(View v) {
        String n = nameOf(v);
        return CONV_LIST.equals(n) || CONV_RECYCLER.equals(n);
    }

    /** 标题栏（曾漏画，导致顶部保留微信原色而其它地方跟了壁纸） */
    /** 标题栏/底栏：现在不再动。曾把它们做成透明，结果露出下层、标题渲染两次，所以保持微信自己画的样子。 */
    /** 底部导航栏（按类名识别） */
    private static boolean isBottomTab(View v) {
        try {
            String n = nameOf(v);
            return n.contains(S.BTAB) || n.contains(S.BTAB_SHORT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBarLike(View v) {
        try {
            String n = nameOf(v);
            return n.contains(S.ABAR) || n.contains("TitleBar")
                    || n.contains(S.BTAB) || BOTTOM_TAB.equals(n);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isActionBar(View v) {
        try {
            String n = nameOf(v);
            return n.contains(S.ABAR_OVERLAY)
                    || n.contains(S.ABAR_CONTAINER)
                    || n.contains(S.ABAR_VIEW);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否「页面级」容器（阈值放宽，只用于判断纯色底值不值得拦） */
    /** 把该视图背后的壁纸画到它自己身上：清透明只在下面确实有壁纸时有效，否则「我」页只会露出灰色 */
    private static void applyWallpaperSlice(View v) {
        try {
            if (imgBg == null) return;
            View root = v.getRootView();
            if (root == null || root.getWidth() <= 0) return;
            int sw = root.getWidth();
            int sh = root.getHeight();
            int offX = (imgBg.getWidth() - sw) / 2;
            int offY = (imgBg.getHeight() - sh) / 2;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);

            if (v.getBackground() instanceof BarDrawable) return;   // already ours

            // 视图没移动／没改尺寸时复用烘焙好的切片：在主线程重切整屏位图正是滑动卡顿的来源。
            BarDrawable cached = sliceCache.get(v);
            int[] geo = sliceGeo.get(v);
            if (cached != null && geo != null && geo[0] == loc[1] && geo[1] == v.getHeight()) {
                setBackgroundSafely(v, cached);      // re-set only, no allocation
                return;
            }

            int sx = Math.max(0, offX);
            int sy = Math.max(0, loc[1] + offY);
            int ex = Math.min(imgBg.getWidth(), offX + v.getWidth());
            int ey = Math.min(imgBg.getHeight(), loc[1] + v.getHeight() + offY);
            if (ex - sx <= 0 || ey - sy <= 0) return;

            Bitmap part = Bitmap.createBitmap(imgBg, sx, sy, ex - sx, ey - sy);
            BarDrawable bd = new BarDrawable(part);
            sliceCache.put(v, bd);
            sliceGeo.put(v, new int[]{loc[1], v.getHeight()});
            setBackgroundSafely(v, bd);
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static boolean isPageSized(View v) {
        try {
            View root = v.getRootView();
            if (root == null || root.getWidth() <= 0) return false;
            // 两种形状都算，缺一不可：
            //   big block  - width >= 75% and height >= 12%  (profile card ~15%)
            //   全宽条：宽 ≥ 90% 且高 ≥ 60px（状态栏 104px、
            //                title bar 151px)
            // 以前漏的就是这些条：只占屏幕 4-6% 高，高度判断都不达标，于是「我」页顶部一直白着而下面透明了。
            boolean wide = v.getWidth() >= root.getWidth() * 0.90f;
            boolean big = v.getWidth() >= root.getWidth() * 0.75f
                    && v.getHeight() >= root.getHeight() * 0.12f;
            return (wide || big) && v.getHeight() >= dp(v, 20);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBottomTabBar(View v) {
        try {
            if (BOTTOM_TAB.equals(nameOf(v))) return true;
            View p = (v.getParent() instanceof View) ? (View) v.getParent() : null;
            return p != null && BOTTOM_TAB.equals(nameOf(p));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRootView(View v) {
        try {
            String n = nameOf(v);
            return n.contains("DecorView") || n.contains("ContentFrameLayout");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean insideConversationList(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 4 && cur != null; i++) {
                if (isConversationList(cur)) return true;
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 页面变可见就画：四个主页是同一个 Activity 里的 ViewPager，新页出现时没有 resume，等别的事件才画就晚了 */
    private static int hookPageContainers() {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(Features.loader, BOUNCE);
            if (c == null) return 0;
            XposedBridge.hookAllMethods(c, "onVisibilityChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Storage.on(Storage.K_THEME, false)) return;
                        if (!(p.thisObject instanceof View)) return;
                        if (p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                        if (((Integer) p.args[0]) != View.VISIBLE) return;
                        final View v = (View) p.thisObject;
                        long now = System.currentTimeMillis();
                        if (now - lastHeal < 300L) return;
                        lastHeal = now;
                        v.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    View root = v.getRootView();
                                    if (root != null && root.getWidth() > 0) paint(root);
                                } catch (Throwable ignored) {
                                    // 仅外观
                                }
                            }
                        }, 90L);
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            hits++;
        } catch (Throwable ignored) {
            // 不存在就跳过
        }
        return hits;
    }

    /** 让微信的下拉提示条不可见（列表顶部滑入的白色条，自带一份标题） */
    private static int hookTaskBarHint() {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(Features.loader,
                    S.CLS_TBAR_BOTTOM);
            if (c == null) return 0;
            XposedBridge.hookAllMethods(c, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        ((View) p.thisObject).setAlpha(0f);
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            hits++;
        } catch (Throwable ignored) {
            // 这个版本没有
        }
        return hits;
    }

    /** 在微信设置标题栏背景的那一刻替换：事后清理会与微信重绘赛跑，赛跑就是频闪 */
    private static int hookActionBar() {
        int hits = 0;
        try {
            Class<?> c = Silent.cls(Features.loader,
                    S.CLS_ABAR);
            if (c == null) return 0;
            XposedBridge.hookAllMethods(c, "setPrimaryBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (OUR_CALL.get() != null) return;
                        if (p.args.length < 1) return;
                        // 只对主窗口：把会话页标题栏清透明会露出它后面的深色窗口（「聊天里顶部一条黑灰边」）。
                        if (!(p.thisObject instanceof View)) return;
                        View bar = (View) p.thisObject;
                        // 这个版本里会话页与主界面共用 LauncherUI 的窗口，按 Activity 名分不出来，只能按视图层级。
                        if (insideChatting(bar)) {
                            // 微信按标题栏背后的颜色挑聊天标题栏深浅，而且在会话打开时就定了——事后还原窗口背景已经晚了（它已经选了 #111111）。
                            p.args[0] = new android.graphics.drawable.ColorDrawable(0xFFFFFFFF);
                            return;
                        }
                        if (!isMainContext(bar.getContext())) return;
                        // 用透明而不是 null：调用方可能假定 drawable 存在
                        p.args[0] = new android.graphics.drawable.ColorDrawable(0);
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            hits++;
        } catch (Throwable ignored) {
            // 这个版本没有
        }
        return hits;
    }

    // ── veto ──────────────────────────────────────────────────────────────────

    /** 拦住会话列表和它的行自己画背景：不拦的话每行都会重新盖一层不透明色，底下画什么都被盖住 */
    /** 页面变可见时重画：主页四个 tab 是同一个 LauncherUI 里的 Fragment，切页不触发 Activity onResume */
    private static int hookFragments() {
        int hits = 0;
        for (String cn : new String[]{S.CLS_FRAGMENT,
                "android.app.Fragment", "android.support.v4.app.Fragment"}) {
            try {
                Class<?> fc = Silent.cls(Features.loader, cn);
                if (fc == null) continue;
                XposedBridge.hookAllMethods(fc, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!Storage.on(Storage.K_THEME, false)) return;
                            Object frag = p.thisObject;
                            if (frag == null) return;
                            Object act = frag.getClass().getMethod("getActivity").invoke(frag);
                            if (!(act instanceof Activity)) return;
                            View decor = ((Activity) act).getWindow() == null ? null
                                    : ((Activity) act).getWindow().getDecorView();
                            if (decor == null) return;
                            // 新页面：视图都是新的，清掉「已画过」的记录再画一遍。
                            painted.clear();
                            decor.post(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        paint(decor);
                                    } catch (Throwable ignored) {
                                        // 仅外观
                                    }
                                }
                            });
                        } catch (Throwable ignored) {
                            // 不打扰宿主
                        }
                    }
                });
                hits++;
            } catch (Throwable ignored) {
                // fragment 类不存在
            }
        }
        return hits;
    }

    private static int hookVeto() {
        int hits = 0;
        // 对我们隐藏的下拉条拒绝 VISIBLE：微信每次动画都会重设
        // animation frame, and each re-show put the second "微信(N)" title back on screen -
        // 实测滚动中该条在 y=138 可见，而副本停在 y=95
        try {
            for (Method m : View.class.getDeclaredMethods()) {
                if (!"setVisibility".equals(m.getName()) || m.getParameterCount() != 1) continue;
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!(p.thisObject instanceof View)) return;
                            if (p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                            if (((Integer) p.args[0]) != View.VISIBLE) return;
                            if (pullStripHidden.containsKey((View) p.thisObject)) {
                                p.setResult(null);   // stay hidden
                            }
                        } catch (Throwable ignored) {
                            // 不打扰宿主
                        }
                    }
                });
                hits++;
                break;
            }
        } catch (Throwable ignored) {
            // 不存在
        }
        try {
            XposedBridge.hookAllMethods(View.class, "setBackgroundColor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (OUR_CALL.get() != null) return;
                        if (!(p.thisObject instanceof View)) return;
                        View v = (View) p.thisObject;
                        if (belongsToList(v)) {
                            p.setResult(null);
                            return;
                        }
                        // 栏有意不碰（见 isBarLike）。不透明的页面容器会挡住窗口背景，所以整体处理时按尺寸判断、可靠地清掉。
                        if (!insideChatting(v) && Storage.on(K_ALL_PAGES, true)
                                && isPageSized(v)) {
                            Object arg = p.args.length > 0 ? p.args[0] : null;
                            if (arg instanceof Integer) {
                                int c = (Integer) arg;
                                int a = (c >>> 24) & 0xFF;
                                if (a > 200) p.setResult(null);   // only fully opaque
                            }
                        }
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            hits++;
        } catch (Throwable ignored) {
            // 不存在
        }

        for (String name : new String[]{"setBackground", "setBackgroundDrawable",
                "setBackgroundResource"}) {
            try {
                for (Method m : View.class.getDeclaredMethods()) {
                    if (!name.equals(m.getName())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (OUR_CALL.get() != null) return;
                                if (!(p.thisObject instanceof View)) return;
                                View v2 = (View) p.thisObject;
                                if (belongsToList(v2)) {
                                    p.setResult(null);
                                    return;
                                }
                                if (!insideChatting(v2)
                                        && Storage.on(K_ALL_PAGES, true) && isPageSized(v2)) {
                                    p.setResult(null);
                                    return;
                                }
                                // 主页面上，任何宽视图上的不透明底板都直接拒掉：微信在滚动／重绑时会反复重画这些层，放行一次就盖住壁纸一帧（看起来就是背景在闪）。
                                if (!insideChatting(v2) && isMainContext(v2.getContext())
                                        && isWidePlate(v2)) {
                                    p.setResult(null);
                                }
                                // 会话页保留微信自己的标题栏：与它抢颜色（我们改写 vs 它重设）正是断续频闪的来源。
                            } catch (Throwable ignored) {
                                // 不打扰宿主
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (OUR_CALL.get() != null) return;   // our own call
                                if (!(p.thisObject instanceof View)) return;
                                View v2 = (View) p.thisObject;
                                // 会话页保留微信自己的栏。这段重贴逻辑以前在会话页也跑，把缓存的壁纸切片贴到了聊天标题栏上（壁纸顶部偏暗，栏就成了近黑）。
                                if (insideChatting(v2)) return;
                                if (isBarLike(v2)) {
                                    // 微信自己给栏画了背景（切 tab 就会这样）：立刻把缓存的切片贴回去，有缓存才能瞬时且不漂移。
                                    BarDrawable cached = barCache.get(v2);
                                    if (cached != null) setBackgroundSafely(v2, cached);
                                    return;
                                }
                                // 「我」页曾有一两秒停在微信白色 UI 上：只扫一遍不够，微信随后还会重画自己的颜色，所以在设置背景的当口就清掉。
                                // 小于屏宽 30% 的控件不动：固定的 600px 在 1080p 上恰好，在高分屏/平板上会把本该清理的容器放过
                                if (v2.getWidth()
                                        < v2.getResources().getDisplayMetrics().widthPixels * 0.3f) {
                                    return;
                                }
                                if (Storage.on(K_ALL_PAGES, true) && !insideChatting(v2)) {
                                    if (hasOpaqueSolidBackground(v2)) {
                                        setBackgroundSafely(v2, null);
                                    }
                                }
                            } catch (Throwable ignored) {
                                // 不打扰宿主
                            }
                        }
                    });
                    hits++;
                }
            } catch (Throwable ignored) {
                // 不存在 on this API level
            }
        }
        return hits;
    }

    /** 该视图（或其祖先）是否属于列表行 */
    private static boolean belongsToList(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 4 && cur != null; i++) {
                String n = nameOf(cur);
                if (isConversationList(cur)) return true;
                if (n.contains("RecyclerView") || n.endsWith("ListView")
                        || n.contains("PullDownListView")) {
                    return true;
                }
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static void setBackgroundSafely(View v, Drawable d) {
        try {
            OUR_CALL.set(Boolean.TRUE);
            v.setBackground(d);
        } catch (Throwable ignored) {
            // 仅外观
        } finally {
            OUR_CALL.remove();
        }
    }

    // ── chat rows as cards ────────────────────────────────────────────────────

    private static void styleCards(View root) {
        if (!cardsAllowed) return;
        if (!Storage.on(K_CARD_ON, false)) return;
        styleLists(root, 0);
    }

    /** 主页四个 tab 都在同一个 LauncherUI 里，其它页面不重排 */
    private static boolean isMainPage(View decor) {
        try {
            if (!(decor.getContext() instanceof Activity)) return true;
            String n = ((Activity) decor.getContext()).getClass().getName();
            if (n.contains(S.CHAT)) return false;
            return n.contains(S.LAUNCHER);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 按帧写出整页视图树：只看列表子树看不到破坏壁纸的层（它们在列表之上） */
    private static void dumpPageOnce(View root) {
        if (root == null || !Storage.on(K_DUMP, false)) return;
        long now = System.currentTimeMillis();
        int seq;
        synchronized (Beautify.class) {
            if (pageDumps >= MAX_PAGE_DUMPS || now - lastPageDump < 500L) return;
            lastPageDump = now;
            pageDumps++;
            seq = pageDumps;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("page=").append(nameOf(root))
                    .append("  ").append(root.getWidth()).append('x').append(root.getHeight())
                    .append("  frame=").append(seq).append('\n');
            dumpTree(root, 0, sb);
            File f = new File(root.getContext().getFilesDir(),
                    "P" + (seq < 10 ? "0" : "") + seq + "-page.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
            // 仅测量
        }
    }

    /** 隐藏微信插在会话列表上方的「小程序」入口：它是 header 不是行，卡片逻辑看不到它，而且会随列表滚动 */
    private static void hideAppBrandHeader(View list) {
        try {
            if (!isConversationList(list)) return;
            Object infos = XposedHelpers.getObjectField(list, "mHeaderViewInfos");
            if (!(infos instanceof java.util.List)) return;
            for (Object info : (java.util.List<?>) infos) {
                Object head = XposedHelpers.getObjectField(info, "view");
                if (!(head instanceof ViewGroup)) continue;
                ViewGroup headGroup = (ViewGroup) head;
                if (headGroup.getChildCount() > 1) {
                    headGroup.getChildAt(1).setVisibility(View.GONE);
                }
                setBackgroundSafely(headGroup, null);
            }
        } catch (Throwable ignored) {
            // 这个版本没有这个 header
        }
    }

    /** 给页面上所有列表加卡片（不只会话列表）：两侧内缩、圆角、半透明，壁纸从缝隙透出 */
    private static void styleLists(View v, int depth) {
        if (v == null || depth > 18) return;
        try {
            if (isGenericList(v) && !insideChatting(v)) {
                if (isConversationList(v)) hideAppBrandHeader(v);
                blankPullDownBar(v, 0);
                hookAdapter(v);
                if (v instanceof ViewGroup) applyCards((ViewGroup) v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    styleLists(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 微信的列表容器（按结构识别，不按页面） */
    private static boolean isGenericList(View v) {
        try {
            String n = nameOf(v);
            // 消息列表永远不做卡片：它的「行」是消息气泡，套卡片会给每条消息加一层框。
            if (n.contains(S.CHAT)) return false;
            return n.contains("RecyclerView") || n.endsWith("ListView")
                    || n.contains("PullDownListView");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否在打开的会话里（气泡要保持自己的布局）；判定要一路走到根，6 层不够，会误判消息列表 */
    private static boolean insideChatting(View v) {
        Boolean hit = chatCache.get(v);
        if (hit != null) return hit;
        boolean r = insideChattingWalk(v);
        chatCache.put(v, r);
        return r;
    }

    private static boolean insideChattingWalk(View v) {
        try {
            View cur = v;
            for (int i = 0; i < 40 && cur != null; i++) {
                String n = nameOf(cur);
                if (n.contains(S.CHAT) || n.contains(S.MMCHAT_LIST)) return true;
                cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static void applyCards(ViewGroup g) {
        int inset = dp(g, clamp(Storage.num(K_CARD_INSET, 10), 0, MAX_CARD_INSET_DP));
        int gap = dp(g, clamp(Storage.num(K_CARD_GAP, 8), 0, MAX_CARD_INSET_DP));
        int corner = dp(g, clamp(Storage.num(K_CARD_CORNER, 16), 1, MAX_CARD_CORNER_DP));
        int alpha = clamp(Storage.num(K_CARD_ALPHA, 200), 0, 255);

        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            // 微信的行分隔线是 1px 全宽细线；做成带缝隙的卡片后，它正好落在缝隙里，像一条杂条（发现页的「小条」）。
            if (isSeparator(child)) {
                setBackgroundSafely(child, null);
                continue;
            }
            applyRow(child, inset, gap, corner, alpha);
        }
    }

    /** 1px 全宽细线（微信自己的分隔线） */
    private static boolean isSeparator(View v) {
        try {
            if (v == null || !v.isShown()) return false;
            if (v.getHeight() > dp(v, 2)) return false;
            if (v.getWidth() < v.getResources().getDisplayMetrics().widthPixels * 0.9f) {
                return false;
            }
            Drawable d = v.getBackground();
            return d instanceof android.graphics.drawable.ColorDrawable
                    || d instanceof android.graphics.drawable.GradientDrawable;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 让下拉条一直不可见：每次下拉微信都会把它重新设成可见，一次性的 INVISIBLE 会被覆盖 */
    private static int hookVisibility() {
        int hits = 0;
        try {
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof View)) return;
                        if (p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                        if (((Integer) p.args[0]) == View.GONE) return;   // already gone
                        View v = (View) p.thisObject;
                        if (nameOf(v).contains(S.TBAR_BOTTOM)
                                || holdsPullDownStrip(v)) {
                            p.args[0] = View.INVISIBLE;
                        }
                        // 会话页变可见，就是该把窗口背景还给微信的时刻（见 paint()）：它按背后颜色挑标题栏深浅，留着我们的壁纸就是近黑标题栏的来源。
                        if (((Integer) p.args[0]) == View.VISIBLE
                                && nameOf(v).contains(S.CHAT_UI)) {
                            final View root = v.getRootView();
                            v.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        // 这里只还原窗口背景，不动标题栏颜色——与它抢颜色会造成断续频闪。
                                        if (root != null && root.getWidth() > 0) paint(root);
                                    } catch (Throwable ignored) {
                                        // 仅外观
                                    }
                                }
                            }, 250L);
                        }
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
            hits++;
        } catch (Throwable ignored) {
            // 这个版本没有
        }
        return hits;
    }

    /** 关掉面板容器自绘的底：按实例挂 hook（按类名查不到这些插件类）。onDraw 是容器自己的绘制，子视图不受影响。 */
    private static void hookPanelDraw(Class<?> cls) {
        if (cls == null || !panelHooked.add(cls)) return;   // once per class
        try {
            XposedBridge.hookAllMethods(cls, "onDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (OUR_CALL.get() != null) return;
                        p.setResult(null);
                    } catch (Throwable ignored) {
                        // 不打扰宿主
                    }
                }
            });
        } catch (Throwable ignored) {
            // 这个版本没有
        }
    }

    /** 已关掉自绘底的类 */
    private static final java.util.Set<Class<?>> panelHooked =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    /** 直接子项里含下拉条的容器 */
    private static boolean holdsPullDownStrip(View v) {
        Boolean hit = stripCache.get(v);
        if (hit != null) return hit;
        boolean r = holdsPullDownStripWalk(v);
        stripCache.put(v, r);
        return r;
    }

    private static boolean holdsPullDownStripWalk(View v) {
        try {
            if (!(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null && nameOf(c).contains(S.TBAR_BOTTOM)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 不是容器
        }
        return false;
    }

    /** 取视图 context 背后真正的 Activity（context 通常被包装过） */
    private static Activity activityOf(android.content.Context c) {
        try {
            android.content.Context cur = c;
            for (int i = 0; i < 8 && cur != null; i++) {
                if (cur instanceof Activity) return (Activity) cur;
                if (cur instanceof android.content.ContextWrapper) {
                    cur = ((android.content.ContextWrapper) cur).getBaseContext();
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 解析不出来
        }
        return null;
    }

    /** 是否「主页四 tab 所在的窗口」：必须问当前活动的 context，不能读共享标志——会话页 paint 会提前返回，标志会留成旧值 */
    private static boolean isMainContext(android.content.Context c) {
        Activity a = activityOf(c);
        return a != null && nameOf(a).contains(S.LAUNCHER);
    }

    /** 该窗口是否允许做背景拦截：除会话页外都允许。会话页要完整保留微信画的东西。 */
    private static boolean vetoAllowed(android.content.Context c) {
        Activity a = activityOf(c);
        return a != null && !nameOf(a).contains(S.CHAT);
    }

    /** 该背景是否我们自己的 Drawable。故意不含 BitmapDrawable——微信也用它，混在一起就分不清「我的」和「它的」。 */
    /** 窗口背景是否已还原成微信的浅色聊天底 */
    private static volatile boolean bgPlainLight = false;

    /** 窗口里是否有会话页在显示：从 decor 往下找可见的会话容器（这个版本会话页与主界面共用窗口，看 Activity 分不出） */
    private static boolean chatOnScreen(View decor) {
        return hasVisibleChat(decor, 0);
    }

    private static boolean hasVisibleChat(View v, int depth) {
        if (v == null || depth > 20) return false;
        try {
            if (v.isShown()) {
                String n = nameOf(v);
                if (n.contains(S.CHAT_UI) || n.contains(S.CHAT_SCROLL)) {
                    return true;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (hasVisibleChat(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 把会话页标题栏改成浅色：这个页面没有 setBackground 可拦（颜色来自布局膨胀），也不触发重绘，只能直接找到它改 */
    private static void fixChatBar(View v, int depth) {
        if (v == null || depth > 24) return;
        try {
            if (v.isShown() && isBarLike(v) && insideChatting(v)) {
                Drawable d = v.getBackground();
                boolean alreadyLight = d instanceof android.graphics.drawable.ColorDrawable
                        && ((android.graphics.drawable.ColorDrawable) d).getColor() == 0xFFFFFFFF;
                if (!alreadyLight) {
                    setBackgroundSafely(v,
                            new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    fixChatBar(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 类名缓存：高频路径上大量判定只看类名，getClass().getName() 每次都分配。 */
    private static final Map<Class<?>, String> nameCache =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, String>());

    /** 会话页判定缓存：它要向上遍历 40 层父链，且在每次 setBackground/setVisibility 上被问一次。 */
    private static final Map<View, Boolean> chatCache =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /** 下拉条容器判定缓存（要遍历子项）。 */
    private static final Map<View, Boolean> stripCache =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private static String nameOf(Object o) {
        try {
            Class<?> c = o.getClass();
            String n = nameCache.get(c);
            if (n == null) {
                n = c.getName();
                nameCache.put(c, n);
            }
            return n;
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isOurDrawable(Drawable d) {
        return d instanceof BarDrawable || d instanceof CardDrawable
                || d instanceof TopBandDrawable;
    }

    /** 主页面上微信会反复重画的不透明底板：调用发生在布局之前，尺寸不可信，所以按 drawable 类型判断 */
    private static boolean isWidePlate(View v) {
        try {
            Drawable d = v.getBackground();
            if (d == null || isOurDrawable(d)) return false;
            int w = v.getWidth();
            // 只对宽层：阈值比以前严，因为下面的 alpha 判断放宽了很多，小元素会误伤
            if (w > 0 && w < v.getResources().getDisplayMetrics().widthPixels * 0.6f) return false;
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                // 从「浅灰洗层」起都算，不只是全不透明：微信用半透明灰／黑洗层盖住页面，滑动时把壁纸冲灰，旧判据（只认不透明）全放过去了
                return ((c >>> 24) & 0xFF) > 30;
            }
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((android.graphics.drawable.GradientDrawable) d).getColor();
                return csl != null && ((csl.getDefaultColor() >>> 24) & 0xFF) > 30;
            }
            String n = nameOf(d);
            return n.contains("NinePatchDrawable") || n.contains("StateListDrawable");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 本该承载壁纸的窗口层是否没有壁纸 */
    private static boolean rootNeedsWallpaper(View decor) {
        try {
            if (decor == null || decor.getWidth() <= 0) return false;
            // 要对我们所有 Drawable 类都判断：只认 BitmapDrawable 会漏掉 TopBandDrawable（decor 上实际就是它），判据永远为真、每隔 1.5 秒重画一次
            return !isOurDrawable(decor.getBackground());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清掉窗口级容器的背景，让窗口壁纸透出：壁纸只在窗口层时，这些层不能自带背景 */
    private static void clearRootLayers(View v, int depth) {
        if (v == null || depth > 6) return;
        try {
            if (isRootView(v) && v.getBackground() != null) {
                setBackgroundSafely(v, null);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    clearRootLayers(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 隐藏微信下拉时滑入的提示条（1080x151，滑到 y=22；自带一份标题和搜索/加号图标，所以下拉中标题会出现两次） */
    private static void blankPullDownBar(View v, int depth) {
        if (v == null || depth > 14) return;
        try {
            String n = nameOf(v);
            if (n.contains(S.TBAR_CONTAINER) || n.contains(S.ABRAND_DESK)) {
                // 下拉的小程序面板：它的深色底不是 View 背景（内部节点全是 bg=null，但看得见深色），是容器 onDraw 自己画的，setBackground 够不着；所以从实例上挂 hook 关掉它，子视图不受影响
                hookPanelDraw(v.getClass());
                return;
            }
            if (n.contains(S.TBAR_BOTTOM)) {
                // 整条隐藏，不只这个 View：它的父容器里有个兄弟节点也带一份标题（dump 实测），只隐藏自己会留下那份副本
                // "微信(N)"]), which is why hiding this view alone still left two titles
                // 就在列表被轻轻上推的那一刻
                //
                // 用 INVISIBLE 而不是 alpha 0：微信显示时会重设 alpha，之前设的会被静默重置
                android.view.ViewParent parent = v.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup strip = (ViewGroup) parent;
                    for (int i = 0; i < strip.getChildCount(); i++) {
                        View sib = strip.getChildAt(i);
                        if (sib != null) {
                            pullStripHidden.put(sib, Boolean.TRUE);
                            if (sib.getVisibility() != View.INVISIBLE) {
                                sib.setVisibility(View.INVISIBLE);
                            }
                        }
                    }
                } else if (v.getVisibility() != View.INVISIBLE) {
                    v.setVisibility(View.INVISIBLE);
                }
                // 记下来：微信每帧都会重新显示这条，拦截逻辑靠这个集合拒绝
                pullStripHidden.put(v, Boolean.TRUE);
                if (v.getBackground() != null) setBackgroundSafely(v, null);
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    blankPullDownBar(g.getChildAt(i), depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 下拉的「小程序」面板及其任务栏容器：它是会话列表的子项，会被当成行套上全屏卡片 */
    private static boolean isPullDownPanel(View v) {
        try {
            String n = nameOf(v);
            return n.contains(S.TBAR) || n.contains(S.ABRAND) || n.contains(S.MULTITASK);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 给一个列表项做卡片，或按「多行容器」处理。微信把「分组标题 + 该组首行」塞进同一个 item（238px），整体套卡片会让两份白底都盖在卡片上。 */
    private static void applyRow(View child, int inset, int gap, int corner, int alpha) {
        if (child == null) return;
        if (isPullDownPanel(child)) return;
        if (isRowContainer(child)) {
            setBackgroundSafely(child, null);
            resetMargins(child);
            ViewGroup cg = (ViewGroup) child;
            for (int j = 0; j < cg.getChildCount(); j++) {
                View row = cg.getChildAt(j);
                if (row == null || !row.isShown()) continue;
                if (row.getHeight() >= dp(cg, 34) && row.getWidth() * 10 >= cg.getWidth() * 6) {
                    styleRow(row, inset, gap, corner, alpha);
                } else {
                    stripRowFills(row, 0);
                    fillsStripped.put(row, Boolean.TRUE);
                }
            }
            return;
        }
        styleRow(child, inset, gap, corner, alpha);
    }

    /** 该项是否只是装行的容器：按内容形状判断，不看类名（每个页面类名都不同，猜名字在这份文件上失败过） */
    private static boolean isRowContainer(View v) {
        try {
            if (!(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            int rows = 0;
            int tallest = 0;
            int h = -1;
            boolean even = true;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || !c.isShown()) continue;
                int ch = c.getHeight();
                if (ch < 100) continue;
                if (ch > tallest) tallest = ch;
                if (h < 0) {
                    h = ch;
                } else if (Math.abs(ch - h) > Math.max(4, h / 8)) {
                    even = false;
                }
                rows++;
            }
            if (rows >= 3 && even) return true;
            return rows == 1 && v.getHeight() > tallest + dp(v, 8);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 应用卡片高度增量；每行只记一次原始高度，重复执行不会累积 */
    private static void applyCardHeight(View row, int cornerUnused) {
        try {
            int dh = dp(row, clamp(Storage.num(K_CARD_H, 0), -24, 48));
            if (dh == 0) return;
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp == null || lp.height != ViewGroup.LayoutParams.WRAP_CONTENT
                    && lp.height <= 0) {
                // 只有高度确定的行才能安全缩放
            }
            Integer orig = origHeights.get(row);
            if (orig == null) {
                int h = row.getHeight() > 0 ? row.getHeight() : lp.height;
                if (h <= 0) return;
                orig = h;
                origHeights.put(row, orig);
            }
            int want = Math.max(1, orig + dh);
            if (lp.height != want) {
                lp.height = want;
                row.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
            // 单行失败不影响其它行
        }
    }

    /** 每行原始高度（防止高度增量累积） */
    private static final Map<View, Integer> origHeights =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    private static void resetMargins(View v) {
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.leftMargin != 0 || mlp.rightMargin != 0 || mlp.bottomMargin != 0) {
                    mlp.leftMargin = 0;
                    mlp.rightMargin = 0;
                    mlp.bottomMargin = 0;
                    v.setLayoutParams(mlp);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 清掉行内部的底板，让画在行上的卡片真正露出来。只清覆盖父容器大部分面积的填充，小的是图标/角标，不动。 */
    private static void stripRowFills(View v, int depth) {
        if (v == null || depth > 4 || !(v instanceof ViewGroup)) return;
        try {
            ViewGroup g = (ViewGroup) v;
            int w = v.getWidth();
            int h = v.getHeight();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || !c.isShown()) continue;
                boolean wide = c.getWidth() * 10 >= w * 6;
                // 两种算底板：覆盖父容器大部分高度，或本身就是「行尺」。后者才捞得到「分组标题 + 首行」item 里那两块——标题只占 238px 中的 87px，纯相对判断会直接走过去
                boolean plate = wide && h > 0 && w > 0
                        && (c.getHeight() * 10 >= h * 7 || c.getHeight() >= dp(c, 20));
                if (plate && isOpaqueFill(c)) setBackgroundSafely(c, null);
                stripRowFills(c, depth + 1);
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 在这个位置只当「文字底衬」用的平铺填充。故意包含读不出颜色的类型：通讯录行的底板就是 StateList / NinePatch。 */
    private static boolean isOpaqueFill(View v) {
        try {
            Drawable d = v.getBackground();
            if (d == null) return false;
            if (d instanceof CardDrawable || d instanceof BarDrawable) return false;
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return ((((android.graphics.drawable.ColorDrawable) d).getColor() >>> 24) & 0xFF)
                        > 200;
            }
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList csl =
                        ((android.graphics.drawable.GradientDrawable) d).getColor();
                return csl != null && ((csl.getDefaultColor() >>> 24) & 0xFF) > 200;
            }
            String n = nameOf(d);
            return n.contains("StateListDrawable") || n.contains("NinePatchDrawable");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 样式化一行，且只在确实要改时才动：已完成的行按 drawable 跳过，所以滚动路径上的开销很低 */
    private static void styleRow(View row, int inset, int gap, int corner, int alpha) {
        if (row == null) return;
        // 每条路径都加保险：真正的多行容器永远不能被当卡片，无论各趟处理谁先跑
        if (isRowContainer(row)) {
            applyRow(row, inset, gap, corner, alpha);
            return;
        }
        try {
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                if (mlp.leftMargin != inset || mlp.rightMargin != inset
                        || mlp.bottomMargin != gap) {
                    mlp.leftMargin = inset;
                    mlp.rightMargin = inset;
                    mlp.bottomMargin = gap;
                    row.setLayoutParams(mlp);
                }
            }
            Drawable cur = row.getBackground();
            if (!(cur instanceof CardDrawable) || !((CardDrawable) cur).matches(corner, alpha)) {
                setBackgroundSafely(row, new CardDrawable(cardColour(), corner, alpha));
            }
            // 卡片在行上，但行自己的子视图又盖了不透明底板。通讯录行实测：往下一层是全尺寸 StateListDrawable，文字下面是九宫格面板
            if (row.getWidth() > 0 && row.getHeight() > 0 && !fillsStripped.containsKey(row)) {
                fillsStripped.put(row, Boolean.TRUE);
                stripRowFills(row, 0);
            }
            applyCardHeight(row, 0);
        } catch (Throwable ignored) {
            // 单行失败不影响其它行
        }
    }

    private static void applyRow(View row) {
        if (row == null) return;
        applyRow(row,
                dp(row, clamp(Storage.num(K_CARD_INSET, 10), 0, MAX_CARD_INSET_DP)),
                dp(row, clamp(Storage.num(K_CARD_GAP, 8), 0, MAX_CARD_INSET_DP)),
                dp(row, clamp(Storage.num(K_CARD_CORNER, 16), 1, MAX_CARD_CORNER_DP)),
                clamp(Storage.num(K_CARD_ALPHA, 200), 0, 255));
    }

    /** 行测量完成后重跑一次样式：adapter 交回来的行还没测量，而「一行还是多行容器」要靠尺寸判断 */
    private static void applyRowDeferred(final View row) {
        if (row == null) return;
        try {
            row.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyRow(row);
                    } catch (Throwable ignored) {
                        // 仅外观
                    }
                }
            }, 140L);
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static int cardColour() {
        return Storage.on(K_CARD_DARK, false) ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    private static View findConversationList(View v) {
        try {
            if (isConversationList(v)) return v;
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View r = findConversationList(g.getChildAt(i));
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 挂 adapter 的绑定回调，一切从「活着的列表」上取：按类名查在这版上失败（微信重打包了库） */
    private static void hookAdapter(View list) {
        synchronized (Beautify.class) {
            try {
                Object adapter = null;
                for (Method m : list.getClass().getMethods()) {
                    if (!"getAdapter".equals(m.getName()) || m.getParameterCount() != 0) continue;
                    adapter = m.invoke(list);
                    break;
                }
                if (adapter == null) return;

                for (Class<?> c = adapter.getClass(); c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"onBindViewHolder".equals(m.getName())) continue;
                        // 只有 (holder, position) 这个重载才是真回调；单参数版是 final 且从不被调用——挂上去「成功」然后永不触发
                        if (m.getParameterCount() != 2) continue;
                        // 每个 adapter 类各挂一次：以前用一个布尔量，会话列表挂过后通讯录／发现／我的列表就再没挂上
                        if (adapterHooked.putIfAbsent(c.getName(), Boolean.TRUE) != null) {
                            return;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    if (!Storage.on(K_CARD_ON, false)) return;
                                    if (p.args.length < 1 || p.args[0] == null) return;
                                    Object item = XposedHelpers.getObjectField(p.args[0], "itemView");
                                    if (item instanceof View) {
                                        applyRow((View) item);
                                        applyRowDeferred((View) item);
                                    }
                                } catch (Throwable ignored) {
                                    // 不打扰宿主
                                }
                            }
                        });
                        Storage.diag(T + "adapter hooked: " + c.getName());
                        // 挂 hook 之前就绑定好的行收不到回调，这里补一次样式，否则首屏保持微信底色
                        if (list instanceof ViewGroup) applyCards((ViewGroup) list);
                        dumpListOnce(list);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 下面的滚动 hook 兜底
            }
        }
    }

    /** 补上滚动才露出来的行：RecyclerView 滚动时外层不触发布局回调，只靠布局监听会出现「停下才出圆角」 */
    private static void hookScroll() {
        if (scrollHooked) return;
        synchronized (Beautify.class) {
            if (scrollHooked) return;
            try {
                XposedBridge.hookAllMethods(View.class, "onScrollChanged", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!(p.thisObject instanceof View)) return;
                            View self = (View) p.thisObject;

                            // 切 tab 本质是一次滚动：新页滑入时 pager 会报滚动变化。该页没有 resume、没有重绘，只有这个事件会 fire
                            if (nameOf(self).contains("ViewPager")
                                    && Storage.on(Storage.K_THEME, false)) {
                                long now = System.currentTimeMillis();
                                if (now - lastHeal > 300L) {
                                    lastHeal = now;
                                    final View root = self.getRootView();
                                    self.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (root != null && root.getWidth() > 0) {
                                                    paint(root);
                                                }
                                            } catch (Throwable ignored) {
                                                // 仅外观
                                            }
                                        }
                                    }, 120L);
                                }
                            }

                            if (!Storage.on(K_CARD_ON, false)) return;
                            if (!(self instanceof ViewGroup)) return;
                            ViewGroup g = (ViewGroup) self;
                            if (!isGenericList(g)) return;
                            // 会话页标题栏在这里定不了色：颜色来自微信布局膨胀，且每次滚动都会重设——与它抢就是拉锯，拉锯就是频闪。会话页保持原样。
                            dumpListOnce(g);
                            View pageRoot = g.getRootView();
                            if (pageRoot != null) dumpPageOnce(pageRoot);
                            blankPullDownBar(g, 0);
                            applyCards(g);
                        } catch (Throwable ignored) {
                            // 不打扰宿主
                        }
                    }
                });
                scrollHooked = true;
            } catch (Throwable ignored) {
                // adapter hook 兜底
            }
        }
    }

    /** 用类名标记所有权的位图 Drawable：自愈判断曾用 instanceof BitmapDrawable，但微信自己也有，会误判成「已经是我的」 */
    private static final class BarDrawable extends Drawable {
        private final Bitmap bmp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();

        BarDrawable(Bitmap bmp) {
            this.bmp = bmp;
        }

        @Override
        public void draw(Canvas c) {
            try {
                rect.set(getBounds());
                c.drawBitmap(bmp, null, rect, paint);
            } catch (Throwable ignored) {
                // 不破坏宿主的绘制
            }
        }

        @Override
        public void setAlpha(int a) {
            paint.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** 把状态栏那条留给微信的壁纸：本机系统栏是透明的，窗口画什么就露出什么，图片不能盖到状态栏上 */
    private static final class TopBandDrawable extends Drawable {
        private final Bitmap bmp;
        private final int band;
        private final int colour;
        private final Paint picPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint bandPaint = new Paint();
        private final Rect rect = new Rect();

        TopBandDrawable(Bitmap bmp, int band, int colour) {
            this.bmp = bmp;
            this.band = Math.max(0, band);
            this.colour = colour;
        }

        @Override
        public void draw(Canvas c) {
            try {
                rect.set(getBounds());
                if (band > 0) {
                    bandPaint.setColor(colour);
                    c.drawRect(rect.left, rect.top, rect.right, rect.top + band, bandPaint);
                }
                c.save();
                c.clipRect(rect.left, rect.top + band, rect.right, rect.bottom);
                c.drawBitmap(bmp, null, rect, picPaint);
                c.restore();
            } catch (Throwable ignored) {
                // 不破坏宿主的绘制
            }
        }

        @Override
        public void setAlpha(int a) {
            picPaint.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            picPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return band > 0 ? android.graphics.PixelFormat.TRANSLUCENT
                    : android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** 状态栏高度（px），从平台资源读；猜高度要么留一条图，要么吃掉标题栏一截 */
    private static int statusBarHeight(View v) {
        try {
            android.content.res.Resources res = v.getContext().getResources();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int px = res.getDimensionPixelSize(id);
                if (px > 0) return px;
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        // 刘海/挖孔/折叠屏上 status_bar_height 常与真实可用高度对不上，Android 11+ 优先用窗口 insets
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsets wi = v.getRootWindowInsets();
                if (wi != null) {
                    int top = wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    if (top > 0) return top;
                }
            }
        } catch (Throwable ignored) {
            // 继续往下
        }
        try {
            return (int) (24 * v.getResources().getDisplayMetrics().density);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 状态栏条要还原成的颜色：取替换前的窗口色；微信窗口通常透明，那种情况用主题默认色 */
    private static int windowColour(View v, android.view.Window win) {
        try {
            Drawable d = win == null ? v.getBackground() : win.getDecorView().getBackground();
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                int c = ((android.graphics.drawable.ColorDrawable) d).getColor();
                if (((c >>> 24) & 0xFF) > 200) return c;
            }
        } catch (Throwable ignored) {
            // 继续往下 to the themed default
        }
        try {
            int uiMode = v.getContext().getResources().getConfiguration().uiMode;
            if ((uiMode & 0x30) == 0x20) return 0xFF191919;   // night
        } catch (Throwable ignored) {
            // 这个版本上浅色更安全
        }
        return 0xFFEDEDED;
    }

    /** 做好的卡片：颜色和形状在创建时就烘焙好，绘制时不测量。故意不带图（带图的卡片会跟着列表动）。 */
    private static final class CardDrawable extends Drawable {
        private final int colour;
        private final float corner;
        private final int alpha;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();

        CardDrawable(int colour, float corner, int alpha) {
            this.colour = colour;
            this.corner = corner;
            this.alpha = alpha;
        }

        boolean matches(float cornerPx, int alphaValue) {
            return this.corner == cornerPx && this.alpha == alphaValue;
        }

        @Override
        public void draw(Canvas c) {
            try {
                rect.set(getBounds());
                clip.reset();
                clip.addRoundRect(rect, corner, corner, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                paint.setColor(colour);
                paint.setAlpha(alpha);
                c.drawRect(rect, paint);
                c.restore();
            } catch (Throwable ignored) {
                // 不破坏宿主的绘制
            }
        }

        @Override
        public void setAlpha(int a) {
            paint.setAlpha(a);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** 把容器裁成圆角：用裁剪而不是重画；换背景的做法曾因为只认 ColorDrawable 而静默无效 */
    private static void watchLayout(final View decor) {
        try {
            if (watched.containsKey(decor)) return;
            watched.put(decor, Boolean.TRUE);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        private long last = 0L;

                        @Override
                        public void onGlobalLayout() {
                            long now = System.currentTimeMillis();
                            if (now - last < 220L) return;
                            last = now;
                            try {
                                // 自愈：以「承载壁纸的窗口层」为准。以页容器为准永远为真（它们按设计就是透明的），照那种判据会一直重画
                                if (now - lastHeal > 1500L && rootNeedsWallpaper(decor)) {
                                    lastHeal = now;
                                    decor.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                paint(decor);
                                            } catch (Throwable ignored) {
                                                // 仅外观
                                            }
                                        }
                                    });
                                }
                                if (!Storage.on(K_CORNER_ON, false)) return;
                                int radius = clamp(Storage.num(K_CORNER_DP, 12), 1, 40);
                                decor.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        clipCorners(decor, radius, new int[]{250}, 0);
                                    }
                                });
                            } catch (Throwable ignored) {
                                // 仅外观
                            }
                        }
                    });
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    /** 屏幕上本该有壁纸的页容器是否缺图（只看两类容器，且只在可见时判断） */
    private static boolean pageNeedsWallpaper(View v, int depth) {
        if (v == null || depth > 16) return false;
        try {
            if (!v.isShown()) return false;
            String n = nameOf(v);
            if (n.contains(S.BOUNCE) || n.contains(S.LAYOUT_LISTENER)) {
                if (!(v.getBackground() instanceof BarDrawable)) return true;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (pageNeedsWallpaper(g.getChildAt(i), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static void clipCorners(View v, int radiusDp, int[] budget, int depth) {
        if (v == null || depth > 6 || budget[0] <= 0) return;
        budget[0]--;
        try {
            if (cornered.containsKey(v)) return;
            float px = radiusDp * v.getResources().getDisplayMetrics().density;
            v.setClipToOutline(true);
            v.setOutlineProvider(new RoundOutline(px));
            cornered.put(v, Boolean.TRUE);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount() && budget[0] > 0; i++) {
                    clipCorners(g.getChildAt(i), radiusDp, budget, depth + 1);
                }
            }
        } catch (Throwable ignored) {
            // 仅外观
        }
    }

    private static final class RoundOutline extends ViewOutlineProvider {
        private final float radius;

        RoundOutline(float radius) {
            this.radius = radius;
        }

        @Override
        public void getOutline(View v, Outline o) {
            try {
                float r = Math.min(radius, Math.min(v.getWidth(), v.getHeight()) / 2f);
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            } catch (Throwable ignored) {
                // 不设 outline
            }
        }
    }

    // ── bitmap helpers ────────────────────────────────────────────────────────

    private static Bitmap cropPercent(Bitmap src, int cx, int cy, int cw, int ch) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            int x = Math.max(0, Math.min(w - 1, w * cx / 100));
            int y = Math.max(0, Math.min(h - 1, h * cy / 100));
            int cw2 = Math.max(1, Math.min(w - x, w * Math.max(1, cw) / 100));
            int ch2 = Math.max(1, Math.min(h - y, h * Math.max(1, ch) / 100));
            if (x == 0 && y == 0 && cw2 == w && ch2 == h) return src;
            return Bitmap.createBitmap(src, x, y, cw2, ch2);
        } catch (Throwable ignored) {
            return src;
        }
    }

    /** 按尺寸模式生成位图：fill 铺满并裁掉溢出、fit 完整显示留边、stretch 拉伸、center 居中 */
    private static Bitmap scaleFor(Bitmap src, String mode, int vw, int vh) {
        float srcRatio = (float) src.getWidth() / Math.max(1, src.getHeight());
        float viewRatio = (float) vw / Math.max(1, vh);
        int tw;
        int th;

        if ("stretch".equals(mode)) {
            tw = vw;
            th = vh;
        } else if ("fit".equals(mode)) {
            if (srcRatio > viewRatio) {
                tw = vw;
                th = Math.max(1, (int) (vw / srcRatio));
            } else {
                th = vh;
                tw = Math.max(1, (int) (vh * srcRatio));
            }
        } else if ("center".equals(mode)) {
            tw = src.getWidth();
            th = src.getHeight();
        } else if ("custom".equals(mode)) {
            tw = Math.max(1, vw * clamp(Storage.num(K_BG_W, 100), 5, 400) / 100);
            th = Math.max(1, vh * clamp(Storage.num(K_BG_H, 100), 5, 400) / 100);
        } else {
            if (srcRatio > viewRatio) {
                th = vh;
                tw = Math.max(1, (int) (vh * srcRatio));
            } else {
                tw = vw;
                th = Math.max(1, (int) (vw / srcRatio));
            }
        }

        if (tw == src.getWidth() && th == src.getHeight()) return src;
        try {
            return Bitmap.createScaledBitmap(src, tw, th, true);
        } catch (Throwable ignored) {
            return src;
        }
    }

    private static Bitmap applyAlpha(Bitmap src, int alpha) {
        try {
            Bitmap argb = src.copy(Bitmap.Config.ARGB_8888, true);
            if (argb == null) return src;
            int a = clamp(alpha, 0, 255);
            int[] px = new int[argb.getWidth()];
            for (int y = 0; y < argb.getHeight(); y++) {
                argb.getPixels(px, 0, argb.getWidth(), 0, y, argb.getWidth(), 1);
                for (int i = 0; i < px.length; i++) {
                    px[i] = (px[i] & 0x00FFFFFF) | (a << 24);
                }
                argb.setPixels(px, 0, argb.getWidth(), 0, y, argb.getWidth(), 1);
            }
            return argb;
        } catch (Throwable ignored) {
            return src;
        }
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
