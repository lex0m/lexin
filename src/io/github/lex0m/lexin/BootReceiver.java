package io.github.lex0m.lexin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机把监听服务拉起来（Android 12+ 对 BOOT_COMPLETED 启动前台服务有豁免）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String a = intent == null ? null : intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                    || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
                WorkService.kick(context);
            }
        } catch (Throwable ignored) {
            // 接收器里绝不抛
        }
    }
}
