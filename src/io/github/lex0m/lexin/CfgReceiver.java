package io.github.lex0m.lexin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 模块侧的配置总线接点（manifest 静态注册，平时不占进程）。
 *
 * 只有两条 action 会被理会，其余一律无视：
 *   {@link CfgBus#REQ} 微信要全量 → 直接把 .cfg 的镜像推回去
 *   {@link CfgBus#PUT} 微信改一项 → 落盘 .cfg，再推一份最新全量
 *
 * 为什么用静态接收器：微信发广播时模块进程多半没起来，动态注册没人收；
 * 静态注册由系统按需拉起，是"配置能跨进程又不落微信目录"的唯一活路。
 */
public class CfgReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (context == null || intent == null) return;
            String a = intent.getAction();
            if (a == null) return;
            Storage.init(context);
            if (CfgBus.REQ.equals(a)) {
                if (!Sig.ok(a, intent.getStringExtra(CfgBus.EXTRA_SIG))) return;   // 只认本包私钥
                CfgBus.pushAll(context, Storage.snapshot());
            } else if (CfgBus.PUT.equals(a)) {
                String k = intent.getStringExtra(CfgBus.EXTRA_KEY);
                String v = intent.getStringExtra(CfgBus.EXTRA_VAL);
                if (k == null || v == null) return;
                if (!Sig.ok(a + "|" + k + "|" + v, intent.getStringExtra(CfgBus.EXTRA_SIG))) return;
                Storage.putEncoded(k, v);
                CfgBus.pushAll(context, Storage.snapshot());
            } else if (CfgBus.STATUS.equals(a)) {
                // 微信侧推来的适配状态：原样落盘，面板读的时候零解析
                String s = intent.getStringExtra(CfgBus.EXTRA_VAL);
                if (s == null || s.isEmpty()) return;
                if (!Sig.ok(a + "|" + s, intent.getStringExtra(CfgBus.EXTRA_SIG))) return;
                java.io.File f = new java.io.File(context.getFilesDir(), "status.txt");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                try {
                    fo.write(s.getBytes("UTF-8"));
                    fo.flush();
                } finally {
                    fo.close();
                }
            }
        } catch (Throwable ignored) {
            // 接收器里绝不抛：抛出去会让系统判成 ANR
        }
    }
}
