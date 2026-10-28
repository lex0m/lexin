package io.github.lex0m.lexin;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 转发增强 · 阶段 1 + 2 骨架。
 *
 * 设计原则（比"破解微信判定"更低风险）：
 *   **不改微信任何判定**，只在长按菜单里加一个我们自己的项；微信原有逻辑一行不改。
 *   点我们这一项之后，才由我们去做动作（后续阶段：调微信自己的转发/发语音入口）。
 *
 * 挂点全部走字符串特征（WeKit 同款，跨版本稳）：
 *   chat_menu         = com.tencent.mm.ui.chatting.viewitems 里用 "MicroMsg.ChattingItem" + "msg is null!" 的方法
 *   chat_menu_select  = 同包，用 "context item select failed, null dataTag" 的方法（点击回调）
 *   chat_quick_select = 用 "MicroMsg.ChatItemQuickSelectComponent" 的那个多选组件类
 */
final class ForwardPlus {

    private static final String T = "svc-fwd: ";

    /** 我们自己菜单项的 id（随便挑一个不会撞的值）。 */
    private static final int ITEM_ID = 9984;            // 落在微信自己的 id 区间内（太大/太怪会被适配器丢掉）
    private static final int ITEM_GROUP = 0;            // 用微信自己的分组号，别再自造
    private static final String ITEM_LABEL = "转发（乐信）";

    /** 记住弹菜单的那个组件，点击回调里要顺着它找回消息。 */
    private static volatile Object lastOwner;
    /** 建菜单时的那个 View（有些类型只有 View 上有消息）。 */
    private static volatile View lastView;
    /** 已经注入过的那个菜单对象（同一菜单只加一次项，也用来挡递归）。 */
    private static volatile Object markedMenu;
    /** 锚点 View 的 tag（消息对象 + 显示文本都在里面）。 */
    private static volatile Object lastTag;
    /** 这条消息所属会话 id（转发时要用）。 */
    private static volatile String lastTalker;
    /** 已经从调用栈里学到的"菜单构造器"类名（每版不同，动态学）。 */
    private static final java.util.Set<String> knownBuilders =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 已经挂过构造函数的菜单类（每版不同，动态学）。 */
    private static final java.util.Set<String> knownMenuClasses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static void install() {
        int menuHits = hookMenuBuild();
        int popupHits = hookPopup();          // ★ 框架级：任何弹窗（含长按菜单）都要经过这里
        int addHits = 0;   // ★ 关闭 hookMenuAdd：它会在构造期插一项，和视图格重复
        int itemHits = hookMenuItems();
        int selectHits = hookMenuSelect();
        int clickHits = hookClickEntry();
        hookPickResult();
        SnsForward.install();                 // ★ 朋友圈：分享界面关闭后接着发图片
        int selectLimit = hookQuickSelect();
        int any = menuHits + popupHits + addHits + itemHits + selectHits + clickHits + selectLimit;
        boolean any2 = any > 0;
        Status.set("转发增强", any2);
        Status.note("转发增强", any2
                ? "弹窗 " + popupHits + " · 加项 " + addHits + " · 菜单 " + menuHits + " · 类型项 " + itemHits
                        + " · 点击 " + selectHits + "+" + clickHits + " · 多选 " + selectLimit + " 处"
                : "当前微信版本未适配");
        Log.d(T + "armed popup=" + popupHits + " add=" + addHits + " menu=" + menuHits + " items=" + itemHits
                + " select=" + selectHits + " quickSelect=" + selectLimit);
    }

    /**
     * 长按菜单构造：微信自己的 MM 菜单 API —— 每个能弹菜单的组件都会实现
     * `onCreateMMMenu(<菜单>):void`（**方法名没混淆**，所以按名字定位，天然跨版本）。
     * 我们在这里往菜单对象里加自己的一项。
     */
    private static int hookMenuBuild() {
        int hits = 0;
        // ① 表里带方法名（照抄 WAuxiliary 配方定位出来的那个方法）→ 直接挂它
        String entry = Anchors.entryWithMethod(Anchors.CHAT_MENU);
        String wantMethod = Anchors.methodNameOf(entry);
        Class<?> exact = entry == null ? null : Anchors.cls(Anchors.CHAT_MENU);
        if (exact != null && wantMethod != null) {
            for (final Method m : exact.getDeclaredMethods()) {
                if (!m.getName().equals(wantMethod)) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                Object menu = firstMenuArg(p.args);
                                if (menu == null) return;
                                lastOwner = p.thisObject;
                                // ★ 不在构造期加项：真正的注入统一走弹窗视图（injectViewCell），
                                //   两处都加会让菜单里出现两个"乐信"（真机截图已证实）。
                                Log.d(T + "exact menu hook fired: " + exact.getName() + "#" + m.getName());
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    });
                    hits++;
                    Log.d(T + "exact menu hook armed: " + exact.getName() + "#" + m.getName());
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        for (final Class<?> owner : Anchors.all(Anchors.CHAT_MENU)) {
            Method target = null;
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getParameterCount() >= 1 && m.getParameterCount() <= 8) {
                    target = m;
                    break;
                }
            }
            if (target == null) continue;
            try {
                target.setAccessible(true);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                            // 只在"消息列表"那一族组件上注入：图库/相册之类的菜单不要碰
                            String cn = owner.getName();
                            if (!cn.startsWith("com.tencent.mm.ui.chatting.component")
                                    && !cn.startsWith("com.tencent.mm.ui.chatting.viewitems")) return;
                            Object menu = firstMenuArg(p.args);
                            if (menu == null) return;
                            lastOwner = p.thisObject;               // 点击时要靠它找回消息
                            // 注入统一走弹窗视图，这里只记 owner
                        } catch (Throwable ignored) {
                            // 注入失败绝不能影响微信自己的菜单
                        }
                    }
                });
                hits++;
            } catch (Throwable ignored) {
                // 下一个类
            }
        }
        return hits;
    }

    /**
     * 每种消息类型各自的菜单构造（`S(<菜单>, View, <上下文>):boolean` 一类）。
     * 同一个类里可能有好几个这种形状的方法，只认真的是菜单对象的那种（第一个参数带 add(int,CharSequence,Drawable)）。
     */
    private static int hookMenuItems() {
        int hits = 0;
        for (final Class<?> owner : Anchors.all(Anchors.CHAT_MENU_ITEMS)) {
            for (final Method m : owner.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3 || ps[1] != View.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                Object menu = firstMenuArg(p.args);
                                if (menu == null) return;
                                lastOwner = p.thisObject;
                                lastView = (View) p.args[1];
                                Log.d(T + "items hook fired: " + owner.getName()
                                        + "#" + m.getName() + " menu=" + menu.getClass().getName());
                                // 注入统一走弹窗视图
                            } catch (Throwable ignored) {
                                // 注入失败不影响微信
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个方法
                }
            }
        }
        return hits;
    }

    /**
     * **最可靠的一条路**：直接挂在"往菜单里加项"的方法上（签名 `(int, CharSequence, Drawable) → MenuItem`，
     * 名字被混淆所以按签名找）。任何菜单项都要经过它 —— 也就是说每次弹菜单我们都会被叫到，
     * 而且菜单对象就是 thisObject。
     *
     * 注意递归：我们在钩子里自己也会调这个方法加自己的项 —— 用"同一个菜单只加一次"的标记挡住。
     */
    private static int hookMenuAdd() {
        int hits = 0;
        for (final Class<?> owner : Anchors.all(Anchors.MM_MENU_ADD)) {
            for (final Method m : allMethods(owner)) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3) continue;
                if (ps[0] != int.class || ps[1] != CharSequence.class || ps[2] != Drawable.class) continue;
                if (!MenuItem.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                Object menu = p.thisObject;
                                if (menu == null) return;
                                if (p.args.length > 0 && Integer.valueOf(ITEM_ID).equals(p.args[0])) return; // 我们自己那次
                                if (menu == markedMenu) return;         // 同一个菜单只加一次（同时挡住递归）
                                markedMenu = menu;
                                Log.d(T + "menu add hook fired: " + menu.getClass().getName());
                                m.setAccessible(true);
                                m.invoke(menu, ITEM_ID, ITEM_LABEL, null);   // 会再次进钩子，被上面的标记挡掉
                                Log.d(T + "menu item injected");
                            } catch (Throwable ignored) {
                                // 注入失败绝不影响微信菜单
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个方法
                }
            }
        }
        return hits;
    }

    /**
     * ★ 框架级注入：微信的长按菜单是个 PopupWindow（实测类名 kj5.f5，每版不同）。
     * 不管它叫什么，弹出来时都要经过 PopupWindow.show / showAtLocation / showAsDropDown ——
     * 我们在这里拿到弹窗对象，再反射找出它肚子里的"菜单对象"（带 5 参 add 的那个），插自己的项。
     * 完全不依赖混淆名 ⇒ 换版本也不会失效。
     */
    private static int hookPopup() {
        int hits = 0;
        try {
            Class<?> pw = Class.forName("android.widget.PopupWindow", false, Features.loader);
            for (String show : new String[]{"showAtLocation", "showAsDropDown", "show"}) {
                try {
                    XposedBridge.hookAllMethods(pw, show, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                // ★ 锚点 View 就是"被长按的那条消息"的格子：
                                //   showAtLocation(View,int,int,int) → args[0]；showAsDropDown(View,…) → args[0]
                                if (p.args != null && p.args.length > 0
                                        && p.args[0] instanceof android.view.View) {
                                    lastView = (android.view.View) p.args[0];
                                    Log.d(T + "anchor view: " + lastView.getClass().getName()
                                            + " tag=" + (lastView.getTag() == null ? "null"
                                            : lastView.getTag().getClass().getName()));
                                }
                                injectIntoPopup(p.thisObject);
                            } catch (Throwable ignored) {
                                // 绝不影响微信自己的弹窗
                            }
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 换下一个
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return hits;
    }

    /**
     * 在弹窗对象里找"菜单"——**判据是 `instanceof android.view.Menu`**（标准接口，与混淆名无关）。
     * 实测结构：弹窗 kj5.f5（PopupWindow 子类，只有一个字段 a）→ 字段 a 就是菜单 kj5.i4，
     * 而 kj5.i4 实现的就是 android.view.Menu（有 add/addSubMenu/addIntentOptions 等标准方法）。
     * 找到后直接用**接口方法** add(int groupId, int itemId, int order, CharSequence title) 插入 ✓
     */
    private static boolean injectViaMenuInterface(Object holder) {
        return injectViaMenuInterface(holder, 0);
    }

    /**
     * 递归找菜单：日志实测菜单不在 PopupWindow 自己身上，而在它的包装类里
     * （popup.showAtLocation ← ju5.c0#p ← ju5.q#onLongClick），所以字段要往下挖 3 层。
     */
    private static boolean injectViaMenuInterface(Object holder, int depth) {
        if (holder == null || depth > 3) return false;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class || ft.isArray()) continue;
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v == null) continue;
                    if (!(v instanceof android.view.Menu)) {
                        // 不是菜单就往里再挖一层（包装类/持有者）
                        if (depth < 3 && !ft.getName().startsWith("android.") && !ft.getName().startsWith("java.")) {
                            if (injectViaMenuInterface(v, depth + 1)) return true;
                        }
                        continue;
                    }
                    if (v == markedMenu) return true;
                    android.view.Menu menu = (android.view.Menu) v;
                    learnMenuClass(v.getClass());       // ★ 学它的构造：下次长按在"造出来那一刻"就插
                    android.view.MenuItem item = menu.add(ITEM_GROUP, ITEM_ID, 0, ITEM_LABEL);
                    markedMenu = v;
                    lastOwner = v;
                    Log.d(T + "inject via Menu interface: holder=" + holder.getClass().getName()
                            + " menu=" + v.getClass().getName() + " item=" + (item != null));
                    return item != null;
                } catch (Throwable ignored) {
                    // 看下一个字段
                }
            }
        }
        return false;
    }

    /**
     * ★★ 学菜单类的构造函数：菜单"刚被造出来"时插项，微信随后填充自己的项，
     * 我们那一项就会跟着一起被渲染出来（弹窗摆好之后再插，视图已经画完，看不见）。
     */
    private static void learnMenuClass(final Class<?> menuClass) {
        if (menuClass == null || !knownMenuClasses.add(menuClass.getName())) return;
        try {
            for (Constructor<?> ctor : menuClass.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                Object self = p.thisObject;
                                if (!(self instanceof android.view.Menu)) return;
                                int before = ((android.view.Menu) self).size();
                                android.view.MenuItem mi =
                                        ((android.view.Menu) self).add(ITEM_GROUP, ITEM_ID, 10, ITEM_LABEL);
                                Log.d(T + "item added at menu-construction: " + menuClass.getName()
                                        + " size " + before + "->" + ((android.view.Menu) self).size()
                                        + " itemId=" + (mi == null ? -1 : mi.getItemId()));
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    });
                } catch (Throwable ignored) {
                    // 下一个构造
                }
            }
            // ★ 关键：微信会造多个 kj5.i4（空壳模板 + 真正上屏那个）。
            // 判据：谁的项数涨到 8 以上，谁就是真菜单 —— 就在它加完项的那一刻插我们的项。
            int hooked = 0;
            for (final Method m : menuClass.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (!android.view.MenuItem.class.isAssignableFrom(m.getReturnType())) continue;
                if (ps.length == 0 || ps.length > 6) continue;
                boolean intish = false;
                for (Class<?> pc : ps) if (pc == int.class || pc == CharSequence.class) { intish = true; break; }
                if (!intish) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                if (!(p.thisObject instanceof android.view.Menu)) return;
                                android.view.Menu menu = (android.view.Menu) p.thisObject;
                                if (p.thisObject == markedMenu) return;
                                int n = menu.size();
                                if (n < 8) return;                       // 还是模板，等真正的菜单
                                markedMenu = p.thisObject;
                                learnMenuClass(menuClass);               // 已经学过，幂等
                                // 同理：不在"任何长大了的菜单"上插，避免污染标题栏。
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
            Log.d(T + "learned menu class: " + menuClass.getName()
                    + " ctors=" + menuClass.getDeclaredConstructors().length + " addMethods=" + hooked);
        } catch (Throwable ignored) {
            // 静默
        }
    }

    /** 在弹窗对象里找"菜单对象"，找到就插项。 */
    /**
     * ★★ 动态学习构造器（与版本无关）：
     * 日志实测长按链是
     *   PopupWindow.showAtLocation ← eu5.s0#p ← eu5.s0#n ← eu5.s0#f ← viewitems.m0#g ← ...#onLongClick
     * 菜单对象在 **构造器类（eu5.s0）** 里，**不在 PopupWindow 上** ✗，且该类名每版都变 ✗。
     * 那就让**调用栈**告诉我它是谁：弹窗出现时取栈里第一个微信类，挂它的所有方法；
     * 下次长按时它会被叫到，thisObject 就是构造器 —— 再递归找菜单插项即可。
     * （因此：第一次长按 = 学习，之后每次长按都有效）
     */
    private static void learnBuilderFromStack() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                // 只认"带点的类名"：微信混淆类是 eu5.s0 / ju5.q 这种（一定有点），
                // 而 Xposed 蹦床类是单字符的 s / l（没有点）—— 一开始就被这条挡住了。
                if (cn.indexOf('.') < 0) continue;
                if (cn.startsWith("android.") || cn.startsWith("java.") || cn.startsWith("com.android.")
                        || cn.startsWith("de.robv") || cn.startsWith("io.github.lex0m.lexin")
                        || cn.startsWith("x.") || cn.startsWith("gxnix.") || cn.contains("XC_MethodHook")
                        || cn.contains("LSPosed") || cn.contains("sandhook")) continue;
                if (knownBuilders.contains(cn)) return;              // 已经学过
                Class<?> c = Class.forName(cn, false, Features.loader);
                knownBuilders.add(cn);
                int n = 0;
                for (Method m : c.getDeclaredMethods()) {
                    int pc = m.getParameterCount();
                    if (pc > 5) continue;
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                try {
                                    if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                                    if (p.thisObject == null || p.thisObject == markedMenu) return;
                                    if (injectViaMenuInterface(p.thisObject)) {
                                        markedMenu = p.thisObject;
                                        Log.d(T + "builder inject ok: " + p.thisObject.getClass().getName());
                                    }
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                            }
                        });
                        n++;
                    } catch (Throwable ignored) {
                        // 下一个
                    }
                }
                if (n == 0) {
                    knownBuilders.remove(cn);                       // 这个不是构造器，继续往下找
                    continue;
                }
                Log.d(T + "learned builder from stack: " + cn + " methods=" + n);
                return;                                             // 学第一个"有方法可挂"的微信类
            }
        } catch (Throwable ignored) {
            // 静默
        }
    }

    /** 聊天/朋友圈菜单的格子文案（稳定，不随版本变）。 */
    private static final String[] MENU_LABELS = {
            "复制", "转发", "收藏", "删除", "多选",
            "引用", "提醒", "翻译", "搜一搜", "连续朗读",
            "转发给", "分享", "编辑", "撤回",
            // 朋友圈图片/视频长按菜单（之前没覆盖 → 长按图片不弹「乐信」的根因）
            "保存图片", "保存到手机", "保存视频", "保存",
            "识别图中二维码", "识别二维码", "扫码",
            "设为壁纸", "设为聊天背景", "发送给朋友", "在聊天中查看",
    };
    private static final String TAG_CELL = "lexin_fwd_cell";
    /** 已经注入过的弹窗（identity hash），防止 post 延迟导致重复添加。 */
    private static final java.util.Set<Integer> injectedPopups =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    /** 已经用菜单模型注入过的菜单（identity hash），保证一个菜单只有一项。 */
    private static final java.util.Set<Integer> injectedMenus =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private static void injectIntoPopup(Object popup) {
        if (popup == null) return;
        try {
            // ★ 视图注入是唯一入口：成功就结束（两个入口都开会造成"同一菜单出现两格"✗）
            int v = injectViewCell(popup);
            if (v != 0) return;               // 1=已加；-1=不是聊天菜单（别往无关菜单里塞东西）
            // v==0：确实是聊天菜单，但这次视图没加上 → 走下面的菜单模型兜底
        } catch (Throwable ignored) {
            // 视图注入失败再走模型那条
        }
        learnBuilderFromStack();
        if (popup == markedMenu) return;
        // ★ 最可靠的一条：按标准 Menu 接口找（与类名无关）
        if (injectViaMenuInterface(popup)) return;
        if (injectViaMenuInterface(lastOwner)) return;
        // ★ ① 弹窗**自己**可能就是菜单（实测 kj5.f5 与菜单类 kj5.i4 同包，很可能是继承关系）
        try {
            Method self = addMethodOf(popup);
            if (self != null) {
                markedMenu = popup;
                lastOwner = popup;
                Log.d(T + "popup inject on self: " + popup.getClass().getName()
                        + " add=" + self.getName() + "/" + self.getParameterCount());
                addItem(popup);
                return;
            }
            Log.d(T + "popup self has no add(): " + popup.getClass().getName());
        } catch (Throwable ignored) {
            // 继续找字段
        }
        for (Class<?> c = popup.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.isArray() || ft == String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(popup);
                    if (v == null || v == popup) continue;
                    Method add = addMethodOf(v);
                    if (add == null) continue;
                    markedMenu = popup;                     // 同一个弹窗只插一次
                    lastOwner = v;
                    Log.d(T + "popup inject: popup=" + popup.getClass().getName()
                            + " menu=" + v.getClass().getName() + " add=" + add.getName());
                    addItem(v);
                    return;
                } catch (Throwable ignored) {
                    // 看下一个字段
                }
            }
        }
        Log.d(T + "popup inject: no menu field on " + popup.getClass().getName());
    }

    /** 参数里第一个"像菜单"的对象。 */
    private static Object firstMenuArg(Object[] args) {
        for (Object a : args) {
            if (a != null && isMenu(a)) return a;
        }
        return null;
    }

    /** 这个对象是不是"菜单"：看它有没有 add(int, CharSequence, Drawable) -> MenuItem。 */
    private static boolean isMenu(Object o) {
        return addMethodOf(o) != null;
    }

    /**
     * 找"往菜单里加项"的方法。**照抄 WAuxiliary（成品）的签名**：
     *   (int groupId, int itemId, int, CharSequence title, int) → MenuItem
     * 三参版本 (int, CharSequence, Drawable) → MenuItem 作为退路。
     */
    private static Method addMethodOf(Object menu) {
        if (menu == null) return null;
        try {
            for (Method m : allMethods(menu.getClass())) {
                Class<?>[] p = m.getParameterTypes();
                if (!MenuItem.class.isAssignableFrom(m.getReturnType())) continue;
                if (p.length == 5 && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && p[3] == CharSequence.class && p[4] == int.class) {
                    return m;                                   // 成品用的就是这个
                }
            }
            for (Method m : allMethods(menu.getClass())) {
                Class<?>[] p = m.getParameterTypes();
                if (!MenuItem.class.isAssignableFrom(m.getReturnType())) continue;
                if (p.length == 3 && p[0] == int.class && p[1] == CharSequence.class
                        && p[2] == Drawable.class) {
                    return m;                                   // 退路
                }
            }
        } catch (Throwable ignored) {
            // 找不到
        }
        return null;
    }

    /** 用菜单对象自己的 add 方法（签名固定：int, CharSequence, Drawable → MenuItem，名字被混淆所以按签名找）。 */
    private static void addItem(Object menu) {
        try {
            if (menu == null) return;
            if (!injectedMenus.add(System.identityHashCode(menu))) return;   // 同一个菜单只加一次
            Method m = addMethodOf(menu);
            if (m == null) {
                StringBuilder sb = new StringBuilder();
                for (Method cand : allMethods(menu.getClass())) {
                    if (MenuItem.class.isAssignableFrom(cand.getReturnType())) {
                        sb.append(cand.getName()).append("(").append(cand.getParameterCount()).append(") ");
                    }
                }
                Log.d(T + "no add() on " + menu.getClass().getName() + " 候选: " + sb);
                return;
            }
            m.setAccessible(true);
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 5) {
                // 成品同款：(groupId, itemId, ?, title, ?)
                m.invoke(menu, ITEM_GROUP, ITEM_ID, 0, ITEM_LABEL, 0);
            } else {
                m.invoke(menu, ITEM_ID, ITEM_LABEL, null);
            }
            Log.d(T + "menu item injected via " + m.getName() + " on " + menu.getClass().getName());
        } catch (Throwable t) {
            Log.d(T + "inject failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 点击回调：微信的 `onMMMenuItemSelected(MenuItem,int):void`（同样没混淆）。
     * 认出是我们那一项就自己处理掉，别让微信再走它自己的分支。
     */
    /**
     * ★ 点击入口（框架级）：弹窗里的九宫格是 AdapterView，任何一格的点击都要经过
     * AdapterView#performItemClick。在这里认领我们那一格，然后用 setResult(true) 吃掉，
     * 微信就不会再拿一个不认识的 id 去走它自己的 switch。
     */
    /** 截获"会话选择器"的返回，拿到目标会话后发语音。 */
    private static int hookPickResult() {
        try {
            Class<?> act = Class.forName("android.app.Activity", false, Features.loader);
            XposedBridge.hookAllMethods(act, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        final Object msg = pendingVoice;
                        if (msg == null) return;
                        Object data = p.args != null && p.args.length > 2 ? p.args[2] : null;
                        if (!(data instanceof android.content.Intent)) return;
                        android.content.Intent it = (android.content.Intent) data;
                        Log.d(T + "onActivityResult req=" + p.args[0] + " res=" + p.args[1]
                                + " extras=" + it.getExtras());
                        // ★ 实测键名（微信 8.0.78）：Select_Conv_User = wxid_xxx
                        String conv = it.getStringExtra("Select_Conv_User");
                        if (conv == null) conv = it.getStringExtra("recent_conv_name");
                        if (conv == null) conv = it.getStringExtra("select_conv_name");
                        if (conv == null) conv = it.getStringExtra("conv_name");
                        if (conv == null) conv = it.getStringExtra("chat_username");
                        if (conv == null) conv = it.getStringExtra("brand_user_name");
                        if (conv == null) {
                            Log.d(T + "选人返回里没有会话名，keys=" + it.getExtras());
                            return;
                        }
                        pendingVoice = null;
                        Log.d(T + "选中会话: " + conv);
                        VoiceSend.forward(msg, conv, new Runnable() {
                            @Override
                            public void run() {
                                extractRestricted();
                            }
                        });
                    } catch (Throwable t) {
                        Log.d(T + "onActivityResult 处理失败: " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hookClickEntry() {
        int n = hookPerformClick();
        try {
            Class<?> av = Class.forName("android.widget.AdapterView", false, Features.loader);
            XposedBridge.hookAllMethods(av, "performItemClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                        if (p.args.length < 2 || !(p.args[1] instanceof Integer)) return;
                        android.widget.AdapterView<?> view = (android.widget.AdapterView<?>) p.thisObject;
                        android.widget.Adapter adapter = view.getAdapter();
                        if (adapter == null) return;
                        Object item = adapter.getItem((Integer) p.args[1]);
                        boolean ours = false;
                        if (item instanceof MenuItem) {
                            ours = ((MenuItem) item).getItemId() == ITEM_ID;
                        } else if (item != null) {
                            ours = item.toString().contains(ITEM_LABEL);
                        }
                        if (!ours) return;
                        p.setResult(true);                 // 吃掉这次点击
                        Log.d(T + "our item clicked (performItemClick)");
                        onOurItem(lastOwner != null ? lastOwner : lastView);
                    } catch (Throwable ignored) {
                        // 别影响微信自己的点击
                    }
                }
            });
            n += 1;
        } catch (Throwable ignored) {
            // AdapterView 那条路不通就算了
        }
        return n;
    }

    /**
     * ★ 最终兜底：`View#performClick` —— 任何可点控件的点击都要经过它（与容器是 GridView、
     * RecyclerView 还是自定义布局无关）。判据：点到的 View 自己或它的 1~2 层父容器里，
     * 有没有一个 TextView 的文字含我们的标签。命中就执行动作并吃掉这次点击。
     */
    private static int hookPerformClick() {
        try {
            Class<?> v = Class.forName("android.view.View", false, Features.loader);
            XposedBridge.hookAllMethods(v, "onTouchEvent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                        if (!(p.thisObject instanceof android.view.View)) return;
                        // ★ 先判事件类型再查父级文字：onTouchEvent 是全局热路径，
                        //   每次 DOWN/MOVE 都做一遍"递归查父级 TextView"代价太大。
                        Object ev = p.args != null && p.args.length > 0 ? p.args[0] : null;
                        if (!(ev instanceof android.view.MotionEvent)) return;
                        if (((android.view.MotionEvent) ev).getAction() != android.view.MotionEvent.ACTION_UP) return;
                        android.view.View view = (android.view.View) p.thisObject;
                        if (!hasOurLabel(view, 0)) return;
                        Log.d(T + "our item touched (onTouchEvent)");
                        onOurItem(lastOwner != null ? lastOwner : view);
                    } catch (Throwable ignored) {
                        // 别影响别的触摸
                    }
                }
            });
            XposedBridge.hookAllMethods(v, "performClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                        if (!(p.thisObject instanceof android.view.View)) return;
                        android.view.View view = (android.view.View) p.thisObject;
                        if (!hasOurLabel(view, 0)) return;
                        p.setResult(true);                       // 吃掉
                        Log.d(T + "our item clicked (performClick)");
                        onOurItem(lastOwner != null ? lastOwner : view);
                    } catch (Throwable ignored) {
                        // 别影响别的点击
                    }
                }
            });
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * ★★ 视图级注入（主方案）：
     * 弹窗内容视图里如果能找到 ≥3 个"聊天菜单文案"，就认定这是聊天菜单弹窗，
     * 找到这些格子的共同父容器（九宫格），在末尾加一格我们自己的（布局/风格仿照它），
     * 点击监听由我们自己挂 —— 不依赖任何混淆类、实例、填充时机。
     */
    /** 返回值：1=已加（或已处理）/ 0=是聊天菜单但这次没加成功（可走菜单模型兜底）/ -1=不是聊天菜单 */
    private static int injectViewCell(Object popup) {
        if (!(popup instanceof android.widget.PopupWindow)) return -1;
        android.view.View root = ((android.widget.PopupWindow) popup).getContentView();
        if (root == null) return -1;
        int popupId = System.identityHashCode(popup);
        if (!injectedPopups.add(popupId)) return 1;                  // 这个弹窗已经处理过
        if (root.findViewWithTag(TAG_CELL) != null) return 1;         // 已经加过

        java.util.List<android.widget.TextView> hits = new java.util.ArrayList<>();
        collectMenuLabels(root, hits, 0);
        // 聊天菜单至少 3 个已知项；朋友圈菜单项更少（长按图片可能只有「保存图片」一个），
        // 只要在朋友圈 context 里命中 ≥1 个已知项就注入 —— 之前 ≥2 把长按图片的菜单挡掉了。
        boolean snsMenu = isSnsMenu(root.getContext());
        if (hits.size() < 3 && !(snsMenu && hits.size() >= 1)) {
            injectedPopups.remove(popupId);
            return -1;                                                        // 不是我们认识的菜单
        }

        // 找"共同父容器"：从第一个命中格子的祖先里，挑出包含全部命中格子的最近一层
        android.view.ViewGroup grid = commonParent(hits);
        if (grid == null) { return 0; }

        android.content.Context ctx = root.getContext();
        android.widget.LinearLayout cell = new android.widget.LinearLayout(ctx);
        cell.setOrientation(android.widget.LinearLayout.VERTICAL);
        cell.setTag(TAG_CELL);
        cell.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
        cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        android.widget.TextView icon = new android.widget.TextView(ctx);
        icon.setText("↗");
        icon.setTextColor(0xFFFFFFFF);
        icon.setTextSize(20);
        icon.setGravity(android.view.Gravity.CENTER);
        cell.addView(icon, new android.widget.LinearLayout.LayoutParams(-1, dp(ctx, 30)));

        android.widget.TextView label = new android.widget.TextView(ctx);
        label.setText("乐信");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(10);
        label.setSingleLine(true);
        label.setMaxLines(1);
        label.setEllipsize(null);
        label.setGravity(android.view.Gravity.CENTER);
        cell.addView(label, new android.widget.LinearLayout.LayoutParams(-1, -2));

        // ★ 官方转发：记住这个九宫格 + 里面文案为「转发」的那个原生格子
        final android.view.View nativeFwd = findForwardCell(grid);
        final Object popupObj = popup;
        Log.d(T + "native 转发 cell: " + (nativeFwd == null ? "null" : nativeFwd.getClass().getName()));
        cell.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                Log.d(T + "our cell clicked; nativeFwd=" + (nativeFwd != null));
                // ★ 朋友圈：菜单里的"乐信"→ 选文字/图片 → 转发（聊天消息的转发规则不适用）
                try {
                    if (isSnsMenu(v.getContext())) {
                        if (popupObj instanceof android.widget.PopupWindow) {
                            ((android.widget.PopupWindow) popupObj).dismiss();
                        }
                        SnsForward.show(lastView != null ? lastView : v);
                        return;
                    }
                } catch (Throwable ignored) {
                    // 继续走聊天那条
                }
                try {
                    if (nativeFwd != null) {
                        // 先关掉长按菜单，再触发微信自己的转发（选人 + 发送都由微信完成）
                        if (popupObj instanceof android.widget.PopupWindow) {
                            ((android.widget.PopupWindow) popupObj).dismiss();
                        }
                        boolean ok = nativeFwd.performClick();
                        Log.d(T + "official forward triggered=" + ok);
                        if (ok) return;
                    }
                } catch (Throwable ignored) {
                    // 退回复制/导出
                }
                // ★ 语音：走官方发送（造一条自己的语音 → setVoice → 启动语音服务）
                try {
                    Object m0 = findMessage(lastOwner);
                    Object ty0 = m0 == null ? null : fieldDeep(m0, "field_type");
                    int type0 = ty0 == null ? -1 : ((Number) ty0).intValue();
                    if (type0 == 34) {
                        // 实测+成品对照：聊天里收到的语音是**加密/压缩存放**（voice2/<hex桶>/<数字>），
                        // 任何开源实现都只支持"收藏里的语音"✗ —— 直接复制加密文件发出去必然是空语音 ✗。
                        // 所以这里改为：高权限导出原始文件 + 明确告知，而不是发一条空语音 ✗
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                extractRestricted();
                            }
                        }, "lexin-voice-export").start();
                        return;
                    }
                } catch (Throwable ignored) {
                    // 落到下面的导出
                }
                // 原生转发不可用（限制转发的消息）→ 用微信自己的权限把源文件抠出来
                // ★ 必须后台线程：递归扫微信私有目录会卡死 UI（实测闪退 ✗）
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        extractRestricted();
                    }
                }, "lexin-extract").start();
            }
        });

        try {
            // ★ 关键：自绘容器（kj5.e5）按它自己的 LayoutParams 摆放子 View。
            // 默认 LP 的尺寸可能是 0 ⇒ 加进去也看不见 ✗。所以照抄一个相邻格子的 LP。
            android.view.View sibling = null;
            for (android.widget.TextView tv : hits) {
                android.view.ViewParent vp = tv.getParent();
                if (vp instanceof android.view.View && ((android.view.View) vp).getParent() == grid) {
                    sibling = (android.view.View) vp;
                    break;
                }
                if (vp == grid) { sibling = tv; break; }
            }
            if (sibling != null && sibling.getLayoutParams() != null) {
                android.view.ViewGroup.LayoutParams src = sibling.getLayoutParams();
                android.view.ViewGroup.LayoutParams lp = null;
                try {
                    // 用 LP 自己的拷贝构造（GridLayout.LayoutParams 之类都有）
                    lp = (android.view.ViewGroup.LayoutParams) src.getClass()
                            .getConstructor(android.view.ViewGroup.LayoutParams.class)
                            .newInstance(src);
                } catch (Throwable ignored) {
                    lp = src;                       // 退路：直接复用同一个 LP
                }
                lp.width = src.width;
                lp.height = src.height;
                cell.setLayoutParams(lp);
                Log.d(T + "cell LP copied from " + sibling.getClass().getName()
                        + " type=" + lp.getClass().getSimpleName()
                        + " w=" + src.width + " h=" + src.height);
            }
            // ★ 防崩溃：不在当前布局/触摸流程里改视图树，挪到下一帧再做
            final android.view.ViewGroup g2 = grid;
            final android.view.View c2 = cell;
            g2.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (c2.getParent() == null) g2.addView(c2);
                        g2.requestLayout();
                    } catch (Throwable ignored) {
                        // 加不进去也不能崩
                    }
                }
            });
            cell.setVisibility(android.view.View.VISIBLE);
            hookGridTouch(grid.getClass(), cell);      // ★ 容器自己吃触摸，只能从容器这一层捞
            Log.d(T + "view cell injected into " + grid.getClass().getName()
                    + " (labels found=" + hits.size() + ")");
            return 1;
        } catch (Throwable ignored) {
            // 加不进去就算了
            return 0;
        }
    }

    /** 已经挂过触摸钩子的容器类。 */
    private static final java.util.Set<String> knownGrids =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 我们那一格（触摸判点用）。 */
    private static volatile android.view.View ourCell;

    /**
     * ★ 容器（kj5.e5 这种自绘九宫格）自己消费触摸，子 View 的 onClick 根本不会触发 ✗。
     * 所以挂容器的 onTouchEvent：ACTION_UP 时按坐标判断落点是否在我们那一格里。
     */
    private static void hookGridTouch(final Class<?> gridClass, android.view.View cell) {
        ourCell = cell;
        if (!knownGrids.add(gridClass.getName())) return;
        try {
            XposedBridge.hookAllMethods(gridClass, "onTouchEvent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.onDefault(Prefs.K_FWD_MENU)) return;
                        android.view.View self = (android.view.View) p.thisObject;
                        Object ev = p.args != null && p.args.length > 0 ? p.args[0] : null;
                        if (!(ev instanceof android.view.MotionEvent)) return;
                        android.view.MotionEvent me = (android.view.MotionEvent) ev;
                        if (me.getAction() != android.view.MotionEvent.ACTION_UP) return;
                        android.view.View c = ourCell;
                        if (c == null || c.getParent() != self) return;
                        float x = me.getX(), y = me.getY();
                        boolean hit = x >= c.getLeft() && x <= c.getRight()
                                && y >= c.getTop() && y <= c.getBottom();
                        Log.d(T + "grid touch up x=" + (int) x + " y=" + (int) y
                                + " cell=[" + c.getLeft() + "," + c.getTop() + ","
                                + c.getRight() + "," + c.getBottom() + "] hit=" + hit);
                        if (!hit) return;
                        p.setResult(true);                 // 吃掉
                        onOurItem(lastOwner != null ? lastOwner : lastView);
                    } catch (Throwable ignored) {
                        // 别影响正常触摸
                    }
                }
            });
            Log.d(T + "grid touch hook armed: " + gridClass.getName());
        } catch (Throwable ignored) {
            // 挂不上就算了
        }
    }

    /**
     * ★ 受限消息提取：微信不提供转发时，用**微信进程自己的权限**把源文件拷到
     *   Download/乐信导出/ —— 语音（type 34）的 field_imgPath 就是 voice2 下的 .amr 文件名。
     * （下一步：把提取出的文件交给官方发送接口发出去）
     */
    private static void extractRestricted() {
        try {
            Object msg = findMessage(lastOwner);
            if (msg == null) { toast("没取到消息对象"); return; }
            Object ty = fieldDeep(msg, "field_type");
            int type = ty == null ? -1 : ((Number) ty).intValue();
            Object ip = fieldDeep(msg, "field_imgPath");
            String name = ip == null ? null : String.valueOf(ip);
            Log.d(T + "extract: type=" + type + " imgPath=" + name);
            if (type == 34) {
                // ★★ 无痕提取（不播放）：消息 → VoiceInfo → 微信自己的 getAmrFullPath → AMR 路径
                String amr = voicePathSilently(msg, lastTalker);
                if (amr != null) {
                    java.io.File srcF = new java.io.File(amr);
                    Log.d(T + "silent voice path=" + amr + " size=" + srcF.length());
                    if (srcF.isFile() && srcF.length() > 0) {
                        java.io.File outDir = exportDir();
                        // ★ 微信取出来的是 SILK（文件头 "\x02#!SILK_V3"），不是 AMR ✗
                        //   去掉开头那个 0x02 头 ⇒ 标准 SILK 文件，任何 silk 解码器可直接转 MP3 ✓
                        String nm = srcF.getName();
                        if (nm.toLowerCase().endsWith(".amr")) {
                            nm = nm.substring(0, nm.length() - 4) + ".silk";
                        }
                        // ★ 先用微信自己的解码器把 SILK 转成 AMR（writeVoiceFile）
                        java.io.File decoded = decodeSilkToAmr(msg, srcF);
                        if (decoded != null && decoded.isFile() && decoded.length() > 0) {
                            java.io.File outAmr = new java.io.File(outDir, srcF.getName());
                            copyFile(decoded, outAmr);
                            String shownAmr = publishPublic(outAmr, outAmr.getName());
                            toast("语音已提取（微信解码为 AMR）："
                                    + (shownAmr != null ? shownAmr : outAmr.getAbsolutePath())
                                    + "（" + outAmr.length() / 1024 + "KB）");
                            Log.d(T + "decoded amr ok -> " + outAmr.getAbsolutePath());
                            return;
                        }
                        java.io.File out = new java.io.File(outDir, nm);
                        int stripped = copyStripSilkHeader(srcF, out);
                        if (!out.isFile() || out.length() <= 0) {
                            toast("语音导出失败（0 字节）");
                            Log.d(T + "silent export 0 byte: " + out.getAbsolutePath());
                            return;
                        }
                        // ★ 转码只在乐信 App 的进程里做（微信进程保持零 native ✓）。
                        //   输入用 byte[] 递过去：乐信 App 只有 INTERNET 权限，读不了 /sdcard/Download ✗
                        String shownSilk = publishPublic(out, out.getName());
                        String label = shownSilk != null ? shownSilk : out.getAbsolutePath();
                        boolean launched = launchConvert(out, srcF.getName());
                        toast(launched
                                ? "语音已提取：" + label + "（" + out.length() / 1024 + "KB），正在转 MP3…"
                                : "语音已提取：" + label + "（" + out.length() / 1024 + "KB）");
                        Log.d(T + "silent export ok -> " + out.getAbsolutePath()
                                + " stripped=" + stripped + " launched=" + launched);
                        return;
                    }
                }
            }
            if (type == 34 && name != null) {
                java.io.File f = findVoiceFile(name);
                if (f != null && f.exists()) {
                    java.io.File outDir = exportDir();
                    java.io.File out = new java.io.File(outDir, f.getName());
                    copyFile(f, out);
                    String shownV = publishPublic(out, out.getName());
                    toast("语音已导出（微信语音是加密存放，无法直接转发为语音）："
                            + (shownV != null ? shownV : out.getAbsolutePath()));
                    Log.d(T + "voice exported -> " + out.getAbsolutePath() + " " + out.length() + "B");
                    return;
                }
                // ★ v1.2 兜底：微信已改用 VFS 访问语音（voice2 里是 256 个 hex 桶 + 纯数字文件名 ✗），
                //   field_imgPath 不是文件名 ✗ ⇒ 改按"修改时间与消息创建时间最接近"匹配。
                long ct = 0;
                Object cto = fieldDeep(msg, "field_createTime");
                if (cto instanceof Number) ct = ((Number) cto).longValue();
                if (ct < 1000000000000L) ct *= 1000L;           // 秒 → 毫秒
                java.io.File near = nearestVoice(ct);
                if (near != null) {
                    java.io.File outDir = exportDir();
                    java.io.File out = new java.io.File(outDir, near.getName() + ".amr");
                    copyFile(near, out);
                    String shownN = publishPublic(out, out.getName());
                    toast("语音已导出（按时间匹配，微信语音加密存放、无法直接转发为语音）："
                            + (shownN != null ? shownN : out.getAbsolutePath()));
                    Log.d(T + "voice by time -> " + near.getAbsolutePath() + " -> " + out.getAbsolutePath());
                    return;
                }
                Log.d(T + "voice scan layout: " + dumpVoiceLayout());
                toast("没找到语音文件（可在日志里查看目录结构）");
                return;
            }
            toast("这一类型暂不支持导出（type=" + type + "）");
        } catch (Throwable t) {
            Log.d(T + "extract failed: " + t.getClass().getSimpleName());
            toast("导出失败：" + t.getClass().getSimpleName());
        }
    }

    /** 在微信私有目录里按文件名找语音文件（模块运行在微信进程内 ⇒ 有它的权限 ✓）。 */
    /**
     * ★ 精确定位语音文件（不扫目录）：
     *   微信固定结构 = /data/data/com.tencent.mm/MicroMsg/<账户hash>/voice2/<会话id>/<imgPath>.amr
     *   其中 <会话id> = field_talker（已有 ✓・会话 id 里的 / 要换成 _ ✓）
     *   只有 <账户hash> 需要确定：找 MicroMsg 下"含 voice2 子目录"的那一层（1 级，不递归）
     */
    private static java.io.File findVoiceFile(String name) {
        try {
            java.io.File base = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            java.io.File[] tops = base.listFiles();
            if (tops == null) return null;
            String talker = lastTalker == null ? null : lastTalker.replace('/', '_').replace(".", "_");
            for (java.io.File hashDir : tops) {
                if (hashDir == null || !hashDir.isDirectory()) continue;
                for (String sub : new String[]{"voice2", "voice3"}) {
                    java.io.File vd = new java.io.File(hashDir, sub);
                    if (!vd.isDirectory()) continue;
                    // ① 精确路径：<voice2>/<talker>/<name>.amr
                    if (talker != null) {
                        java.io.File conv = new java.io.File(vd, talker);
                        java.io.File hit = tryNames(conv, name);
                        if (hit != null) { Log.d(T + "voice hit exact: " + hit.getAbsolutePath()); return hit; }
                    }
                    // ② 同层遍历（只 1 级）：<voice2>/<任意会话>/
                    java.io.File[] convs = vd.listFiles();
                    if (convs == null) continue;
                    for (java.io.File conv : convs) {
                        if (conv == null || !conv.isDirectory()) continue;
                        java.io.File hit = tryNames(conv, name);
                        if (hit != null) { Log.d(T + "voice hit level2: " + hit.getAbsolutePath()); return hit; }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 按时间邻近匹配语音文件：在 voice2/<桶>/<文件> 里找修改时间与消息创建时间最接近的那个
     * （微信改 VFS 后 imgPath 不再是文件名 ✗，这是不碰它内部映射的最稳近似 ✓）。
     */
    private static java.io.File nearestVoice(long msgTimeMs) {
        try {
            java.io.File base = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            java.io.File[] tops = base.listFiles();
            if (tops == null) return null;
            java.io.File best = null;
            long bestDiff = Long.MAX_VALUE;
            for (java.io.File hashDir : tops) {
                if (hashDir == null || !hashDir.isDirectory()) continue;
                java.io.File v2 = new java.io.File(hashDir, "voice2");
                if (!v2.isDirectory()) continue;
                java.io.File[] buckets = v2.listFiles();
                if (buckets == null) continue;
                for (java.io.File bucket : buckets) {
                    if (bucket == null || !bucket.isDirectory()) continue;
                    java.io.File[] files = bucket.listFiles();
                    if (files == null) continue;
                    for (java.io.File f : files) {
                        if (f == null || !f.isFile() || f.length() < 500) continue;   // 太小的不是语音
                        long diff = Math.abs(f.lastModified() - msgTimeMs);
                        if (diff < bestDiff) { bestDiff = diff; best = f; }
                    }
                }
            }
            long window = 3 * 60 * 1000L;                     // ±3 分钟
            if (best != null && bestDiff <= window) {
                Log.d(T + "nearest voice diff=" + bestDiff + "ms file=" + best.getAbsolutePath());
                return best;
            }
            Log.d(T + "nearest voice too far: diff=" + bestDiff);
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 一次性把 MicroMsg 下与语音有关的真实结构打出来（只 1~2 级，不递归）。 */
    private static String dumpVoiceLayout() {
        StringBuilder sb = new StringBuilder();
        try {
            java.io.File base = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            java.io.File[] tops = base.listFiles();
            if (tops == null) return "MicroMsg 不可读";
            int shown = 0;
            for (java.io.File hashDir : tops) {
                if (hashDir == null || !hashDir.isDirectory()) continue;
                java.io.File[] subs = hashDir.listFiles();
                if (subs == null) continue;
                for (java.io.File sub : subs) {
                    String n = sub.getName();
                    if (n == null || !n.startsWith("voice")) continue;
                    sb.append(hashDir.getName()).append('/').append(n).append('[');
                    java.io.File[] convs = sub.listFiles();
                    int c = convs == null ? 0 : convs.length;
                    sb.append("dirs=").append(c).append("] ");
                    if (convs != null) {
                        int k = 0;
                        for (java.io.File conv : convs) {
                            sb.append(conv.getName()).append(conv.isDirectory() ? "/" : "");
                            if (conv.isDirectory()) {
                                java.io.File[] files = conv.listFiles();
                                if (files != null && files.length > 0) {
                                    sb.append('(').append(files.length).append(",e.g.")
                                      .append(files[0].getName()).append(')');
                                }
                            }
                            if (++k >= 3) break;
                        }
                    }
                    sb.append(" | ");
                    if (++shown >= 3) return sb.toString();
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return sb.length() == 0 ? "没找到 voice* 目录" : sb.toString();
    }

    /**
     * 把导出的 SILK 交给乐信 App 转 MP3（跨进程；微信进程零 native ✓）。
     *
     * 为什么不传路径：乐信 App 只有 INTERNET 权限（targetSdk 35），
     * **读不到 /sdcard/Download 下的文件** ✗ ⇒ 小文件直接塞 byte[]（Binder 上限约 1MB）。
     * 512KB 以上（极少见）才退回路径，并在 App 侧走权限兜底。
     */
    private static boolean launchConvert(java.io.File silk, String name) {
        try {
            android.content.Context ctx = lastView == null ? null : lastView.getContext();
            if (ctx == null) {
                Log.d(T + "launchConvert: 没有 context");
                return false;
            }
            android.content.Intent ci = new android.content.Intent();
            ci.setClassName("io.github.lex0m.lexin", "io.github.lex0m.lexin.ConvertActivity");
            ci.putExtra("name", name);
            byte[] data = null;
            try {
                long len = silk.length();
                if (len > 0 && len <= 512 * 1024) data = readAll(silk);
            } catch (Throwable t) {
                Log.d(T + "launchConvert: 读字节失败 " + t);
            }
            if (data != null) ci.putExtra("silk_bytes", data);
            else ci.putExtra("silk", silk.getAbsolutePath());
            ci.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            // ★ 先解析一次：R8 把 ConvertActivity 裁掉时这里就会返回 null（否则是运行期崩/静默失败 ✗）
            if (ctx.getPackageManager().resolveActivity(ci, 0) == null) {
                Log.d(T + "launchConvert: 组件不可用（ConvertActivity 缺失/被裁剪）");
                return false;
            }
            ctx.startActivity(ci);
            Log.d(T + "launchConvert ok: bytes=" + (data == null ? -1 : data.length)
                    + " path=" + silk.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.d(T + "launchConvert 失败: " + t);
            return false;
        }
    }

    /**
     * ★ 无痕取语音路径（不播放）：
     *   消息(com.tencent.mm.storage.e9) → ou5.x#a(msg, null) → VoiceInfo
     *   VoiceInfo → pv.p0#ej(voiceInfo, talker, false) → AMR 绝对路径
     * 两步都是微信自己的解码/取路径逻辑 ✓
     */
    private static String voicePathSilently(Object msg, String talker) {
        try {
            // ★★ 正确调用（照抄 WeKit 的 getVoiceFullPath）：
            //   getAmrFullPath(null, encPath, true)  ← 第一个参数就是 null，第二个才是 imgPath
            try {
                Object enc = fieldDeep(msg, "field_imgPath");
                String encPath = enc == null ? null : String.valueOf(enc);
                if (encPath != null && !encPath.isEmpty()) {
                    Class<?> c1 = Class.forName("pv.p0", false, Features.loader);
                    Object inst1 = null;
                    try {
                        java.lang.reflect.Constructor<?> ctor1 = c1.getDeclaredConstructor();
                        ctor1.setAccessible(true);
                        inst1 = ctor1.newInstance();
                    } catch (Throwable ignored) {
                        // 可能静态
                    }
                    for (java.lang.reflect.Method m : c1.getDeclaredMethods()) {
                        if (!m.getName().equals("ej")) continue;
                        if (m.getReturnType() != String.class || m.getParameterCount() != 3) continue;
                        m.setAccessible(true);
                        Object r = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                                ? m.invoke(null, null, encPath, true)
                                : m.invoke(inst1, null, encPath, true);
                        if (r instanceof String) {
                            String s = (String) r;
                            Log.d(T + "amr via ej(null,enc,true): " + s);
                            if (new java.io.File(s).isFile()) return s;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.d(T + "ej(null,enc,true) 失败: " + t);
            }
            // ★ 首选：pv.p0#cj(消息) → 直接返回 AMR 路径（WeKit getAmrFullPath 同款）
            try {
                Class<?> c0 = Class.forName("pv.p0", false, Features.loader);
                Object inst0 = null;
                try {
                    java.lang.reflect.Constructor<?> ctor0 = c0.getDeclaredConstructor();
                    ctor0.setAccessible(true);
                    inst0 = ctor0.newInstance();
                } catch (Throwable ignored) {
                    // 可能静态
                }
                for (java.lang.reflect.Method m : c0.getDeclaredMethods()) {
                    if (!m.getName().equals("cj")) continue;
                    if (m.getReturnType() != String.class || m.getParameterCount() != 1) continue;
                    m.setAccessible(true);
                    Object r = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            ? m.invoke(null, msg) : m.invoke(inst0, msg);
                    if (r instanceof String && ((String) r).length() > 0) {
                        Log.d(T + "amr via cj(msg): " + r);
                        String s = (String) r;
                        if (new java.io.File(s).isFile()) return s;      // 是完整路径 ✓
                        Log.d(T + "cj 返回的不是完整路径: " + s);
                    }
                }
            } catch (Throwable t) {
                Log.d(T + "cj 失败: " + t);
            }
            // ① 造 VoiceInfo
            Object voiceInfo = null;
            try {
                Class<?> vx = Class.forName("ou5.x", false, Features.loader);
                for (java.lang.reflect.Method m : vx.getDeclaredMethods()) {
                    if (!m.getName().equals("a")) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (!m.getParameterTypes()[0].getName().equals("com.tencent.mm.storage.e9")) continue;
                    m.setAccessible(true);
                    voiceInfo = m.invoke(null, msg, null);
                    if (voiceInfo != null) {
                        Log.d(T + "VoiceInfo ok: " + voiceInfo.getClass().getName());
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.d(T + "VoiceInfo 构造失败: " + t);
            }
            if (voiceInfo == null) return null;

            // ② 取 AMR 路径
            Class<?> c = Class.forName("pv.p0", false, Features.loader);
            Object inst = null;
            try {
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                inst = ctor.newInstance();
            } catch (Throwable ignored) {
                // 可能不需要实例
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("ej")) continue;
                if (m.getReturnType() != String.class) continue;
                if (m.getParameterCount() != 3) continue;
                m.setAccessible(true);
                Object r;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    r = m.invoke(null, voiceInfo, talker == null ? "" : talker, false);
                } else {
                    r = m.invoke(inst, voiceInfo, talker == null ? "" : talker, true);   // true = 要完整路径
                }
                if (r instanceof String) {
                    String s = (String) r;
                    Log.d(T + "amr via ej(...,true): " + s);
                    if (new java.io.File(s).isFile()) return s;
                }
            }
        } catch (Throwable t) {
            Log.d(T + "voicePathSilently fail: " + t);
        }
        return null;
    }

    /** 在某个会话目录里按名字试几种后缀。 */
    private static java.io.File tryNames(java.io.File dir, String name) {
        for (String suffix : new String[]{"", ".amr", ".silk", ".aud"}) {
            java.io.File f = new java.io.File(dir, name + suffix);
            if (f.exists() && f.isFile()) return f;
        }
        // 前缀匹配兜底（同目录下）
        java.io.File[] fs = dir.listFiles();
        if (fs != null) {
            for (java.io.File f : fs) {
                if (f.isFile() && f.getName().startsWith(name)) return f;
            }
        }
        return null;
    }

    private static java.io.File walk(java.io.File dir, String name, int depth) {
        if (dir == null || depth > 2) return null;
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (java.io.File f : fs) {
            // 磁盘上通常带 .amr 后缀（field_imgPath 不带 ✗）⇒ 前缀匹配
            if (f.isFile() && (f.getName().equals(name) || f.getName().startsWith(name))) return f;
            if (f.isDirectory()) {
                java.io.File r = walk(f, name, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * ★ 用**微信自己的解码器**把 SILK 转成 AMR（不引入任何 native 库）：
     *   VoiceLogic.writeVoiceFile = v61.d1#y(ou5.x, String, String, byte[]) → boolean
     * 调用后微信会把解码结果写到它自己的缓存，再按"最新修改时间"找到那个 AMR ✓
     */
    private static java.io.File decodeSilkToAmr(Object msg, java.io.File silk) {
        try {
            // ① VoiceInfo
            Object vi = null;
            try {
                Class<?> vx = Class.forName("ou5.x", false, Features.loader);
                for (java.lang.reflect.Method m : vx.getDeclaredMethods()) {
                    if (!m.getName().equals("a") || m.getParameterCount() != 2) continue;
                    if (!m.getParameterTypes()[0].getName().equals("com.tencent.mm.storage.e9")) continue;
                    m.setAccessible(true);
                    vi = m.invoke(null, msg, null);
                    break;
                }
            } catch (Throwable t) {
                Log.d(T + "decode: VoiceInfo 失败 " + t);
            }
            if (vi == null) return null;

            // ② 读 SILK 字节（去掉微信的 0x02 头之后要补回去？先原样传，微信自己认）
            byte[] data = readAll(silk);
            Object enc = fieldDeep(msg, "field_imgPath");
            String name = enc == null ? silk.getName() : String.valueOf(enc);

            // ③ 调 writeVoiceFile
            Class<?> vl = Class.forName("v61.d1", false, Features.loader);
            Object inst = null;
            try {
                java.lang.reflect.Constructor<?> ctor = vl.getDeclaredConstructor();
                ctor.setAccessible(true);
                inst = ctor.newInstance();
            } catch (Throwable ignored) {
                // 可能静态
            }
            java.util.Set<String> beforeSet = listVoiceFiles();   // ★ 解码前快照
            boolean ok = false;
            for (java.lang.reflect.Method m : vl.getDeclaredMethods()) {
                if (!m.getName().equals("y")) continue;
                if (m.getReturnType() != boolean.class) continue;
                if (m.getParameterCount() != 4) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (!ps[3].isArray() && ps[3] != byte.class) continue;
                m.setAccessible(true);
                Object r = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        ? m.invoke(null, vi, name, "amr", data)
                        : m.invoke(inst, vi, name, "amr", data);
                ok = r instanceof Boolean && (Boolean) r;
                Log.d(T + "writeVoiceFile(" + ps[3].getSimpleName() + ")=" + ok);
                break;
            }
            if (!ok) return null;

            // ④ 找刚写出来的 AMR：前后清单对比，多出来的就是 ✓（比 mtime 可靠）
            java.util.Set<String> afterSet = listVoiceFiles();
            afterSet.removeAll(beforeSet);
            Log.d(T + "解码新增文件 " + afterSet.size() + " 个: " + afterSet);
            for (String p : afterSet) {
                java.io.File f = new java.io.File(p);
                if (f.isFile() && f.length() > 200) return f;
            }
            return newestVoiceSince(System.currentTimeMillis() - 5000);
        } catch (Throwable t) {
            Log.d(T + "decodeSilkToAmr fail: " + t);
            return null;
        }
    }

    private static byte[] readAll(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = is.read(b, n, b.length - n)) > 0) n += r;
            return b;
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    /** 列出 voice2/voice3/voice 下所有文件路径（用于前后对比）。 */
    private static java.util.Set<String> listVoiceFiles() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            java.io.File base = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            java.io.File[] tops = base.listFiles();
            if (tops == null) return out;
            for (java.io.File hash : tops) {
                if (hash == null || !hash.isDirectory()) continue;
                for (String sub : new String[]{"voice2", "voice3", "voice"}) {
                    java.io.File vd = new java.io.File(hash, sub);
                    if (!vd.isDirectory()) continue;
                    java.io.File[] buckets = vd.listFiles();
                    if (buckets == null) continue;
                    for (java.io.File b : buckets) {
                        if (b == null) continue;
                        java.io.File[] fs = b.isDirectory() ? b.listFiles() : new java.io.File[]{b};
                        if (fs == null) continue;
                        for (java.io.File f : fs) {
                            if (f != null && f.isFile()) out.add(f.getAbsolutePath());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }

    /** 找某个时间点之后被修改的最新语音文件。 */
    private static java.io.File newestVoiceSince(long sinceMs) {
        try {
            java.io.File base = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            java.io.File[] tops = base.listFiles();
            if (tops == null) return null;
            java.io.File best = null;
            long bestT = 0;
            for (java.io.File hash : tops) {
                if (hash == null || !hash.isDirectory()) continue;
                for (String sub : new String[]{"voice2", "voice3", "voice"}) {
                    java.io.File vd = new java.io.File(hash, sub);
                    if (!vd.isDirectory()) continue;
                    java.io.File[] buckets = vd.listFiles();
                    if (buckets == null) continue;
                    for (java.io.File b : buckets) {
                        if (b == null) continue;
                        java.io.File[] fs = b.isDirectory() ? b.listFiles() : new java.io.File[]{b};
                        if (fs == null) continue;
                        for (java.io.File f : fs) {
                            if (f == null || !f.isFile() || f.length() < 200) continue;
                            long t = f.lastModified();
                            if (t >= sinceMs && t > bestT) { bestT = t; best = f; }
                        }
                    }
                }
            }
            if (best != null) Log.d(T + "newestVoiceSince -> " + best.getAbsolutePath()
                    + " " + best.length() + "B");
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 复制并去掉微信在 SILK 前面的自定义头（实测首字节 0x02，后面紧跟 "#!SILK_V3"）。
     * 去掉后是标准 SILK 文件 ✓，可用 silk 解码器转 PCM/MP3 ✓
     */
    private static int copyStripSilkHeader(java.io.File in, java.io.File out) throws java.io.IOException {
        java.io.FileInputStream is = new java.io.FileInputStream(in);
        try {
            byte[] all = new byte[(int) Math.min(in.length(), 32 * 1024 * 1024)];
            int n = 0, r;
            while (n < all.length && (r = is.read(all, n, all.length - n)) > 0) n += r;
            int off = 0;
            // 找 "#!SILK" 的位置；前面若有 1~2 字节的自定义头就跳过
            for (int i = 0; i < Math.min(8, n - 6); i++) {
                if (all[i] == '#' && all[i + 1] == '!' && all[i + 2] == 'S'
                        && all[i + 3] == 'I' && all[i + 4] == 'L' && all[i + 5] == 'K') {
                    off = i;
                    break;
                }
            }
            java.io.FileOutputStream os = new java.io.FileOutputStream(out);
            try {
                os.write(all, off, n - off);
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            return off;
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
        }
    }

    private static void copyFile(java.io.File in, java.io.File out) throws java.io.IOException {
        java.io.FileInputStream is = new java.io.FileInputStream(in);
        java.io.FileOutputStream os = new java.io.FileOutputStream(out);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
            try { os.close(); } catch (Throwable ignored) { }
        }
    }

    // ── 导出落盘：公共目录写不进时的兜底 ──────────────────────────────────────
    //
    // 微信 targetSdk 34、且**没有** MANAGE_EXTERNAL_STORAGE（本机 dumpsys 只申请了
    // READ_EXTERNAL_STORAGE）⇒ Android 11+ 的 scoped storage 让
    // `new File(Environment.getExternalStorageDirectory(), "Download/…")` 写不进去。
    // 老代码没有兜底，导出会静默失败（实测 /sdcard/Download/乐信导出 这个目录根本不存在）。
    //
    // 现在分三层：
    //   ① 公共「下载/乐信导出」—— 老系统或有权限的设备，用户直接可见
    //   ② 微信自己的外部私有目录（Android/data/com.tencent.mm/files）—— 一定可写，
    //      内部工作文件（转 MP3 的输入、导出的原文件）走这里
    //   ③ 微信私有 files 目录 —— ② 都拿不到时保命
    // 真要给用户看的那一份，再用 MediaStore 补一份进公共下载目录（Android 10+ 免权限）。

    private static java.io.File exportDir() {
        try {
            java.io.File pub = new java.io.File(android.os.Environment.getExternalStorageDirectory(),
                    "Download/乐信导出");
            if (writable(pub)) return pub;
        } catch (Throwable ignored) {
            // 落到下一层
        }
        try {
            Context ctx = Features.app;
            java.io.File ext = ctx == null ? null : ctx.getExternalFilesDir(null);
            java.io.File dir = new java.io.File(ext != null ? ext : ctx.getFilesDir(), "乐信导出");
            if (writable(dir)) return dir;
        } catch (Throwable ignored) {
            // 落到下一层
        }
        java.io.File base = Features.app == null
                ? new java.io.File("/data") : Features.app.getFilesDir();
        java.io.File tmp = new java.io.File(base, "乐信导出");
        tmp.mkdirs();
        return tmp;
    }

    /** 目录建得出 + 真能写一个探针文件，才算可写（Android 11+ 上 mkdirs 成功、写却 EACCES）。 */
    private static boolean writable(java.io.File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            java.io.File probe = new java.io.File(dir, ".w" + System.nanoTime());
            boolean ok = probe.createNewFile();
            if (ok) probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把一份文件放进公共「下载/乐信导出」：Android 10+ 走 MediaStore，不需要任何权限。
     * 成功返回用户可见的相对路径；失败返回 null（调用方退回 exportDir 的绝对路径）。
     */
    private static String publishPublic(java.io.File src, String displayName) {
        if (src == null || !src.isFile() || Features.app == null) return null;
        if (android.os.Build.VERSION.SDK_INT < 29) return null;      // MediaStore 下载集合是 29+
        android.net.Uri uri = null;
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeOf(displayName));
            v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/乐信导出");
            v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
            uri = Features.app.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            java.io.OutputStream os = Features.app.getContentResolver().openOutputStream(uri);
            if (os == null) throw new java.io.IOException("no output stream");
            try {
                java.io.FileInputStream is = new java.io.FileInputStream(src);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    try { is.close(); } catch (Throwable ignored) { }
                }
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            android.content.ContentValues done = new android.content.ContentValues();
            done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
            Features.app.getContentResolver().update(uri, done, null, null);
            return "下载/乐信导出/" + displayName;
        } catch (Throwable t) {
            Log.d(T + "publish failed: " + t);
            try {
                if (uri != null) Features.app.getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) { }
            return null;
        }
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".amr") || n.endsWith(".silk")) return "audio/*";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    /**
     * Toast 必须在主线程（后台线程调 Toast 不显示甚至会抛 ✗ —— 语音点击"毫无反应"就是这个 ✗）。
     */
    static void toastPublic(String tip) { toast(tip); }

    /**
     * ★ 选人 → 发语音：微信没有语音的原生转发 ✗，所以用**它自己的会话选择器**选目标，
     * 再按 WeKit 配方发送（voiceNameGen(目标会话) 决定发到哪儿 ✓）。
     */
    private static void pickConversationThenVoice(final Object msg) {
        try {
            android.content.Context ctx = lastView == null ? null : lastView.getContext();
            if (ctx == null) { toast("拿不到界面上下文"); return; }
            if (pendingVoice != null) { toast("上一次选择还没完成"); return; }
            pendingVoice = msg;
            android.content.Intent it = new android.content.Intent();
            it.setClassName(ctx, "com.tencent.mm.ui.transmit.SelectConversationUI");
            it.putExtra("Select_Conv_Type", 3);
            it.putExtra("select_is_ret", true);
            it.putExtra("select_fmt_callback", true);
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).startActivityForResult(it, REQ_VOICE);   // ★ 必须 forResult
                Log.d(T + "startActivityForResult(REQ_VOICE=" + REQ_VOICE + ")");
            } else {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                Log.d(T + "非 Activity 上下文，无法接收结果（可能会退回导出）");
            }
            Log.d(T + "已拉起会话选择器");
        } catch (Throwable t) {
            Log.d(T + "拉起选择器失败: " + t);
            toast("打开会话选择器失败");
            pendingVoice = null;
        }
    }

    /** 选中的会话（语音待发）。 */
    private static volatile Object pendingVoice;
    private static final int REQ_VOICE = 0x4C45;   // 语音转发选人的 requestCode

    private static void toast(final String tip) {
        Log.d(T + "toast: " + tip);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.Context ctx = null;
                    if (lastView != null) ctx = lastView.getContext();
                    if (ctx == null) {
                        ctx = android.app.AndroidAppHelper.currentApplication();
                    }
                    if (ctx != null) {
                        android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        };
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable ignored) {
            // 连 Handler 都拿不到就算了
        }
    }

    /** 在九宫格里按文案找"格子"（返回可点击的那层）。 */
    /** 朋友圈菜单判定：弹窗上下文或被长按的 View 只要有一个在朋友圈界面就算。 */
    private static boolean isSnsMenu(android.content.Context ctx) {
        try {
            if (ctx != null && SnsForward.isSnsContext(ctx)) return true;
        } catch (Throwable ignored) {
            // 继续看锚点
        }
        try {
            if (lastView != null && SnsForward.isSnsContext(lastView.getContext())) return true;
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 找这一版菜单里"官方转发"对应的格子：聊天是「转发」，图片面板是「发送给朋友」。 */
    private static android.view.View findForwardCell(android.view.ViewGroup grid) {
        for (String s : new String[]{"转发", "发送给朋友", "分享"}) {
            android.view.View v = findCellByLabel(grid, s);
            if (v != null) return v;
        }
        return null;
    }

    private static android.view.View findCellByLabel(android.view.ViewGroup grid, String label) {
        try {
            java.util.List<android.widget.TextView> hits = new java.util.ArrayList<>();
            collectMenuLabels(grid, hits, 0);
            for (android.widget.TextView tv : hits) {
                if (!label.equals(tv.getText() == null ? "" : tv.getText().toString().trim())) continue;
                android.view.View v = tv;
                for (int i = 0; i < 3; i++) {                 // 往上找 1~3 层，找可点的那个
                    if (v.isClickable()) return v;
                    if (!(v.getParent() instanceof android.view.View)) break;
                    v = (android.view.View) v.getParent();
                }
                return tv;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static int dp(android.content.Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 递归收集含"聊天菜单文案"的 TextView。 */
    private static void collectMenuLabels(android.view.View v, java.util.List<android.widget.TextView> out, int depth) {
        if (v == null || depth > 8) return;
        if (v instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) v).getText();
            if (cs != null) {
                String s = cs.toString().trim();
                for (String label : MENU_LABELS) {
                    // 包含匹配：微信文案常带后缀（「保存图片到手机」「识别图中二维码」等），精确相等会漏
                    if (s.equals(label) || s.contains(label)) {
                        out.add((android.widget.TextView) v);
                        break;
                    }
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectMenuLabels(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** 找出"包含全部命中格子的最近一层容器"。 */
    private static android.view.ViewGroup commonParent(java.util.List<android.widget.TextView> hits) {
        android.view.ViewGroup best = null;
        try {
            android.view.View first = hits.get(0);
            for (android.view.ViewParent vp = first.getParent(); vp != null; vp = vp.getParent()) {
                if (!(vp instanceof android.view.ViewGroup)) continue;
                android.view.ViewGroup g = (android.view.ViewGroup) vp;
                int inside = 0;
                for (android.widget.TextView tv : hits) {
                    if (isDescendant(tv, g)) inside++;
                }
                if (inside >= Math.min(3, hits.size())) { best = g; break; }   // ★ 最近的一层就停
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return best;
    }

    private static boolean isDescendant(android.view.View v, android.view.ViewGroup g) {
        for (android.view.ViewParent vp = v.getParent(); vp != null; vp = vp.getParent()) {
            if (vp == g) return true;
        }
        return false;
    }

    /** 调用栈里有没有"弹窗构造器"（学到的 knownBuilders 之一）。 */
    private static boolean calledFromBuilder() {
        try {
            if (knownBuilders.isEmpty()) return false;
            StackTraceElement[] st = new Throwable().getStackTrace();
            for (StackTraceElement e : st) {
                if (knownBuilders.contains(e.getClassName())) return true;
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    /** 这个 View 或它的 1~2 层父容器里，有没有含我们标签的文字。 */
    private static boolean hasOurLabel(android.view.View v, int depth) {
        try {
            if (v instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) v).getText();
                if (cs != null && cs.toString().contains(ITEM_LABEL)) return true;
            }
            if (depth >= 2) return false;
            if (v.getParent() instanceof android.view.View) {
                return hasOurLabel((android.view.View) v.getParent(), depth + 1);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return false;
    }

    private static int hookMenuSelect() {
        int hits = 0;
        for (final Class<?> owner : Anchors.all(Anchors.CHAT_MENU_SELECT)) {
            Method target = null;
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 || m.getParameterCount() > 6) continue;
                boolean has = false;
                for (Class<?> pc : m.getParameterTypes()) {
                    if (MenuItem.class.equals(pc)) { has = true; break; }
                }
                if (!has) continue;
                target = m;
                break;
            }
            if (target == null) continue;
            try {
                target.setAccessible(true);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object o = p.args[0];
                            if (!(o instanceof MenuItem)) return;
                            if (((MenuItem) o).getItemId() != ITEM_ID) return;
                            p.setResult(null);                  // 吃掉，微信别管
                            onOurItem(p.thisObject);
                        } catch (Throwable ignored) {
                            // 别影响微信自己的点击
                        }
                    }
                });
                hits++;
            } catch (Throwable ignored) {
                // 下一个类
            }
        }
        return hits;
    }

    /** 从"当前弹菜单的那个组件"里把消息对象挖出来（字段类型 == 消息类）。 */
    /**
     * 取"被长按的那条消息"。
     * ★ 完全不依赖 Anchors.MSG（那个锚点在转发场景下解析不出来，会把整段逻辑短路掉 ✗）。
     * 依据实测：锚点 View 的 tag（com.tencent.mm.ui.chatting.viewitems.ap）里
     *   字段 a  = 消息对象
     *   字段 S  = 显示文本（CharSequence）
     */
    private static Object findMessage(Object owner) {
        try {
            Object tag = lastView != null ? lastView.getTag() : null;
            if (tag != null) {
                lastTag = tag;
                // ★ 标签对象本身可能是空壳（实测 hn5.p0 字段全空 ✗），
                //   真正的消息在它内部的某个对象里 —— 递归找"带 field_type 字段的对象"。
                Object deep = findMsgDeep(tag, 0);
                if (deep != null) {
                    Log.d(T + "msg found deep: " + deep.getClass().getName()
                            + " (depth<=3)");
                    return deep;
                }
                Log.d(T + "tag.a 取不到，tag 类=" + tag.getClass().getName()
                        + " 字段=" + fieldNames(tag.getClass()));
            }
            // 退路：锚点 View 自己 / owner 的字段里找
            Class<?> msgClass = Anchors.cls(Anchors.MSG);
            Object fromView = findIn(lastView, msgClass);
            if (fromView != null) return fromView;
            if (owner != null) {
                Object fromOwner = findIn(owner, msgClass);
                if (fromOwner != null) return fromOwner;
            }
        } catch (Throwable ignored) {
            // 找不到就返回 null，外层会提示
        }
        return null;
    }

    /**
     * 递归找"消息对象"：判据是**有 field_type 字段**（沿父类找）。
     * 实测 tag 自己（viewitems.ap）和它的 a 字段都是空壳 ✗，消息藏在更深一层 ✓。
     */
    private static Object findMsgDeep(Object o, int depth) {
        if (o == null || depth > 3) return null;
        try {
            String cn = o.getClass().getName();
            if (cn.startsWith("java.") || cn.startsWith("android.") || o instanceof CharSequence
                    || o instanceof java.util.Collection || o instanceof android.os.Bundle) return null;
            if (hasFieldDeep(o.getClass(), "field_type")) return o;      // 命中 ✓
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft.isArray() || ft == String.class) continue;
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null || v == o) continue;
                        Object r = findMsgDeep(v, depth + 1);
                        if (r != null) return r;
                    } catch (Throwable ignored) {
                        // 下一个
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    private static boolean hasFieldDeep(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name)) return true;
            }
        }
        return false;
    }

    /** 沿父类找字段（tag 的 a 可能声明在祖先类里）。 */
    private static Object fieldDeep(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // 往父类找
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String fieldNames(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            }
        }
        return sb.toString();
    }

    /** 阶段 1：解除多选上限 —— 多选组件里那些 (boolean):void 的开关，一律当"允许"处理。 */
    private static int hookQuickSelect() {
        int hits = 0;
        for (Class<?> c : Anchors.all(Anchors.CHAT_QUICK_SELECT)) {
            for (final Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 1 || p[0] != boolean.class || m.getReturnType() != void.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Prefs.onDefault(Prefs.K_FWD_SELECT)) return;
                            p.args[0] = Boolean.TRUE;
                        }
                    });
                    hits++;
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        return hits;
    }

    /** 点我们自己那一项之后做什么（先证明"菜单项 + 消息定位"这条链路通了）。 */
    private static void onOurItem(Object owner) {
        try {
            Object msg = findMessage(owner);
            // ★ 按"值形状"认字段（字段名每版都变，值不会）：
            //   type    = 落在微信消息类型集合里的 int
            //   content = 最长的那个 String
            // ★ 照抄 WAuxiliary 的 MsgInfoBean：字段名是 field_xxx，且必须**沿父类查找**
            //   （消息类 hn5.p0 自身无字段，全在父类 am5.d 里 ✗ —— 之前就栽在这）
            String type = null, content = null, talker = null;
            Object ty = fieldDeep(msg, "field_type");
            Object ct = fieldDeep(msg, "field_content");
            Object tk = fieldDeep(msg, "field_talker");
            Object mid = fieldDeep(msg, "field_msgId");
            Object isSend = fieldDeep(msg, "field_isSend");
            if (ty != null) type = String.valueOf(ty);
            if (ct != null) content = String.valueOf(ct);
            if (tk != null) talker = String.valueOf(tk);
            lastTalker = talker;
            Log.d(T + "msg by name: type=" + type + " talker=" + talker
                    + " msgId=" + mid + " isSend=" + isSend
                    + " len=" + (content == null ? -1 : content.length()));
            StringBuilder dump = new StringBuilder();
            int[] known = {1, 3, 34, 43, 47, 48, 49, 50, 42, 62, 44, 45, 10000, 10002};
            if (msg != null && (type == null || content == null)) {
                for (Class<?> c = msg.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            Object v = f.get(msg);
                            Class<?> ft = f.getType();
                            dump.append(f.getName()).append('=');
                            if (ft == int.class && v != null) {
                                dump.append(v).append(' ');
                                for (int k : known) {
                                    if (((Integer) v) == k && type == null) { type = String.valueOf(k); }
                                }
                            } else if (ft == String.class && v != null) {
                                String s = (String) v;
                                dump.append('"').append(s.length() > 24 ? s.substring(0, 24) + "…" : s).append('"').append(' ');
                                if (content == null || s.length() > content.length()) content = s;
                            } else if (ft == long.class && v != null) {
                                dump.append(v).append(' ');
                            } else {
                                dump.append(ft.getSimpleName()).append(' ');
                            }
                        } catch (Throwable ignored) {
                            // 下一个
                        }
                    }
                }
                Log.d(T + "msg fields(" + msg.getClass().getName() + "): " + dump);
            }
            Log.d(T + "onOurItem: msg=" + (msg == null ? "null" : msg.getClass().getName())
                    + " type=" + type + " len=" + (content == null ? -1 : content.length()));
            Log.d(T + "our item clicked: msg=" + (msg != null)
                    + " type=" + type + " content=" + content);
            if ((content == null || content.isEmpty()) && lastTag != null) {
                Object s = fieldDeep(lastTag, "S");           // tag 里的显示文本（CharSequence）
                if (s != null && !s.toString().isEmpty()) {
                    content = s.toString();
                    if (type == null) type = "1";
                    Log.d(T + "content from tag.S, len=" + content.length());
                }
            }
            Context ctx = ctxOf(lastView != null ? lastView : owner);
            if (ctx == null && lastView != null) ctx = lastView.getContext();
            Log.d(T + "onOurItem ctx=" + (ctx == null ? "null" : ctx.getClass().getName()));
            String tip;
            if (msg == null) {
                tip = "没取到这条消息（换定位方式）";
            } else {
                tip = act(msg, type);
            }
            if (ctx != null) {
                android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // 静默
        }
    }

    /** 在对象（或其 View 的 tag）里找消息对象。 */
    private static Object findIn(Object o, Class<?> msgClass) {
        if (o == null || msgClass == null) return null;
        try {
            if (msgClass.isInstance(o)) return o;
            if (o instanceof View) {
                Object tag = ((View) o).getTag();
                if (msgClass.isInstance(tag)) return tag;
            }
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != msgClass) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v != null) return v;
                }
            }
        } catch (Throwable ignored) {
            // 找不到就算了
        }
        return null;
    }

    /** 点我们那一项之后干什么：文本进剪贴板，媒体另存到 下载/乐信导出/。零联网、零伪造。 */
    private static String act(Object msg, String type) {
        try {
            String content = readField(msg, "field_content");
            String path = readField(msg, "field_imgPath");
            if (path == null) path = readField(msg, "field_fileFullPath");
            if (path != null && !path.isEmpty() && new java.io.File(path).exists()) {
                java.io.File dir = exportDir();
                String name = new java.io.File(path).getName();
                java.io.File dst = new java.io.File(dir, System.currentTimeMillis() + "_" + name);
                java.io.InputStream in = new java.io.FileInputStream(path);
                java.io.OutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
                String shownAct = publishPublic(dst, dst.getName());
                return "已导出到 " + (shownAct != null ? shownAct : dst.getAbsolutePath());
            }
            if (content != null && !content.isEmpty()) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        Features.app.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lexin", content));
                return "已复制到剪贴板";
            }
            return "这一类型还不能导出（类型 " + type + "）";
        } catch (Throwable t) {
            return "处理失败：" + t.getClass().getSimpleName();
        }
    }

    private static Context ctxOf(Object o) {
        try {
            if (o instanceof Context) return (Context) o;
            if (o instanceof View) return ((View) o).getContext();
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!View.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof View) return ((View) v).getContext();
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return null;
    }

    /** 反射读消息上的 field_xxx 字段（微信这些字段名与数据库列名一致，跨版本稳）。 */
    private static String readField(Object msg, String field) {
        if (msg == null) return null;
        Class<?> c = msg.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(msg);
                return v == null ? null : String.valueOf(v);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 连父类一起，把所有声明方法（含包私有/私有）都列出来。 */
    private static java.util.List<Method> allMethods(Class<?> c) {
        java.util.List<Method> out = new java.util.ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) out.add(m);
        }
        // 接口上的默认方法也带上
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?> itf : k.getInterfaces()) {
                for (Method m : itf.getDeclaredMethods()) out.add(m);
            }
        }
        return out;
    }

    private static int indexOf(Method m, Class<?> want) {
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (want.isAssignableFrom(p[i]) || p[i].isAssignableFrom(want)) return i;
        }
        return -1;
    }

    private ForwardPlus() {}
}
