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

    static final String EXTRA_KV = "k";
    static final String EXTRA_KEY = "a";
    static final String EXTRA_VAL = "b";

    private static volatile boolean armed = false;

    /**
     * 微信侧：注册动态接收器，拿到全量就写进 Storage 的内存镜像；同时发一次 REQ。
     * 只允许在微信进程调用。
     */
    static void watchHost(Context ctx) {
        if (armed || ctx == null) return;
        armed = true;
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    try {
                        if (i == null || !FULL.equals(i.getAction())) return;
                        java.util.ArrayList<String> kv = i.getStringArrayListExtra(EXTRA_KV);
                        if (kv == null) return;
                        Storage.applyRemote(kv);
                    } catch (Throwable ignored) {
                        // 接收器里绝不抛
                    }
                }
            };
            IntentFilter f = new IntentFilter(FULL);
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

    /** 微信侧：请求模块推送全量配置。 */
    static void requestAll(Context ctx) {
        if (ctx == null) return;
        try {
            Intent i = new Intent(REQ);
            i.setPackage(Entry.MODULE);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 模块没装/被禁：拿不到就用默认值
        }
    }

    /** 微信侧：把一项改动交给模块进程落盘。 */
    static void putRemote(Context ctx, String key, String encoded) {
        if (ctx == null || key == null) return;
        try {
            Intent i = new Intent(PUT);
            i.setPackage(Entry.MODULE);
            i.putExtra(EXTRA_KEY, key);
            i.putExtra(EXTRA_VAL, encoded);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 发不出去就只在本次会话生效
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
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
            // 推不动不影响模块侧自身
        }
    }

    private CfgBus() {}
}
