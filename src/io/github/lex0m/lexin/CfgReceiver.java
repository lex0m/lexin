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

    /**
     * 语音转码线程池（单线程就够：一次只转一条，几十毫秒）。
     *
     * 为什么用 goAsync 而不是起 Service：Android 12+ 拒绝后台应用启动前台服务
     * （ForegroundServiceStartNotAllowedException），而"另一个 pid 的应用替微信发起"
     * 更会被判后台。BroadcastReceiver.goAsync() 允许在 10 秒预算内自由开线程，
     * silk→mp3 远低于这个量级，是唯一"零界面且不被系统拦"的路。
     */
    private static final java.util.concurrent.ExecutorService EXPORT =
            java.util.concurrent.Executors.newSingleThreadExecutor();

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
            } else if (CfgBus.VOICE_REQ.equals(a)) {
                // ── 语音转 MP3（v1.7 零界面链路）──
                byte[] silk = intent.getByteArrayExtra(CfgBus.EXTRA_BYTES);
                final String name = intent.getStringExtra(CfgBus.EXTRA_NAME);
                final int kbps = intent.getIntExtra(CfgBus.EXTRA_KBPS, 48);
                String nm = name == null ? "voice" : name;
                // ★ 诊断模式下的自测通道：`am broadcast` 没法传 byte[]，只给 base64。
                //   只在诊断日志打开时生效；发布状态（诊断关）一律要求带 MAC 的 byte[]。
                boolean testMode = false;
                if (silk == null) {
                    try {
                        if (Log.runtimeOn()) {
                            String b64 = intent.getStringExtra("yb");
                            if (b64 != null && !b64.isEmpty()) {
                                silk = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
                                testMode = true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                if (!testMode && !Sig.ok(CfgBus.VOICE_REQ + "|" + nm + "|" + kbps + "|"
                        + (silk == null ? 0 : silk.length), intent.getStringExtra(CfgBus.EXTRA_SIG))) return;
                if (silk == null || silk.length == 0) return;
                final Context app = context.getApplicationContext() == null
                        ? context : context.getApplicationContext();
                // 匿名内部类要 effectively-final：上面的 silk 是可赋值的（测试通道会重置它）
                final byte[] data = silk;
                final PendingResult pr = goAsync();
                EXPORT.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            VoiceExport.R r = VoiceExport.convert(app, data, nm, kbps);
                            String payload;
                            if (r.ok()) {
                                // 微信侧只负责显示，不再需要知道任何路径细节
                                payload = "ok|" + r.shown + "|" + r.size + "|" + r.seconds;
                            } else {
                                payload = "err|" + (r.err == null ? "未知错误" : r.err) + "|-|-";
                            }
                            CfgBus.pushVoiceResult(app, payload);
                            Log.a("voice-export: " + payload);
                        } catch (Throwable t) {
                            try {
                                CfgBus.pushVoiceResult(app, "err|" + t.getClass().getSimpleName() + "|-|-");
                            } catch (Throwable ignored) {
                            }
                        } finally {
                            pr.finish();     // 必须收尾，否则系统会记一次"接收器超时"
                        }
                    }
                });
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
