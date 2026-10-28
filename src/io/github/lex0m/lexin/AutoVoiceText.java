package io.github.lex0m.lexin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 自动语音转文字（v1.3）。
 *
 * 思路照抄 CrackerCat/wcx 的 AutoSpeechToText：收到语音消息后，直接调微信官方
 * 「转文字」组件（TransformChattingComponent）——这是微信自己长按语音 → 转文字走的那条
 * 官方接口，不接第三方 ASR、不碰 SILK 解码，风险最低。
 *
 * 触发点 = 消息 View 绑定（onBindView）后识别新进语音消息，调转文字组件把气泡就地转成
 * 文字。同一消息进程内只转一次（防滚动/复用重复触发）。
 *
 * 坐标（wcx 的 DexKit 字符串特征 → 本项目 anchors 版本表）：
 *   transform_component  com.tencent.mm.ui.chatting.component 下，日志 "MicroMsg.TransformComponent"/"[onChattingPause]"
 *   chat_bind_mvvm       日志 "MicroMsg.MvvmChattingItem"/"[onBindView]"
 *   chatting_context     日志 "MicroMsg.ChattingContext"/"[notifyDataSetChange]"
 *   chatting_adapter_v3  日志 "MicroMsg.ChattingDataAdapterV3"/"[handleMsgChange] isLockNotify:"
 *   api_manager          com.tencent.mm.ui.chatting.manager 下，日志 "[get] "/" is not a interface!"
 */
final class AutoVoiceText {

    private static final String T = "svc-vtxt: ";

    /** 微信消息类型：34 = 语音。 */
    private static final int TYPE_VOICE = 34;

    /** 进程内防重复：msgId -> true，转过的不再转（滚动复用视图会重复触发 onBindView）。 */
    private static final Map<Long, Boolean> processed =
            Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) {
                    return size() > 512;
                }
            });

    private static volatile Class<?> cMsgInfo;
    private static volatile Class<?> cTransformComponent;
    private static volatile Class<?> cChattingContext;
    private static volatile Class<?> cChattingAdapter;
    private static volatile Class<?> cApiManager;

    /** 反射缓存：onBind 是热路径（每条消息绑定都触发），getDeclaredField/Method 不能每次现找。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> fieldByNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> fieldByTypeCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Method getItemMethod;
    private static volatile Method transformMethod;

    static void install() {
        cMsgInfo = Anchors.cls(Anchors.MSG);
        cTransformComponent = Anchors.cls(Anchors.TRANSFORM_COMPONENT);
        cChattingContext = Anchors.cls(Anchors.CHATTING_CONTEXT);
        cChattingAdapter = Anchors.cls(Anchors.CHATTING_ADAPTER_V3);
        cApiManager = Anchors.cls(Anchors.API_MANAGER);

        boolean ok = false;
        if (cMsgInfo != null && cTransformComponent != null && cChattingContext != null
                && cChattingAdapter != null && cApiManager != null) {
            ok = hookBind();
        }
        Status.set("自动语音转文字", ok);
        Status.note("自动语音转文字", ok
                ? "收到语音自动调微信官方转文字"
                : "当前微信版本未适配（锚点缺失）");
        Log.d(T + "install ok=" + ok);
    }

    private static boolean hookBind() {
        // 版本表里 chat_bind_mvvm 是 "类#方法"（emitMethod 输出）
        String entry = Anchors.entryWithMethod(Anchors.CHAT_BIND_MVVM);
        Class<?> cls = Anchors.cls(Anchors.CHAT_BIND_MVVM);
        String mName = Anchors.methodNameOf(entry);
        if (cls == null || mName == null || mName.isEmpty()) {
            Log.d(T + "bind entry missing: " + entry);
            return false;
        }
        int hooked = 0;
        for (final Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(mName)) continue;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            onBind(p);
                        } catch (Throwable ignored) {
                            // 转文字失败绝不打扰宿主
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 下一个重载
            }
        }
        Log.d(T + "bind hooked=" + hooked + " @" + cls.getName() + "#" + mName);
        return hooked > 0;
    }

    private static void onBind(XC_MethodHook.MethodHookParam p) {
        if (!Prefs.onDefault(Prefs.K_VOICE_TEXT)) return;

        // wcx：args[0]=holder（持有 View），args[2]=msgId(int)
        Object msgIdObj = p.args != null && p.args.length > 2 ? p.args[2] : null;
        if (!(msgIdObj instanceof Number)) return;
        int msgId = ((Number) msgIdObj).intValue();

        // 从 thisObject 拿聊天数据适配器 → getItem(msgId) → MsgInfo
        Object adapter = fieldOfType(p.thisObject, cChattingAdapter);
        if (adapter == null) return;
        Object msgInfo = getItem(adapter, msgId);
        if (msgInfo == null) return;

        // 只处理语音消息
        if (intField(msgInfo, "field_type") != TYPE_VOICE) return;

        // 防重复
        Long id = longField(msgInfo, "field_msgSvrId");
        if (id == null || id == 0L) id = longField(msgInfo, "field_msgId");
        if (id == null || id == 0L) return;
        if (processed.put(id, Boolean.TRUE) != null) return;   // 已转

        // 从 thisObject 拿聊天上下文 → 拿 apiManager → getApi(转文字组件接口) → 组件实例
        Object ctx = fieldOfType(p.thisObject, cChattingContext);
        if (ctx == null) {
            Log.d(T + "chatting context missing");
            return;
        }
        Object apiMan = fieldOfType(ctx, cApiManager);
        if (apiMan == null) {
            Log.d(T + "api manager missing");
            return;
        }
        Object component = getApi(apiMan, cTransformComponent);
        if (component == null) {
            Log.d(T + "transform component missing");
            return;
        }

        // 转文字核心：组件上找 (MsgInfo, boolean, int, int) -> void，调 (msgInfo, false, -1, 0)
        Method transform = findTransform(component, cMsgInfo);
        if (transform == null) {
            Log.d(T + "transform method missing");
            return;
        }
        try {
            transform.invoke(component, msgInfo, Boolean.FALSE, Integer.valueOf(-1), Integer.valueOf(0));
            Log.d(T + "transformed msgId=" + id);
        } catch (Throwable t) {
            // 微信有时会抛 NPE（getImgPath must not be null），不影响转文字本身，忽略
            Log.d(T + "transform error(ignored): " + t.getClass().getSimpleName());
        }
    }

    // ── 反射助手 ────────────────────────────────────────────────────────────

    /** 沿父类链找「类型等于/兼容 type」的字段（缓存 Field，热路径零 getDeclaredFields）。 */
    private static Object fieldOfType(Object obj, Class<?> type) {
        if (obj == null || type == null) return null;
        String key = obj.getClass().getName() + "#T#" + type.getName();
        Field f = fieldByTypeCache.get(key);
        if (f == null) {
            synchronized (AutoVoiceText.class) {
                f = fieldByTypeCache.get(key);
                if (f == null) {
                    for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            for (Field ff : c.getDeclaredFields()) {
                                if (ff.getType() == type || type.isAssignableFrom(ff.getType())) {
                                    ff.setAccessible(true);
                                    f = ff;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 继续往上找
                        }
                        if (f != null) break;
                    }
                    if (f != null) fieldByTypeCache.put(key, f);
                }
            }
        }
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** adapter.getItem(int)。缓存 Method，避免热路径每次 getDeclaredMethods。 */
    private static Object getItem(Object adapter, int pos) {
        if (adapter == null) return null;
        Method m = getItemMethod;
        if (m == null) {
            synchronized (AutoVoiceText.class) {
                m = getItemMethod;
                if (m == null) {
                    for (Method mm : adapter.getClass().getDeclaredMethods()) {
                        if ("getItem".equals(mm.getName())
                                && mm.getParameterTypes().length == 1
                                && mm.getParameterTypes()[0] == int.class) {
                            mm.setAccessible(true);
                            getItemMethod = mm;
                            m = mm;
                            break;
                        }
                    }
                }
            }
        }
        if (m == null) return null;
        try {
            return m.invoke(adapter, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** apiManager.getApi(接口) —— 优先按方法名 getApi 找，找不到再遍历 (Class)->Object。 */
    private static Object getApi(Object apiMan, Class<?> transformCls) {
        if (apiMan == null || transformCls == null) return null;
        Class<?> iface = transformCls.isInterface() ? transformCls
                : (transformCls.getInterfaces().length > 0 ? transformCls.getInterfaces()[0] : transformCls);

        // 先按名字 getApi
        for (Class<?> c = apiMan.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"getApi".equals(m.getName()) && !"get".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != Class.class) continue;
                m.setAccessible(true);
                try {
                    Object r = m.invoke(apiMan, iface);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        // 兜底：任何 (Class)->非void 方法
        for (Class<?> c = apiMan.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != Class.class) continue;
                if (m.getReturnType() == void.class) continue;
                m.setAccessible(true);
                try {
                    Object r = m.invoke(apiMan, iface);
                    if (r != null) return r;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        return null;
    }

    /** 组件上找 (MsgInfo, boolean, int, int) -> void（缓存，只解析一次）。 */
    private static Method findTransform(Object component, Class<?> msgInfoCls) {
        if (component == null || msgInfoCls == null) return null;
        Method m = transformMethod;
        if (m != null) return m;
        synchronized (AutoVoiceText.class) {
            m = transformMethod;
            if (m == null) {
                for (Class<?> c = component.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Method mm : c.getDeclaredMethods()) {
                        Class<?>[] pt = mm.getParameterTypes();
                        if (pt.length != 4) continue;
                        if (mm.getReturnType() != void.class) continue;
                        if (!sameOrAssignable(pt[0], msgInfoCls)) continue;
                        if (pt[1] != boolean.class) continue;
                        if (pt[2] != int.class) continue;
                        if (pt[3] != int.class) continue;
                        mm.setAccessible(true);
                        transformMethod = mm;
                        return mm;
                    }
                }
            }
        }
        return m;
    }

    private static boolean sameOrAssignable(Class<?> a, Class<?> b) {
        return a == b || a.isAssignableFrom(b) || b.isAssignableFrom(a);
    }

    /** 读 int 字段（沿父类链）。 */
    private static int intField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private static long longField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private static Object field(Object o, String name) {
        if (o == null) return null;
        String key = o.getClass().getName() + "#N#" + name;
        Field f = fieldByNameCache.get(key);
        if (f == null) {
            synchronized (AutoVoiceText.class) {
                f = fieldByNameCache.get(key);
                if (f == null) {
                    for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            Field ff = c.getDeclaredField(name);
                            ff.setAccessible(true);
                            f = ff;
                            break;
                        } catch (NoSuchFieldException ignored) {
                            // 父类找
                        } catch (Throwable ignored) {
                            return null;
                        }
                    }
                    if (f != null) fieldByNameCache.put(key, f);
                }
            }
        }
        if (f == null) return null;
        try {
            return f.get(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private AutoVoiceText() {}
}
