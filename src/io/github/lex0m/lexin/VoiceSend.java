package io.github.lex0m.lexin;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * 语音消息"官方发送"实现（照抄 WeKit 的 WeMessageApi.sendVoice 配方）。
 *
 * 流程（WeKit 原样）：
 *   1. fileName = voiceNameGen(talker, "amr_")            生成语音名
 *   2. dest     = <账户目录>/voice2/msg_<fileName>.amr    目标路径（自己拼，省掉 pathGen）
 *   3. 复制文件到 dest
 *   4. setVoice(fileName, duration, 0, null)              VoiceLogic.setVoice
 *   5. getVoiceService() → startRecvAndSend(service)      启动语音服务 ⇒ 真正发出去
 *
 * ★ 必须主线程执行：WeKit 明确注明——在无 Looper 的 IO 线程调 send，会抛
 *   "looper and serial is null!" 并导致发送卡死转圈（只能重启微信）。
 *
 * 坐标（微信 8.0.78 / 3180）：
 *   getAmrFullPath   pv.p0#ej   (ou5.x,String,boolean) → String
 *   voiceNameGen     v61.d1#h   (String,String) → String
 *   setVoice         v61.d1#u   (String,int,int,com.tencent.mm.storage.e9) → boolean
 *   getVoiceService  v61.v0#dj  () → yl.y0
 *   startRecvAndSend yl.y0#b    (yl.y0) → void
 */
final class VoiceSend {

    private static final String T = "svc-voice:";

    private static final String C_GET_PATH = "pv.p0";
    private static final String M_GET_PATH = "ej";
    private static final String C_NAME = "v61.d1";
    private static final String M_NAME = "h";
    private static final String M_SET = "u";
    private static final String C_SVC_GET = "v61.v0";
    private static final String M_SVC_GET = "dj";
    private static final String C_SVC = "yl.y0";
    private static final String M_SVC_SEND = "b";

    private VoiceSend() {
    }

    /** 对外入口：把一条语音消息转发到指定会话（会话由微信的选人界面给出）。 */
    static void forward(final Object msg, final String talker, final Runnable onFail) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    String src = amrPath(msg, talker);
                    if (src == null || !new File(src).isFile()) {
                        Log.d(T + "拿不到语音文件路径 src=" + src);
                        if (onFail != null) onFail.run();
                        return;
                    }
                    // ★ 决定性检查：真 AMR 文件头必定是 "#!AMR"；否则就是加密/占位文件，发出去必然空语音 ✗
            String head = head(src);
            long len = new File(src).length();
            Log.d(T + "src=" + src + " size=" + len + " head=" + head);
            if (!"2321414D52".equalsIgnoreCase(head)) {
                Log.d(T + "源文件不是可发送的 AMR（加密或占位）⇒ 改走导出/收藏方案");
                ForwardPlus.toastPublic("这条语音是加密存放的，正在导出原文件");
                if (onFail != null) onFail.run();
                return;
            }
            if (send(talker, src, durationOf(msg))) {
                        Log.d(T + "voice sent ok: " + src);
                        ForwardPlus.toastPublic("语音已发送");
                    } else {
                        Log.d(T + "voice send failed");
                        ForwardPlus.toastPublic("语音发送失败（详见日志）");
                    }
                } catch (Throwable t) {
                    Log.d(T + "send error: " + t);
                    ForwardPlus.toastPublic("语音发送异常：" + t.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 拿语音文件的真实路径（微信自己的 getAmrFullPath）。
     * ★ 它是**实例方法**（之前当静态调用 → NPE: null receiver ✗），
     *   而 pv.p0 有无参构造 ⇒ new 一个实例再调 ✓。
     */
    private static String amrPath(Object msg, String talker) {
        Object inst = null;
        try {
            Class<?> c = Class.forName(C_GET_PATH, false, Features.loader);
            try {
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                inst = ctor.newInstance();
            } catch (Throwable t) {
                Log.d(T + "实例化失败: " + t);
            }
            String tk = talker == null ? "" : talker;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(M_GET_PATH)) continue;
                if (m.getReturnType() != String.class) continue;
                m.setAccessible(true);
                int pc = m.getParameterCount();
                Object r;
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    r = m.invoke(null, msg, tk, false);
                } else {
                    r = m.invoke(inst, msg, tk, false);
                }
                if (r instanceof String) {
                    Log.d(T + "amrPath ok(" + pc + "参): " + r);
                    return (String) r;
                }
            }
        } catch (Throwable t) {
            Log.d(T + "amrPath fail: " + t);
        }
        return null;
    }

    private static int durationOf(Object msg) {
        try {
            Object v = field(msg, "field_voiceLength");
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
            // 忽略
        }
        return 1000;
    }

    /** 核心：造一条自己的语音并触发发送。 */
    private static boolean send(String talker, String srcPath, int durationMs) {
        try {
            File src = new File(srcPath);
            File acc = accDirOf(srcPath);
            if (acc == null) {
                Log.d(T + "accDir 解析失败: " + srcPath);
                return false;
            }
            String name = genName(talker);
            if (name == null) {
                Log.d(T + "voiceNameGen 失败");
                return false;
            }
            File voice2 = new File(acc, "voice2");
            if (!voice2.isDirectory() && !voice2.mkdirs()) {
                Log.d(T + "voice2 目录不可用: " + voice2);
                return false;
            }
            // ★ 目标路径必须走微信自己的 pathGen（它会算分桶子目录 ✗ 自己拼的路径微信读不到 ⇒ 发出去是空的 ✗）
            File dest = resolveDest(voice2, name);
            if (dest == null) {
                Log.d(T + "pathGen 全部候选都失败，退回扁平路径");
                dest = new File(voice2, "msg_" + name + ".amr");
            }
            copy(src, dest);
            if (dest.length() <= 0) {
                Log.d(T + "复制后文件为空: " + dest.getAbsolutePath() + " src=" + srcPath + " srcLen=" + src.length());
                return false;
            }
            Log.d(T + "copied -> " + dest.getAbsolutePath() + " " + dest.length() + "B");

            boolean ok = setVoice(name, durationMs);
            Log.d(T + "setVoice=" + ok + " name=" + name);
            return startService();
        } catch (Throwable t) {
            Log.d(T + "send error: " + t);
            return false;
        }
    }

    /** pathGen 的候选（微信 8.0.78 实测：static，5 参，第 5 个是 int，返回 String）。 */
    private static final String[][] PATH_GEN = {
            {"i04.z", "nj"}, {"i04.z", "oj"}, {"k04.h2", "H"}, {"dx0.r", "t"},
    };

    /**
     * 用微信自己的 pathGen 算目标路径：逐个候选调用，取**父目录真实存在**且含 voice2 的结果。
     * 自己拼路径 = 微信读不到 = 发出去是空语音 ✗（用户实测过）。
     */
    private static File resolveDest(File voice2, String name) {
        String root = voice2.getAbsolutePath();
        if (!root.endsWith("/")) root = root + "/";
        for (String[] cand : PATH_GEN) {
            try {
                Class<?> c = Class.forName(cand[0], false, Features.loader);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(cand[1])) continue;
                    if (m.getReturnType() != String.class) continue;
                    if (m.getParameterCount() != 5) continue;
                    m.setAccessible(true);
                    Object r = m.invoke(null, root, "msg_", name, ".amr", 2);
                    if (!(r instanceof String)) continue;
                    String path = (String) r;
                    if (!path.contains("voice2")) continue;
                    File f = new File(path);
                    File parent = f.getParentFile();
                    if (parent == null) continue;
                    if (!parent.isDirectory()) parent.mkdirs();
                    if (parent.isDirectory()) {
                        Log.d(T + "pathGen hit " + cand[0] + "#" + cand[1] + " -> " + path);
                        return f;
                    }
                }
            } catch (Throwable ignored) {
                // 下一个候选
            }
        }
        return null;
    }

    /** 账户目录：.../MicroMsg/<hash>/（从语音文件路径里截 "voice2/" 之前）。 */
    private static File accDirOf(String path) {
        int i = path.indexOf("/voice2/");
        if (i < 0) i = path.indexOf("/voice3/");
        if (i < 0) return null;
        return new File(path.substring(0, i));
    }

    private static String genName(String talker) {
        try {
            Class<?> c = Class.forName(C_NAME, false, Features.loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(M_NAME)) continue;
                if (m.getReturnType() != String.class) continue;
                if (m.getParameterCount() != 2 || m.getParameterTypes()[0] != String.class) continue;
                m.setAccessible(true);
                Object r = m.invoke(null, talker == null ? "" : talker, "amr_");
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable t) {
            Log.d(T + "genName fail: " + t);
        }
        return null;
    }

    private static boolean setVoice(String name, int durationMs) {
        try {
            Class<?> c = Class.forName(C_NAME, false, Features.loader);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(M_SET)) continue;
                if (m.getReturnType() != boolean.class) continue;
                if (m.getParameterCount() != 4) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (m.getParameterTypes()[1] != int.class) continue;
                m.setAccessible(true);
                Object r = m.invoke(null, name, Math.max(1, Math.min(durationMs, 60000)), 0, null);
                return r instanceof Boolean && (Boolean) r;
            }
        } catch (Throwable t) {
            Log.d(T + "setVoice fail: " + t);
        }
        return false;
    }

    private static boolean startService() {
        try {
            Class<?> svcCls = Class.forName(C_SVC, false, Features.loader);
            Class<?> getterCls = Class.forName(C_SVC_GET, false, Features.loader);
            Object svc = null;
            for (Method m : getterCls.getDeclaredMethods()) {
                if (!m.getName().equals(M_SVC_GET)) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != svcCls) continue;
                m.setAccessible(true);
                svc = m.invoke(null);
                break;
            }
            if (svc == null) {
                Log.d(T + "getVoiceService 返回 null");
                return false;
            }
            for (Method m : svcCls.getDeclaredMethods()) {
                if (!m.getName().equals(M_SVC_SEND)) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != svcCls) continue;
                m.setAccessible(true);
                m.invoke(null, svc);
                Log.d(T + "startRecvAndSend invoked");
                return true;
            }
        } catch (Throwable t) {
            Log.d(T + "startService fail: " + t);
        }
        return false;
    }

    /** 读文件前 5 字节的十六进制（AMR 正常是 2321414D52 = "#!AMR"）。 */
    private static String head(String path) {
        java.io.FileInputStream is = null;
        try {
            is = new java.io.FileInputStream(path);
            byte[] b = new byte[5];
            int n = is.read(b);
            if (n <= 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(String.format("%02X", b[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
        }
    }

    private static void copy(File in, File out) throws Exception {
        FileInputStream is = new FileInputStream(in);
        FileOutputStream os = new FileOutputStream(out);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { is.close(); } catch (Throwable ignored) { }
            try { os.close(); } catch (Throwable ignored) { }
        }
    }

    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
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
}
