package io.github.lex0m.lexin;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

/**
 * 工作目录监听（v1.7.2）。
 *
 * 背景：微信进程和乐信 App 之间的所有跨进程方式在这台设备上都不通
 * （广播被 ColorOS 的 Greezer 丢弃、provider/bind/Activity 被包可见性挡死）。
 * 唯一不受影响的是 **MediaStore**：微信 targetSdk 34，往 Downloads 写文件不需要任何权限，
 * 而且全程在它自己进程里完成。
 *
 * 所以链路改成「信箱模式」：
 *   微信：点导出 → 把 SILK 写进 Download/乐信/待转换/req_*.silk（MediaStore，零权限）
 *   乐信：本类监听 Downloads 集合 → 发现待转换文件 → 转 MP3 → 落 Download/乐信/语音/ → 删原件
 *
 * 监听用 ContentObserver 而不是轮询：系统会在 Downloads 变化时回调，几乎零开销。
 */
final class WorkDirWatcher {

    private static final String T = "svc-work: ";
    static final String PENDING_DIR = "Download/乐信/待转换";
    private static volatile boolean armed = false;
    /**
     * 防重入（v1.7.5）。
     *
     * 真机第一次跑就抓到了：同一个待转换文件被处理了三遍，产出 3 个同名 mp3。
     * 原因是 drain 有两个触发源（Application.onCreate 和 Service.onCreate 各扫一次，
     * 加上 ContentObserver 回调），它们并发跑，各自 listFiles 都看到同一个文件。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean draining =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static ContentObserver observer;

    /** App 启动时调一次；重复调用无副作用。 */
    static void install(Context ctx) {
        if (ctx == null || armed) return;
        armed = true;
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        try {
            observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    // 任何变化都扫一遍待转换目录（数量少，代价可忽略）
                    drain(app);
                }
            };
            app.getContentResolver().registerContentObserver(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer);
            Log.a(T + "observer armed");
        } catch (Throwable t) {
            Log.a(T + "observer failed: " + t.getClass().getSimpleName());
        }
        // 启动先扫一遍：进程被杀期间积累的请求不能漏
        drain(app);
    }

    /**
     * 把待转换目录里的文件全部处理掉。
     *
     * ★ 为什么用**文件系统扫描**而不是 ContentResolver 查询（真机踩出来的）：
     *   待转换的文件是**微信**插进 MediaStore 的，Android 11+ 的 scoped storage 规定
     *   应用用 ContentResolver 只能看到**自己创建的**文件 ⇒ 乐信查出来是空集，
     *   文件就在那儿却永远不处理（真机实测：req_1794...silk 躺了半小时没人管）。
     *   拿到「所有文件访问」权限后直接读目录才是对的。
     */
    static void drain(final Context ctx) {
        if (ctx == null) return;
        if (!draining.compareAndSet(false, true)) return;   // 已经在扫了，直接返回
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!hasAllFiles()) {
                        Log.a(T + "no MANAGE_EXTERNAL_STORAGE, skip drain");
                        return;
                    }
                    java.io.File dir = pendingDir();
                    if (dir == null || !dir.isDirectory()) return;
                    java.io.File[] files = dir.listFiles();
                    if (files == null) return;
                    int done = 0;
                    for (java.io.File f : files) {
                        if (done >= 5) break;                 // 一轮最多 5 条
                        if (f == null || !f.isFile()) continue;
                        String name = f.getName();
                        if (!name.endsWith(".silk")) continue;
                        long size = f.length();
                        if (size <= 0 || size > VoiceExport.MAX_INPUT) continue;
                        if (handleFile(ctx, f, name)) done++;
                    }
                    if (done > 0) Log.a(T + "converted " + done);
                } catch (Throwable t) {
                    Log.a(T + "drain failed: " + t.getClass().getSimpleName());
                } finally {
                    draining.set(false);
                }
            }
        }, "workdir").start();
    }

    /** 待转换目录（公共 Downloads 下）。 */
    static java.io.File pendingDir() {
        try {
            java.io.File dl = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            return new java.io.File(dl, "乐信/待转换");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 是否已拿到「所有文件访问」权限（Android 11+ 才有这个概念）。 */
    static boolean hasAllFiles() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30) return true;
            return android.os.Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 申请「所有文件访问」：跳到系统的授权页。 */
    static void requestAllFiles(Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            try {
                ctx.startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 处理一个文件（文件系统路径版）。 */
    private static boolean handleFile(Context ctx, java.io.File f, String name) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > VoiceExport.MAX_INPUT) return false;
                bos.write(buf, 0, n);
            }
            byte[] silk = bos.toByteArray();
            if (silk.length < 8) return false;
            VoiceExport.R r = VoiceExport.convert(ctx, silk, name, 0);
            if (!r.ok()) {
                Log.a(T + "convert failed: " + r.err + " name=" + name);
                return false;
            }
            Log.a(T + "converted " + name + " -> " + r.shown);
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
            try {
                Notice.done(ctx, r.shown, r.size);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            Log.a(T + "handleFile failed: " + t.getClass().getSimpleName());
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean handle(Context ctx, ContentResolver cr, Uri file, String name) {
        java.io.InputStream in = null;
        try {
            in = cr.openInputStream(file);
            if (in == null) return false;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > VoiceExport.MAX_INPUT) return false;
                bos.write(buf, 0, n);
            }
            byte[] silk = bos.toByteArray();
            if (silk.length < 8) return false;

            VoiceExport.R r = VoiceExport.convert(ctx, silk, name, 0);
            if (!r.ok()) {
                Log.a(T + "convert failed: " + r.err + " name=" + name);
                return false;      // 保留原件，方便下次重试
            }
            Log.a(T + "converted " + name + " -> " + r.shown);
            try {
                cr.delete(file, null, null);          // 成功才删请求文件
            } catch (Throwable ignored) {
            }
            // 转好了给个系统通知（App 可能在后台）
            try {
                Notice.done(ctx, r.shown, r.size);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            Log.a(T + "handle failed: " + t.getClass().getSimpleName());
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private WorkDirWatcher() {}
}
