package io.github.lex0m.lexin;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 运动步数修改（改写思路照抄 XposedWechatHelper 的 StepHook，但把「乘一个固定倍数」换成三种模式）。
 *
 * **走的是传感器层，不碰微信任何代码**：
 *   android.hardware.SystemSensorManager$SensorEventQueue.dispatchSensorEvent(handle, values, ...)
 * 这是所有 App（包括微信）拿到传感器数据的必经之路。我们在数据分发出去之前改掉 values[0]，
 * 微信自己那套计数逻辑原样运行，它拿到的就是"传感器说今天走了这么多步"。
 * 好处：不 hook 微信的任何类、不碰数据库/网络，微信侧看不到任何被改过的痕迹。
 *
 * 怎么确认哪个 handle 是计步器：队列里有个 SparseArray<SensorEvent> mSensorsEvents，
 * 拿 handle 一查就知道是哪个传感器（SensorEvent.sensor 是 public 字段），
 * 只改 TYPE_STEP_COUNTER（累计步数），别的传感器一律不碰。
 *
 * 三种模式（都换算成"额外加多少步"，并受上限约束，避免数字离谱）：
 *   1) 倍率     —— 按真实步数的 k 倍走（额外量 = 真实步数 × (k-1)）
 *   2) 自动增加 —— 每小时自动加 N 步，随一天时间平滑增长（默认 200 步/小时）
 *   3) 指定目标 —— 指定今天要走到的步数，随一天时间平滑填充
 */
final class Steps {

    private static final String T = "svc-step: ";

    static final String K_ON = "st_on";
    static final String K_MODE = "st_mode";        // 1=倍率 2=自动增加 3=指定目标
    static final String K_MUL = "st_mul";          // 倍率 ×10（默认 15 = 1.5 倍）
    static final String K_RATE = "st_rate";        // 每小时增加步数
    static final String K_TARGET = "st_target";    // 今日目标步数
    static final String K_CAP = "st_cap";          // 额外步数上限

    private static final int DEFAULT_MUL10 = 15;
    private static final int DEFAULT_RATE = 200;
    private static final int DEFAULT_TARGET = 12000;
    private static final int DEFAULT_CAP = 20000;

    private static volatile Field sensorsEvents;
    private static volatile Field eventSensor;
    private static volatile int hooked = 0;

    static void install() {
        // 只装主进程：微信运动的数据在主进程，:push 之类的进程再改一遍会变成双倍（两份注入叠加）。
        if (!isMainProcess()) {
            Log.d(T + "skip: not main process");
            return;
        }
        int wxLevel = hookWeChatSteps();     // 首选：微信自己算出来的"今天步数"（各家 provider 它自己处理了）
        int sensorLevel = hookSensorLayer(); // 兜底：框架传感器层
        boolean any = wxLevel + sensorLevel > 0;
        hooked = any ? 1 : 0;
        Status.set("运动步数", any);
        Status.note("运动步数", any
                ? label() + "（微信侧 " + wxLevel + " 处 · 传感器侧 " + sensorLevel + " 处）"
                : "当前微信/机型未适配");
        Log.d(T + "armed wx=" + wxLevel + " sensor=" + sensorLevel);
    }

    /**
     * 微信自己的步数结果。
     *
     * 这一步是关键：**各家手机的步数来源不一样** —— 小米读 MIUI 的 content provider、
     * 别的品牌多半读系统计步器，微信内部自己做了适配。我们直接改它算出来的"今天步数"，
     * 就与品牌/机型无关了（换手机也不用改规则）。
     *
     * 两条落点都挂上，谁在跑就改谁：
     *   ① SportUtil 里那些"无参返回 long/int"的取值方法（只改量级像步数的，别的不碰）
     *   ② MMSportStepDetector / PushSportStepDetector 的 onSensorChanged
     */
    private static int hookWeChatSteps() {
        int hits = 0;
        hits += hookUtilGetters();
        hits += hookDetector(Anchors.SPORT_DETECTOR);
        hits += hookDetector(Anchors.SPORT_PUSH_DETECTOR);
        return hits;
    }

    /** 只改"看起来就是步数"的 getter：无参、返回 long/int、原值 ≥ 100（0/1 这种开关值一律放过）。 */
    private static int hookUtilGetters() {
        int hits = 0;
        for (final Class<?> c : Anchors.all(Anchors.SPORT_UTIL)) {
            for (final Method m : c.getDeclaredMethods()) {
                Class<?> rt = m.getReturnType();
                if (m.getParameterCount() != 0) continue;
                if (rt != long.class && rt != int.class) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(K_ON)) return;
                                Object r = p.getResult();
                                if (!(r instanceof Number)) return;
                                double real = ((Number) r).doubleValue();
                                // 只认"步数量级"：下限挡开关/状态值(0/1)，上限挡时间戳(1.7e12)之类的长整数。
                                // 之前只写了下限，把 c0#g 返回的时间戳也当成步数改了 —— 幸好在日志里抓到。
                                if (real < 100 || real > 300000) return;
                                // 再加一道：数值必须"自己会变"。步数会随时间变，而配置值/超时常量不会 ——
                                // 万一下个版本这个类里多出一个常量 long，也不至于被改坏。
                                if (!looksAlive(m.getName(), real)) return;
                                double faked = fromToday(real);
                                if (faked < 0 || faked == real) return;
                                utilActive = true;
                                if (rt == long.class) p.setResult((long) faked);
                                else p.setResult((int) faked);
                                logSample(real, faked, Storage.num(K_MODE, 2), c.getName() + "#" + m.getName());
                            } catch (Throwable ignored) {
                                // 有疑问就保持原值
                            }
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

    /** 听计步器的那两条路：直接改 SensorEvent 里的值。 */
    private static int hookDetector(String slot) {
        int hits = 0;
        for (Class<?> c : Anchors.all(slot)) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!"onSensorChanged".equals(m.getName()) || p.length != 1) continue;
                if (!"android.hardware.SensorEvent".equals(p[0].getName())) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                if (!Prefs.onDefault(K_ON) || utilActive) return;
                                SensorEvent e = (SensorEvent) p.args[0];
                                if (e == null || e.values == null || e.values.length < 1) return;
                                if (e.sensor == null || e.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
                                double raw = e.values[0];
                                double faked = fake(raw);
                                if (faked > 0 && faked != raw) {
                                    e.values[0] = (float) faked;
                                    logSample(raw, faked, Storage.num(K_MODE, 2), "onSensorChanged");
                                }
                            } catch (Throwable ignored) {
                                // 传感器回调里绝不抛异常
                            }
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

    /** 框架层兜底：所有 App 拿传感器数据都走这里。 */
    private static int hookSensorLayer() {
        try {
            Class<?> queue = Class.forName("android.hardware.SystemSensorManager$SensorEventQueue",
                    false, Features.loader);
            sensorsEvents = findField(queue, "mSensorsEvents");
            eventSensor = findField(SensorEvent.class, "sensor");
            if (sensorsEvents == null || eventSensor == null) return 0;
            sensorsEvents.setAccessible(true);
            XposedBridge.hookAllMethods(queue, "dispatchSensorEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Prefs.onDefault(K_ON) || utilActive) return;
                        if (param.args.length < 2 || !(param.args[1] instanceof float[])) return;
                        int handle = Silent.asInt(param.args[0]);
                        float[] values = (float[]) param.args[1];
                        if (values.length < 1) return;
                        if (!isStepCounter(param.thisObject, handle)) return;
                        double raw = values[0];
                        double faked = fake(raw);
                        if (faked > 0 && faked != raw) {
                            values[0] = (float) faked;
                            logSample(raw, faked, Storage.num(K_MODE, 2), "dispatch");
                        }
                    } catch (Throwable ignored) {
                        // 传感器回调里绝不抛异常
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            Log.d(T + "framework hook failed: " + t.getClass().getSimpleName());
            return 0;
        }
    }

    /**
     * 微信层的换算：这里拿到的**就是微信自己算出来的今天步数**，所以三种模式都能给准值。
     *   1 倍率      ：今天 × k
     *   2 自动增加  ：今天 + 每小时 N 步 × 已过小时数
     *   3 直接指定  ：直接给目标值
     */
    private static double fromToday(double realToday) {
        int mode = Storage.num(K_MODE, 2);
        int cap = Math.max(1000, Storage.num(K_CAP, DEFAULT_CAP));
        double out;
        switch (mode) {
            case 1: {
                double k = Math.max(10, Storage.num(K_MUL, DEFAULT_MUL10)) / 10.0;
                out = realToday * k;
                break;
            }
            case 3: {
                out = Math.max(100, Storage.num(K_TARGET, DEFAULT_TARGET));
                break;
            }
            default: {
                int rate = Math.max(10, Storage.num(K_RATE, DEFAULT_RATE));
                out = realToday + rate * (minutesToday() / 60.0);
                break;
            }
        }
        if (out > cap) out = cap;
        return out;
    }

    /** handle -> SensorEvent，只有计步器才动。 */
    private static boolean isStepCounter(Object queue, int handle) {
        try {
            Object ev = ((SparseArray<?>) sensorsEvents.get(queue)).get(handle);
            if (ev == null) return false;
            Object sensor = eventSensor.get(ev);
            if (!(sensor instanceof Sensor)) return false;
            int type = ((Sensor) sensor).getType();
            return type == Sensor.TYPE_STEP_COUNTER;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把"今天应该显示多少"换算成对累计值的额外增量。
     *
     * 三种模式的语义（用户要求分开）：
     *   1 自动增加：随时间平滑涨（每小时 +N），像真在走路
     *   2 倍率    ：按真实步数放大
     *   3 指定步数：**直接**把今天改到指定值（不是按时间填充），立刻生效
     *
     * 为什么能"直接改"：微信自己算的是「今天的累计 - 今天第一次读到的累计」，
     * 我们只要让 fake 的"今日增量"等于目标值就行 —— 记下今天第一次读到的原始累计当基准，
     * 之后 fake = raw + (目标 - 真实今日量)，微信算出来就是目标值。
     */
    private static double fake(double raw) {
        try {
            int mode = Storage.num(K_MODE, 2);
            int cap = Math.max(1000, Storage.num(K_CAP, DEFAULT_CAP));
            double added;
            switch (mode) {
                case 1: {   // 倍率
                    double k = Math.max(10, Storage.num(K_MUL, DEFAULT_MUL10)) / 10.0;
                    added = raw * (k - 1.0);
                    break;
                }
                case 3: {   // 直接指定今日步数：把"还差多少"一次性注入（只增不减）
                    int target = Math.max(100, Storage.num(K_TARGET, DEFAULT_TARGET));
                    double realToday = Math.max(0, raw - baseOf(raw));
                    double need = target - realToday;
                    if (need > injected) injected = Math.min(cap, need);
                    added = injected;
                    break;
                }
                default: {  // 自动增加
                    int rate = Math.max(10, Storage.num(K_RATE, DEFAULT_RATE));
                    added = rate * (minutesToday() / 60.0);
                    break;
                }
            }
            if (added < 0) added = 0;
            if (added > cap) added = cap;
            double out = raw + added;
            logSample(raw, out, mode, "sensor");
            return out;
        } catch (Throwable ignored) {
            return raw;
        }
    }

    /*
     * 为什么模式 3 必须"注入增量"而不是"加固定偏移"：
     * 微信算的是「今天的累计 − 今天第一次读到的累计」，是个**差值**。
     *   固定偏移：fake = raw + C  →  今天 = (raw2 + C) − (raw1 + C) = 真实步数   ✗ 白改
     *   注入增量：fake = raw + inj →  今天 = 真实步数 + inj                     ✓ 想给多少给多少
     * inj 取「目标 − 真实今日」，一次注入到位；之后只增不减，避免计数倒退。
     */

    /** 是不是微信主进程（主进程名 == 包名）。 */
    private static boolean isMainProcess() {
        try {
            String p = android.app.Application.getProcessName();
            if (p == null || p.isEmpty()) return true;      // 取不到就别乱跳，按主进程处理
            return Entry.HOST.equals(p);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 已经注入的总量（模式 3）。 */
    private static volatile double injected = 0;
    /** 今天的基准累计值（第一次读到的那个）。 */
    private static volatile double baseRaw = -1;
    private static volatile int baseDay = -1;

    private static double baseOf(double raw) {
        int day = (int) (System.currentTimeMillis() / 86400000L);
        if (day != baseDay || baseRaw < 0 || raw < baseRaw) {
            baseDay = day;
            baseRaw = raw;      // 新的一天 / 传感器重启 → 重新开始注入
            injected = 0;
        }
        return baseRaw;
    }

    /** 每个取值方法记上一次的值：变过至少一次才认为它是"活的"数值（步数会变，常量不会）。 */
    private static final java.util.Map<String, double[]> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean looksAlive(String key, double value) {
        double[] prev = lastSeen.get(key);
        if (prev == null) {
            lastSeen.put(key, new double[]{value, 0});
            return false;                       // 第一次见到：先记账，不动它
        }
        if (prev[0] != value) {
            prev[0] = value;
            prev[1] = 1;                        // 变过 → 认定是活值
        }
        return prev[1] == 1;
    }

    /** 临时诊断：看看钩子到底有没有吃到计步器的事件（发布版不带日志，这里只在 DEBUG 打开时输出）。 */
    private static volatile int seen = 0;
    /** 微信层的取值一旦真的吃到步数量级的值，就停掉传感器层改写，免得同一份数据被改两遍。 */
    private static volatile boolean utilActive = false;

    private static void logSample(double raw, double out, int mode, String from) {
        if (seen > 20) return;
        seen++;
        Log.d(T + "step#" + seen + " mode=" + mode + " " + raw + " -> " + out + "  @ " + from);
    }

    private static double minutesToday() {
        long now = System.currentTimeMillis();
        long dayStart = now - (now + java.util.TimeZone.getDefault().getRawOffset()) % 86400000L;
        double mins = (now - dayStart) / 60000.0;
        return Math.max(0.0, Math.min(1439.0, mins));
    }

    private static String modeName() {
        switch (Storage.num(K_MODE, 2)) {
            case 1:
                return "倍率 " + (Storage.num(K_MUL, DEFAULT_MUL10) / 10.0) + "×";
            case 3:
                return "今日目标 " + Storage.num(K_TARGET, DEFAULT_TARGET) + " 步";
            default:
                return "每小时 +" + Storage.num(K_RATE, DEFAULT_RATE) + " 步";
        }
    }

    /** 面板上的状态文案（开着的时候顺便报一下当前模式）。 */
    static String label() {
        return Prefs.onDefault(K_ON) ? modeName() : "已关闭";
    }

    private static Field findField(Class<?> owner, String name) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private Steps() {}
}
