package io.github.lex0m.lexin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻监听服务（v1.7.3）。
 *
 * 目的：让 {@link WorkDirWatcher} 不依赖"用户手动打开一次乐信 App"。
 * 微信把待转换的语音写进 Download/乐信/待转换/ 之后，本服务即时接手转码。
 *
 * 为什么必须是前台服务：Android 8+ 后台服务会被冻结，ContentObserver 收不到回调；
 * 只有前台服务（带一条常驻通知）能在后台持续接收 MediaStore 的变化。
 *
 * 启动时机：乐信 App 被用户打开时（有前台 Activity，才有权启动 FGS）。
 * 开机自启走 BOOT_COMPLETED（Android 12+ 对 BOOT_COMPLETED 启动 FGS 有豁免）。
 */
public class WorkService extends Service {

    private static final String CH = "lexin_watch";
    private static final int ID = 0x4C46;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "乐信后台",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("保持语音导出通道可用");
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setContentTitle("乐信正在监听工作目录")
                    .setContentText("语音导出后会自动转成 MP3")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true);
            Notification n = b.build();
            // v1.7.9（系统兼容）：Android 14 起前台服务必须给出类型，
            // 不传虽然多数 ROM 会按 manifest 兜底，但严格实现会抛
            // MissingForegroundServiceTypeException 直接崩在 onCreate。
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID, n);
            }
        } catch (Throwable ignored) {
            // 前台化失败也不影响下面的监听（只是存活时间短）
        }
        WorkDirWatcher.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        WorkDirWatcher.install(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 从最近任务里被划掉之后把自己拉回来（v1.7.4）。
     *
     * 为什么用 AlarmManager 而不是直接 startService：划掉任务的瞬间，进程仍处于
     * "刚被用户杀掉"的状态，直接重启会被系统判为后台启动而拒绝；用一个 2 秒后的
     * 精确闹钟由系统代发，成功率明显更高，也不违反后台限制。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent i = new Intent(this, WorkService.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getService(
                    this, 0x4C46, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(ALARM_SERVICE);
            if (am != null) {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                        android.os.SystemClock.elapsedRealtime() + 2000, pi);
            }
        } catch (Throwable ignored) {
            // 拉不回来就等下次开机
        }
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Android 15（API 35）的 `dataSync` 前台服务有 **6 小时累计上限**（v1.7.9）。
     *
     * 超时后系统会回调这里并停掉前台状态：如果不处理，通知消失、服务降到普通后台，
     * 在 ColorOS 上很快被冻结 ⇒ 语音导出"莫名其妙不转了"，而且日志里没有任何报错。
     *
     * 处理方式：主动摘掉前台通知（DETACH，不杀进程）+ 10 分钟后由系统闹钟把自己排回来。
     * 用 ELAPSED_REALTIME_WAKEUP + allowWhileIdle，不需要精确闹钟权限（Android 12+ 收紧过）。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        try {
            stopForeground(STOP_FOREGROUND_DETACH);
            scheduleRevive();
        } catch (Throwable ignored) {
            // 排不回来就等下次开机 / 下次打开 App
        }
        try {
            super.onTimeout(startId, fgsType);
        } catch (Throwable ignored) {
            // API < 35 上没有这个方法，永远不会走到这里
        }
    }

    /** 10 分钟后由系统代发一次启动（用于 6 小时上限之后自愈）。 */
    private void scheduleRevive() {
        try {
            Intent i = new Intent(this, WorkService.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getService(
                    this, 0x4C47, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000L, pi);
        } catch (Throwable ignored) {
            // 忽略：下一次开机或用户打开 App 时还会拉起
        }
    }

    /** 从任意前台上下文拉起（失败静默：系统拒绝后台起前台服务时就是失败）。 */
    static void kick(Context ctx) {
        try {
            Intent i = new Intent(ctx, WorkService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
            // 起不来就退回到"用户打开 App 时转换"
        }
    }
}
