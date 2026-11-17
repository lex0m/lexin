package io.github.lex0m.lexin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 完成后给一条系统通知（v1.7.2）。
 *
 * 为什么用通知而不是 Toast：转码是**异步**发生的（微信把请求写进工作目录后，
 * 由 {@link WorkDirWatcher} 在乐信 App 侧接手），那时用户可能已经切到别的应用了。
 * Android 12+ 对后台 Toast 有严格限制（经常直接被丢），只有通知栏稳。
 */
final class Notice {

    private static final String CH = "lexin_work";
    private static final int ID = 0x4C45;

    static void done(Context ctx, String path, long size) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "乐信导出",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("语音转换完成提醒");
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CH)
                    : new Notification.Builder(ctx);
            b.setContentTitle("语音已导出 MP3")
                    .setContentText((path == null ? "" : path)
                            + (size > 0 ? "（" + Math.max(1, size / 1024) + "KB）" : ""))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true);
            nm.notify(ID, b.build());
        } catch (Throwable ignored) {
            // 通知发不出去不影响文件已经落盘
        }
    }

    private Notice() {}
}
