package io.github.lex0m.lexin;

import android.app.Activity;
import android.os.Bundle;

/**
 * 无界面触发口：`adb shell am start -n io.github.lex0m.lexin/.DiagActivity` 就直接导出诊断包。
 *
 * 为什么单独一个 Activity（v1.7.11）：
 *   现场排查最常见的情况是「微信卡着 / 面板打不开 / 用户不在旁边」，这时候只能从 adb 抓东西。
 *   放在设置页里要人点一下，等于把「能不能排查」绑在「用户愿不愿意配合」上。
 *
 * 安全取向：`exported="false"` —— 只有 root / shell 能拉起，普通 App 拉不动；
 * 导出的内容也不出本机（写进 Download/乐信/诊断/）。
 */
public class DiagActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Storage.init(getApplicationContext());
        final long start = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String where;
                try {
                    where = Diagnosis.export(DiagActivity.this);
                } catch (Throwable t) {
                    where = null;
                }
                Log.a("svc-diag: adb export -> " + where
                        + " (" + (System.currentTimeMillis() - start) + "ms)");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }
        }, "lexin-diag-adb").start();
    }
}
