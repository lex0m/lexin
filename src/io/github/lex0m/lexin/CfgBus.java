package io.github.lex0m.lexin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

/**
 * 配置总线：模块进程 ⇄ 微信进程，**不走 ContentProvider**。
 *
 * 为什么不能用 provider（v1.5 实测）：微信 targetSdk 34 且没有 QUERY_ALL_PACKAGES，
 * 它**看不见模块包**，于是 {@code content://io.github.lex0m.lexin.bg/...} 在微信侧解析失败
 * （logcat 实证：{@code IllegalArgumentException: Unknown authority}）。v1.4 的"设置页改完
 * 同步给微信"因此从来没生效过，只是异常被 catch 吞掉。
 *
 * 广播不受包可见性限制，是本机唯一还能用的跨进程通道：
 *   · 微信启动  → 发 {@link #REQ}    → 模块侧静态接收器被系统拉起
 *   · 模块侧    → 发 {@link #FULL}   → 微信侧动态接收器刷新内存镜像
 *   · 微信改配置 → 发 {@link #PUT}    → 模块侧落盘 .cfg，再回一条 FULL
 *
 * 隐蔽性：action 是短随机串、不带任何语义；模块侧的静态接收器是唯一新增组件，
 * 名字用极短类名，manifest 里不带权限（它只认自己这几条 action，别的什么也不做）。
 */
final class CfgBus {

    /** 微信 → 模块：请求全量。 */
    static final String REQ = "x.q";
    /** 模块 → 微信：全量配置（k=v 列表）。 */
    static final String FULL = "x.f";
    /** 微信 → 模块：改一项。 */
    static final String PUT = "x.p";
    /**
     * 微信 → 模块：语音转码请求（v1.7）。
     *
     * 以前这条路是 startActivity(ConvertActivity) —— 透明主题也是 Activity，
     * 一点导出就把前台从微信切走，转完还得手动切回来。现在改成广播：
     * 字节直传（语音只几十 KB，Binder 事务上限 1MB 够用），模块侧 goAsync 转码，
     * 全程零界面。也正因为不落中间文件，不再受"App 读不到公共目录"的限制。
     */
    static final String VOICE_REQ = "x.c";
    /** 模块 → 微信：语音转码结果（微信侧收到后弹 Toast，用户不用切 App）。 */
    static final String VOICE_DONE = "x.e";
    /**
     * 微信 → 模块：适配状态镜像（面板读的那份 status.txt）。
     *
     * v1.5 修：这条以前走的是 BgProvider 的 call()，而微信看不见模块包
     * （targetSdk 34 且没有 QUERY_ALL_PACKAGES）⇒ Unknown authority ⇒ 异常被吞，
     * 于是面板状态从 v1.4 起就没再更新过（status.txt 一直停在旧时间）。
     */
    static final String STATUS = "x.s";

    /**
     * 模块侧接收器的组件名。
     *
     * v1.5.1 修：只给 action + setPackage 的**隐式**广播在本机解析不到接收器
     * （实测 `am broadcast -a x.s -p io.github.lex0m.lexin` → result=0 无人接收，
     * 改成 `-n io.github.lex0m.lexin/.CfgReceiver` 立刻写盘）⇒ 配置同步从 v1.4 起
     * 其实一直是断的。显式指定组件后不再依赖系统的隐式广播解析。
     */
    private static final String RECV = "io.github.lex0m.lexin.CfgReceiver";

    /** 防伪 MAC（见 {@link Sig}）：第三方 App 算不出来，伪造的广播直接丢。 */
    static final String EXTRA_SIG = "g";

    static final String EXTRA_KV = "k";
    static final String EXTRA_KEY = "a";
    static final String EXTRA_VAL = "b";
    /** 语音字节（byte[]，只在 VOICE_REQ 上用）。 */
    static final String EXTRA_BYTES = "y";
    /** 来源名（会话名/文件名，只用于给 mp3 命名与索引）。 */
    static final String EXTRA_NAME = "n";
    /** 码率 kbps（0/缺省 = 48）。 */
    static final String EXTRA_KBPS = "r";

    private static volatile boolean armed = false;

    /**
     * 微信侧：注册动态接收器，拿到全量就写进 Storage 的内存镜像；同时发一次 REQ。
     * 只允许在微信进程调用。
     */
    /** 全量载荷：发送方与接收方用同一份拼法，否则 MAC 对不上。 */
    static String payloadFull(java.util.List<String> kv) {
        StringBuilder sb = new StringBuilder(FULL);
        if (kv != null) {
            for (String line : kv) sb.append('|').append(line == null ? "" : line);
        }
        return sb.toString();
    }

    static void watchHost(Context ctx) {
        if (armed || ctx == null) return;
        armed = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null) return;
                        if (VOICE_DONE.equals(i.getAction())) {
                            String v = i.getStringExtra(EXTRA_VAL);
                            if (v == null) return;
                            if (!Sig.ok(VOICE_DONE + "|" + v, i.getStringExtra(EXTRA_SIG))) return;
                            toastVoice(v);
                            return;
                        }
                        if (!FULL.equals(i.getAction())) return;
                        java.util.ArrayList<String> kv = i.getStringArrayListExtra(EXTRA_KV);
                        if (kv == null) return;
                        if (!Sig.ok(payloadFull(kv), i.getStringExtra(EXTRA_SIG))) return;   // 伪造的丢弃
                        Storage.applyRemote(kv);
                        // 配置是异步到的，Features 里那次 Log.setRuntime(Storage.diag())
                        // 永远读到默认值（广播还没回来）。这里补一次，诊断开关才真的能开。
                        Log.setRuntime(Storage.diag());
                    } catch (Throwable ignored) {
                        // 接收器里绝不抛
                    }
                }
            };
            IntentFilter f = new IntentFilter(FULL);
            f.addAction(VOICE_DONE);   // 语音转码结果（模块进程 → 微信进程）
            if (Build.VERSION.SDK_INT >= 33) {
                // 必须 EXPORTED：发送方是模块进程（另一个 uid）
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            requestAll(ctx);
        } catch (Throwable t) {
            Storage.diag("bus watch: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 微信侧：把转码结果显示出来（v1.7）。
     *
     * 为什么在微信进程弹：模块进程在后台，Android 12+ 对后台 Toast 有限制（常常直接被丢），
     * 只有正在前台的那个应用弹才稳。
     */
    private static void toastVoice(final String payload) {
        try {
            String[] p = payload.split("\\|");
            final String tip;
            if (p.length >= 2 && "ok".equals(p[0])) {
                long size = p.length > 2 ? parseLong(p[2]) : 0;
                int sec = p.length > 3 ? (int) parseLong(p[3]) : 0;
                tip = "语音已导出 MP3：" + p[1]
                        + (size > 0 ? "（" + Math.max(1, size / 1024) + "KB" : "")
                        + (sec > 0 ? " · 约 " + sec + " 秒" : "")
                        + (p.length > 1 ? "）" : "");
            } else {
                tip = "语音导出失败：" + (p.length > 1 ? p[1] : payload);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Context ctx = android.app.AndroidAppHelper.currentApplication();
                        if (ctx != null) {
                            android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable ignored) {
                        // 弹不出来不影响文件已落盘
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 微信侧：请求模块推送全量配置。 */
    static void requestAll(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(REQ);
            i.putExtra(EXTRA_SIG, Sig.of(REQ));
            ctx.sendBroadcast(toModule(i));
        } catch (Throwable ignored) {
            // 模块没装/被禁：拿不到就用默认值
        }
    }

    /** 微信侧：把一项改动交给模块进程落盘。 */
    static void putRemote(Context ctx, String key, String encoded) {
        if (ctx == null || key == null) return;
        try {
            Intent i = toModule(new Intent(PUT));
            i.putExtra(EXTRA_KEY, key);
            i.putExtra(EXTRA_VAL, encoded);
            i.putExtra(EXTRA_SIG, Sig.of(PUT + "|" + key + "|" + encoded));
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 发不出去就只在本次会话生效
        }
    }

    /**
     * 模块侧的接收器还在不在。
     *
     * 免 root 内嵌框架（LSPatch/NPatch）下模块**没有独立安装、也没有自己的组件**，
     * {@code setClassName(io.github.lex0m.lexin, …)} 解析不到任何接收器 ——
     * 广播发出去是"发成功但永远没人收"，调用方看到 true 会以为已经接管，
     * 结果就是用户点了导出、什么都不发生、连 Toast 都没有。
     *
     * 这里先问 PackageManager 一次（manifest 里已声明对模块包的 <queries>，不受包可见性影响），
     * 解析不到就明确返回 false，让上层走"该框架不支持"的提示，而不是静默黑屏。
     */
    static boolean moduleReachable(Context ctx) {
        if (ctx == null) return false;
        try {
            Intent probe = toModule(new Intent(VOICE_REQ));
            java.util.List<android.content.pm.ResolveInfo> l =
                    ctx.getPackageManager().queryBroadcastReceivers(probe, 0);
            return l != null && !l.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 微信侧：把语音字节交给模块进程转码并落到工作目录（v1.7）。
     *
     * @return 是否成功发出（发出≠转码成功，结果另走 VOICE_DONE）；
     *         免 root 内嵌框架下模块组件不存在，这里直接返回 false
     */
    static boolean exportVoice(Context ctx, byte[] silk, String name, int kbps) {
        if (ctx == null || silk == null || silk.length == 0) return false;
        if (!moduleReachable(ctx)) {
            Storage.diag("voice: module receiver unreachable（免 root 框架？）");
            return false;
        }
        try {
            Intent i = toModule(new Intent(VOICE_REQ));
            i.putExtra(EXTRA_BYTES, silk);
            // MAC 的拼法必须与 CfgReceiver 完全一致（name 先归一，别一边 null 一边 "voice"）
            String nm = name == null ? "voice" : name;
            i.putExtra(EXTRA_NAME, nm);
            i.putExtra(EXTRA_KBPS, kbps);
            i.putExtra(EXTRA_SIG, Sig.of(VOICE_REQ + "|" + nm + "|" + kbps + "|" + silk.length));
            ctx.sendBroadcast(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模块侧：把转码结果回给微信进程（微信侧弹 Toast / 面板刷新）。 */
    static void pushVoiceResult(Context ctx, String payload) {
        if (ctx == null || payload == null) return;
        try {
            Intent i = new Intent(VOICE_DONE);
            i.setPackage(Entry.HOST);
            i.putExtra(EXTRA_VAL, payload);
            i.putExtra(EXTRA_SIG, Sig.of(VOICE_DONE + "|" + payload));
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 回不去不影响文件已经落盘
        }
    }

    /** 微信侧：把适配状态推给模块进程落盘（面板读 status.txt）。 */
    static void pushStatus(Context ctx, String text) {
        if (ctx == null || text == null || text.isEmpty()) return;
        try {
            Intent i = toModule(new Intent(STATUS));
            i.putExtra(EXTRA_VAL, text);
            i.putExtra(EXTRA_SIG, Sig.of(STATUS + "|" + text));
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 推不动就只在微信进程内有效（面板看不到，功能不受影响）
        }
    }

    /** 模块侧：把当前全量推给微信。 */
    static void pushAll(Context ctx, java.util.Map<String, String> mem) {
        if (ctx == null || mem == null) return;
        try {
            java.util.ArrayList<String> kv = new java.util.ArrayList<>(mem.size());
            for (java.util.Map.Entry<String, String> e : mem.entrySet()) {
                kv.add(e.getKey() + "=" + e.getValue());
            }
            Intent i = new Intent(FULL);
            i.setPackage(Entry.HOST);
            i.putStringArrayListExtra(EXTRA_KV, kv);
            i.putExtra(EXTRA_SIG, Sig.of(payloadFull(kv)));
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 推不动不影响模块侧自身
        }
    }

    /** 微信 → 模块：一律显式组件，免得被系统的隐式广播解析/ROM 管控吞掉。 */
    private static Intent toModule(Intent i) {
        i.setClassName(Entry.MODULE, RECV);
        i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return i;
    }

    private CfgBus() {}
}
